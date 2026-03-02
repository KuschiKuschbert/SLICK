package com.slick.tactical.data.repository

import com.slick.tactical.data.local.dao.RouteDao
import com.slick.tactical.data.local.dao.ShelterDao
import com.slick.tactical.data.local.dao.WeatherNodeDao
import com.slick.tactical.data.local.entity.RouteEntity
import com.slick.tactical.data.local.entity.WeatherNodeEntity
import com.slick.tactical.data.remote.OpenMeteoClient
import com.slick.tactical.data.remote.OverpassClient
import com.slick.tactical.data.remote.ValhallRoutingClient
import com.slick.tactical.engine.navigation.RouteManeuver
import com.slick.tactical.engine.navigation.RouteStateHolder
import com.slick.tactical.engine.weather.Coordinate
import com.slick.tactical.engine.weather.GripMatrix
import com.slick.tactical.engine.weather.RouteForecaster
import com.slick.tactical.engine.weather.SolarGlareVector
import com.slick.tactical.engine.weather.TwilightMatrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil

/**
 * Offline-first weather data repository.
 *
 * The UI always reads from [WeatherNodeDao] via [observeNodes].
 * Network data is fetched in the background and written to Room -- the UI
 * updates automatically when the database changes.
 *
 * Resilience strategy:
 * - If Valhalla is unavailable: falls back to straight-line Haversine node generation
 *   so that weather data can still be fetched and the polyline can be displayed.
 * - If Open-Meteo fails for an individual node: inserts a stub record with DRY defaults
 *   so the full route polyline is always visible (grey = dry/unknown).
 */
@Singleton
class RouteRepository @Inject constructor(
    private val weatherNodeDao: WeatherNodeDao,
    private val shelterDao: ShelterDao,
    private val routeDao: RouteDao,
    private val openMeteoClient: OpenMeteoClient,
    private val overpassClient: OverpassClient,
    private val valhallClient: ValhallRoutingClient,
    private val routeForecaster: RouteForecaster,
    private val gripMatrix: GripMatrix,
    private val twilightMatrix: TwilightMatrix,
    private val solarGlareVector: SolarGlareVector,
    private val routeStateHolder: RouteStateHolder,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Observed by the UI for GripMatrix gradient colours. */
    fun observeNodes(): Flow<List<WeatherNodeEntity>> = weatherNodeDao.observeAllNodes()

    /**
     * Live shelter POIs for the given route corridor.
     * Collected by [com.slick.tactical.ui.inflight.InFlightViewModel] to render shelter markers.
     */
    fun observeShelters(corridorId: String): Flow<List<com.slick.tactical.data.local.entity.ShelterEntity>> =
        shelterDao.observeSheltersForCorridor(corridorId)

    /**
     * Restores the last synced route from Room into [RouteStateHolder].
     *
     * Called by [com.slick.tactical.ui.inflight.InFlightViewModel] on startup when
     * [RouteStateHolder] is empty due to process death. After this call, the In-Flight
     * HUD polyline, GripMatrix gradient, and navigation state are all restored without
     * requiring the rider to re-sync weather.
     *
     * @return true if a route was found and restored, false if no route exists yet
     */
    suspend fun restoreLastRoute(): Boolean = withContext(Dispatchers.IO) {
        try {
            val entity = routeDao.getLastRoute() ?: return@withContext false

            val polyline = json.decodeFromString<List<Coordinate>>(entity.polylineJson)
            val maneuvers = json.decodeFromString<List<RouteManeuver>>(entity.maneuversJson)

            routeStateHolder.setRoute(
                polyline = polyline,
                maneuvers = maneuvers,
                origin = Coordinate(entity.originLat, entity.originLon),
                destination = Coordinate(entity.destinationLat, entity.destinationLon),
                totalDistanceKm = entity.totalDistanceKm,
                corridorId = entity.corridorId,
            )

            Timber.i("Route restored from Room (%d pts, corridorId=%s)", polyline.size, entity.corridorId)
            true
        } catch (e: Exception) {
            Timber.w(e, "Failed to restore route from Room")
            false
        }
    }

    /**
     * Full pre-flight pipeline:
     * 1. Fetch route polyline from Valhalla (motorcycle costing)
     *    → fallback: straight-line Haversine points if Valhalla unreachable
     * 2. Slice into 10 km GripMatrix nodes via Haversine
     * 3. Sync Open-Meteo weather for each node **in parallel** (all nodes concurrently)
     *    → fallback: DRY stub for any node where the API call fails
     *
     * @param onProgress Called with a value 0.0–1.0 as each node completes. Used by the UI
     *   to display a progress bar. Defaults to a no-op so existing callers don't need updating.
     */
    suspend fun fetchRouteAndSync(
        origin: Coordinate,
        destination: Coordinate,
        averageSpeedKmh: Double,
        departureTime24h: String,
        onProgress: (Float) -> Unit = {},
    ): Result<Int> = withContext(Dispatchers.IO) {
        // Step 1: Get route from Valhalla (polyline + maneuvers), with straight-line fallback
        val valhallResult = valhallClient.fetchRoute(origin, destination)
        val polyline: List<Coordinate>
        val maneuvers: List<com.slick.tactical.engine.navigation.RouteManeuver>
        val totalDistanceKm: Double

        if (valhallResult.isSuccess) {
            val r = valhallResult.getOrThrow()
            polyline = r.polyline
            maneuvers = r.maneuvers
            totalDistanceKm = r.totalDistanceKm
        } else {
            Timber.w(valhallResult.exceptionOrNull(), "Valhalla unavailable -- straight-line fallback")
            polyline = generateStraightLinePoints(origin, destination)
            maneuvers = emptyList()
            totalDistanceKm = 0.0
        }

        val corridorId = "${origin.lat}_${origin.lon}_${destination.lat}_${destination.lon}"

        // Publish full route to RouteStateHolder -- drives Zone2Map polyline + navigation
        routeStateHolder.setRoute(
            polyline = polyline,
            maneuvers = maneuvers,
            origin = origin,
            destination = destination,
            totalDistanceKm = totalDistanceKm,
            corridorId = corridorId,
        )

        // Persist route to Room so it survives process death (no re-sync needed on reopen)
        runCatching {
            routeDao.saveRoute(
                RouteEntity(
                    polylineJson = json.encodeToString(polyline),
                    maneuversJson = json.encodeToString(maneuvers),
                    originLat = origin.lat,
                    originLon = origin.lon,
                    destinationLat = destination.lat,
                    destinationLon = destination.lon,
                    totalDistanceKm = totalDistanceKm,
                    corridorId = corridorId,
                    savedAt = System.currentTimeMillis(),
                ),
            )
            Timber.d("Route saved to Room (%d pts, %d maneuvers)", polyline.size, maneuvers.size)
        }.onFailure { e ->
            Timber.w(e, "Failed to persist route to Room -- in-memory state still valid")
        }

        Timber.i("Route: %d polyline pts, %d maneuvers", polyline.size, maneuvers.size)

        // Step 2: Sync weather nodes (parallel fetches; onProgress updates UI progress bar)
        val nodeCount = syncRouteWeather(polyline, averageSpeedKmh, departureTime24h, onProgress)
            .getOrElse { return@withContext Result.failure(it) }

        // Step 3: Fetch emergency shelters from Overpass (best-effort, non-blocking)
        overpassClient.fetchSheltersAlongRoute(
            nodes = polyline.filterIndexed { i, _ -> i % 10 == 0 },
            routeCorridorId = corridorId,
        ).onSuccess { shelters ->
            shelterDao.clearCorridor(corridorId)
            shelterDao.insertShelters(shelters)
            Timber.i("Overpass: %d shelters cached for corridor", shelters.size)
        }.onFailure { e ->
            Timber.w(e, "Overpass sync failed -- app will still work without POIs")
        }

        Result.success(nodeCount)
    }

    /**
     * Generates a straight-line route as evenly spaced GPS points.
     *
     * Used when Valhalla is unavailable (no network, rate limit, or server error).
     * Produces ~1 point per km between origin and destination.
     * Sufficient for GripMatrix node slicing -- nodes are placed at 10 km intervals
     * regardless of the polyline's actual curvature.
     *
     * @param origin Start coordinate
     * @param destination End coordinate
     * @return List of GPS points interpolated along the great circle
     */
    private fun generateStraightLinePoints(origin: Coordinate, destination: Coordinate): List<Coordinate> {
        val distanceKm = routeForecaster.haversineDistance(origin, destination)
        val pointCount = ceil(distanceKm).toInt().coerceAtLeast(20)  // At least 20 points

        return (0..pointCount).map { i ->
            val fraction = i.toDouble() / pointCount
            Coordinate(
                lat = origin.lat + (destination.lat - origin.lat) * fraction,
                lon = origin.lon + (destination.lon - origin.lon) * fraction,
            )
        }.also {
            Timber.i("Straight-line fallback: %d points for %.1f km route", it.size, distanceKm)
        }
    }

    /**
     * Slices the route and syncs Open-Meteo weather for all nodes **in parallel**.
     *
     * All node HTTP calls are dispatched concurrently via [coroutineScope] + [async]/[awaitAll].
     * For a Kawana→Yeppoon run (~45 nodes) this reduces sync time from ~30 s to ~2 s.
     *
     * Per-node failure strategy: if Open-Meteo returns an error for a node, a DRY stub is
     * inserted instead of dropping the node. This ensures the full route polyline is always
     * visible on the map (grey = dry/unknown, not missing).
     *
     * @param onProgress Called with a value 0.0–1.0 as each node completes. Forwarded from
     *   [fetchRouteAndSync] to drive the UI progress bar.
     */
    suspend fun syncRouteWeather(
        polyline: List<Coordinate>,
        averageSpeedKmh: Double,
        departureTime24h: String,
        onProgress: (Float) -> Unit = {},
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val departureTime = LocalTime.parse(departureTime24h, DateTimeFormatter.ofPattern("HH:mm"))

            val stubs = routeForecaster.generateNodes(polyline, averageSpeedKmh, departureTime)
                .getOrElse { return@withContext Result.failure(it) }

            Timber.d("Syncing %d weather nodes in parallel", stubs.size)
            weatherNodeDao.clearAllNodes()

            // Report 0% at the start so the UI transitions from indeterminate to determinate
            onProgress(0f)
            val completed = AtomicInteger(0)
            val totalNodes = stubs.size

            // Launch all node fetches concurrently. Each coroutine reports progress as it finishes.
            val populatedNodes = coroutineScope {
                stubs.map { stub ->
                    async(Dispatchers.IO) {
                        val node = openMeteoClient.fetchNodeWeather(stub.latitude, stub.longitude)
                            .fold(
                                onSuccess = { weather ->
                                    val precipList = weather.minutely15.precipitation
                                    val precipT30 = precipList.getOrElse(0) { 0.0 }
                                    val precipT15 = precipList.getOrElse(1) { 0.0 }
                                    val precipNow = precipList.getOrElse(2) { 0.0 }

                                    val hourlyIdx = 0
                                    val windSpeed = weather.hourly.windspeed10m.getOrElse(hourlyIdx) { 0.0 }
                                    val windDir = weather.hourly.winddirection10m.getOrElse(hourlyIdx) { 0.0 }
                                    val temp = weather.hourly.temperature2m.getOrElse(hourlyIdx) { 20.0 }
                                    val visibility = weather.hourly.visibility.getOrElse(hourlyIdx) { 10000.0 } / 1000.0

                                    val sunriseRaw = weather.daily.sunrise.firstOrNull() ?: ""
                                    val sunsetRaw = weather.daily.sunset.firstOrNull() ?: ""
                                    val sunrise24h = extractTime24h(sunriseRaw)
                                    val sunset24h = extractTime24h(sunsetRaw)

                                    val isTwilight = twilightMatrix.isTwilightHazard(
                                        stub.estimatedArrival24h, sunrise24h, sunset24h,
                                    ).getOrElse { false }

                                    val isSolarGlare = solarGlareVector.isSolarGlareRisk(
                                        stub.latitude, stub.longitude, stub.estimatedArrival24h, stub.routeBearingDeg,
                                    ).getOrElse { false }

                                    stub.copy(
                                        windSpeedKmh = windSpeed,
                                        windDirDegrees = windDir,
                                        precipT30Mm = precipT30,
                                        precipT15Mm = precipT15,
                                        precipNowMm = precipNow,
                                        tempCelsius = temp,
                                        visibilityKm = visibility,
                                        isTwilightHazard = isTwilight,
                                        isSolarGlareRisk = isSolarGlare,
                                        lastUpdated24h = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")),
                                    )
                                },
                                onFailure = { e ->
                                    // DRY stub: insert the node anyway so the polyline is always visible.
                                    // The rider sees grey (dry/unknown) rather than a missing section.
                                    Timber.w("Weather fetch failed for node %s -- inserting DRY stub: %s",
                                        stub.nodeId, e.localizedMessage)
                                    stub.copy(
                                        lastUpdated24h = "??:??",  // Signals stale/missing data to UI
                                    )
                                },
                            )
                        // Report incremental progress after each node completes
                        val pct = completed.incrementAndGet().toFloat() / totalNodes
                        onProgress(pct)
                        node
                    }
                }.awaitAll()
            }

            if (populatedNodes.isNotEmpty()) {
                weatherNodeDao.insertNodes(populatedNodes)
                val liveCount = populatedNodes.count { it.lastUpdated24h != "??:??" }
                Timber.i("Weather sync: %d/%d nodes with live data, %d stubs",
                    liveCount, stubs.size, populatedNodes.size - liveCount)
            }

            Result.success(populatedNodes.size)
        } catch (e: Exception) {
            Timber.e(e, "Route weather sync failed")
            Result.failure(Exception("Weather sync failed: ${e.localizedMessage}", e))
        }
    }

    /** Periodic re-sync (WorkManager / Activity Recognition stop trigger). Progress not surfaced to UI. */
    suspend fun syncPeriodic(
        polyline: List<Coordinate>,
        averageSpeedKmh: Double,
        departureTime24h: String,
    ): Result<Int> = syncRouteWeather(polyline, averageSpeedKmh, departureTime24h, onProgress = {})

    private fun extractTime24h(isoDateTime: String): String =
        if (isoDateTime.contains("T")) isoDateTime.substringAfter("T").take(5) else "06:00"
}
