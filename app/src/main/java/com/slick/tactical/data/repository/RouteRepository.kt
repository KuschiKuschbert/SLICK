package com.slick.tactical.data.repository

import com.slick.tactical.data.local.dao.ShelterDao
import com.slick.tactical.data.local.dao.WeatherNodeDao
import com.slick.tactical.data.local.entity.WeatherNodeEntity
import com.slick.tactical.data.remote.OpenMeteoClient
import com.slick.tactical.data.remote.OverpassClient
import com.slick.tactical.data.remote.ValhallRoutingClient
import com.slick.tactical.engine.weather.Coordinate
import com.slick.tactical.engine.weather.GripMatrix
import com.slick.tactical.engine.weather.RouteForecaster
import com.slick.tactical.engine.weather.SolarGlareVector
import com.slick.tactical.engine.weather.TwilightMatrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline-first weather data repository.
 *
 * The UI always reads from [WeatherNodeDao] via the [observeNodes] Flow.
 * Network data is fetched in the background and written to Room -- the UI
 * updates automatically when the database changes.
 *
 * Three sync strategies (user selectable in settings):
 * 1. [syncPeriodic] -- WorkManager triggers every 30 min while riding
 * 2. [syncOnStop] -- triggered by Activity Recognition (IN_VEHICLE → STILL)
 * 3. [syncOnce] -- pre-flight only, no further network calls
 *
 * If network is unavailable, Room serves stale cached data.
 * The UI displays "Last synced at HH:mm" to inform the rider.
 */
@Singleton
class RouteRepository @Inject constructor(
    private val weatherNodeDao: WeatherNodeDao,
    private val shelterDao: ShelterDao,
    private val openMeteoClient: OpenMeteoClient,
    private val overpassClient: OverpassClient,
    private val valhallClient: ValhallRoutingClient,
    private val routeForecaster: RouteForecaster,
    private val gripMatrix: GripMatrix,
    private val twilightMatrix: TwilightMatrix,
    private val solarGlareVector: SolarGlareVector,
) {

    /**
     * Observed by the UI layer. Emits updated node list whenever Room changes.
     * This is the single source of truth for the GripMatrix polyline gradient.
     */
    fun observeNodes(): Flow<List<WeatherNodeEntity>> = weatherNodeDao.observeAllNodes()

    /**
     * Full pre-flight pipeline:
     * 1. Fetch route polyline from Valhalla (motorcycle costing)
     * 2. Slice into 10km GripMatrix nodes via Haversine
     * 3. Sync Open-Meteo weather for each node
     *
     * @param origin Start GPS coordinate
     * @param destination End GPS coordinate
     * @param averageSpeedKmh Rider's expected cruising speed in km/h
     * @param departureTime24h Planned departure time in HH:mm (24h)
     * @return Result containing number of weather nodes synced
     */
    suspend fun fetchRouteAndSync(
        origin: Coordinate,
        destination: Coordinate,
        averageSpeedKmh: Double,
        departureTime24h: String,
    ): Result<Int> = withContext(Dispatchers.IO) {
        // Step 1: Get polyline from Valhalla
        val polyline = valhallClient.fetchRoute(origin, destination)
            .getOrElse { return@withContext Result.failure(it) }

        // Step 2: Sync weather nodes
        val nodeCount = syncRouteWeather(polyline, averageSpeedKmh, departureTime24h)
            .getOrElse { return@withContext Result.failure(it) }

        // Step 3: Fetch emergency shelters from Overpass (best-effort, non-blocking failure)
        val corridorId = "${origin.lat}_${origin.lon}_${destination.lat}_${destination.lon}"
        overpassClient.fetchSheltersAlongRoute(
            nodes = polyline.filterIndexed { i, _ -> i % 10 == 0 },  // Sample every 10th point for bbox
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
     * Slices the route and performs a full weather sync for all nodes.
     *
     * Generates node stubs via [RouteForecaster], then fetches Open-Meteo data
     * for each node and populates weather fields.
     *
     * @param polyline Route GPS coordinates from Valhalla
     * @param averageSpeedKmh Rider's expected average speed in km/h
     * @param departureTime24h Departure time in HH:mm (24h)
     * @return Result containing the number of nodes synced, or failure with cause
     */
    suspend fun syncRouteWeather(
        polyline: List<Coordinate>,
        averageSpeedKmh: Double,
        departureTime24h: String,
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val departureTime = LocalTime.parse(departureTime24h, DateTimeFormatter.ofPattern("HH:mm"))

            // Step 1: Generate node stubs via Haversine slicing
            val stubs = routeForecaster.generateNodes(polyline, averageSpeedKmh, departureTime)
                .getOrElse { return@withContext Result.failure(it) }

            Timber.d("Syncing %d weather nodes", stubs.size)
            weatherNodeDao.clearAllNodes()

            // Step 2: Fetch Open-Meteo data for each node and populate weather fields
            val populatedNodes = stubs.mapNotNull { stub ->
                openMeteoClient.fetchNodeWeather(stub.latitude, stub.longitude)
                    .fold(
                        onSuccess = { weather ->
                            val precipList = weather.minutely15.precipitation
                            val precipT30 = precipList.getOrElse(0) { 0.0 }
                            val precipT15 = precipList.getOrElse(1) { 0.0 }
                            val precipNow = precipList.getOrElse(2) { 0.0 }

                            val hourlyIdx = 0  // First hourly slot for current conditions
                            val windSpeed = weather.hourly.windspeed10m.getOrElse(hourlyIdx) { 0.0 }
                            val windDir = weather.hourly.winddirection10m.getOrElse(hourlyIdx) { 0.0 }
                            val temp = weather.hourly.temperature2m.getOrElse(hourlyIdx) { 20.0 }
                            val visibility = weather.hourly.visibility.getOrElse(hourlyIdx) { 10000.0 } / 1000.0

                            val sunriseRaw = weather.daily.sunrise.firstOrNull() ?: ""
                            val sunsetRaw = weather.daily.sunset.firstOrNull() ?: ""

                            // Extract HH:mm from ISO 8601 datetime
                            val sunrise24h = extractTime24h(sunriseRaw)
                            val sunset24h = extractTime24h(sunsetRaw)

                            val isTwilight = twilightMatrix.isTwilightHazard(
                                stub.estimatedArrival24h,
                                sunrise24h,
                                sunset24h,
                            ).getOrElse { false }

                            val isSolarGlare = solarGlareVector.isSolarGlareRisk(
                                stub.latitude,
                                stub.longitude,
                                stub.estimatedArrival24h,
                                stub.routeBearingDeg,
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
                            Timber.w("Weather fetch failed for node %s, using stub: %s", stub.nodeId, e.localizedMessage)
                            null  // Skip nodes where API fails; stubs remain in DB from previous cycle
                        },
                    )
            }

            // Step 3: Persist to Room database
            if (populatedNodes.isNotEmpty()) {
                weatherNodeDao.insertNodes(populatedNodes)
                Timber.i("Weather sync complete: %d/%d nodes populated", populatedNodes.size, stubs.size)
            }

            Result.success(populatedNodes.size)
        } catch (e: Exception) {
            Timber.e(e, "Route weather sync failed")
            Result.failure(Exception("Weather sync failed: ${e.localizedMessage}", e))
        }
    }

    /** Used for on-stop sync (Activity Recognition STILL trigger) and periodic sync. */
    suspend fun syncPeriodic(
        polyline: List<Coordinate>,
        averageSpeedKmh: Double,
        departureTime24h: String,
    ): Result<Int> = syncRouteWeather(polyline, averageSpeedKmh, departureTime24h)

    /** Extracts HH:mm from an ISO 8601 datetime string (e.g., "2026-03-02T06:12"). */
    private fun extractTime24h(isoDateTime: String): String {
        return if (isoDateTime.contains("T")) {
            isoDateTime.substringAfter("T").take(5)
        } else {
            "06:00"  // Safe default if parsing fails
        }
    }
}
