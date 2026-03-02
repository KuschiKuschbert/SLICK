package com.slick.tactical.ui.inflight

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slick.tactical.data.local.entity.ShelterEntity
import com.slick.tactical.data.local.entity.WeatherNodeEntity
import com.slick.tactical.data.remote.ShelterType
import com.slick.tactical.data.repository.RouteRepository
import com.slick.tactical.engine.audio.AudioRouteManager
import com.slick.tactical.engine.location.GpsStateHolder
import com.slick.tactical.engine.mesh.ConvoyMeshManager
import com.slick.tactical.engine.mesh.DetourManager
import com.slick.tactical.engine.mesh.RiderState
import com.slick.tactical.engine.navigation.NavigationState
import com.slick.tactical.engine.navigation.RouteStateHolder
import com.slick.tactical.engine.weather.GripMatrix
import com.slick.tactical.service.BatterySurvivalManager
import com.slick.tactical.service.OperationalState
import com.slick.tactical.ui.preflight.DEFAULT_ENABLED_POI_TYPES
import com.slick.tactical.util.slickSettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private val KEY_ENABLED_POI_TYPES_INFLIGHT = stringPreferencesKey("enabled_poi_types")

/** UI state for all three HUD zones. Immutable snapshot. */
data class InFlightUiState(
    // ── Zone 1: Telemetry ─────────────────────────────────────────────────────
    val speedKmh: Double = 0.0,
    val nextTurnArrow: String = "↑",
    val nextTurnInstruction: String = "Follow route",
    val nextTurnDistanceMetres: Int = 0,
    val eta24h: String = "--:--",
    val remainingDistanceKm: Double = 0.0,

    // ── Zone 1: Weather hazard strip ─────────────────────────────────────────
    val nextHazard: HazardAlert? = null,
    val nearestShelter: ShelterEntity? = null,

    // ── Zone 2: Map ───────────────────────────────────────────────────────────
    val riderLat: Double = -26.7380,
    val riderLon: Double = 153.1230,
    val riderBearingDeg: Float = 0f,
    val navState: NavigationState = NavigationState(),
    val weatherNodes: List<WeatherNodeEntity> = emptyList(),
    val shelters: List<ShelterEntity> = emptyList(),
    val convoyRiders: Map<String, RiderState> = emptyMap(),

    /** True while the map camera auto-follows the rider. Set to false on user pan. */
    val isFollowingRider: Boolean = true,

    /** POI types currently visible on the map (persisted in DataStore via SettingsViewModel). */
    val enabledPoiTypes: Set<String> = DEFAULT_ENABLED_POI_TYPES,

    /** Next 5 POIs ahead of the rider along the route, sorted by along-route distance. */
    val nextStops: List<ShelterEntity> = emptyList(),

    // ── Zone 3 / system ───────────────────────────────────────────────────────
    val isAudioMuted: Boolean = false,
    val operationalMode: OperationalState = OperationalState.FULL_TACTICAL,
    val hazardPins: List<Pair<Double, Double>> = emptyList(),
)

/**
 * Weather hazard alert surfaced to Zone 1.
 *
 * @property dangerLevel Overall classification for the approaching node
 * @property distanceMetres Straight-line distance to the hazard node
 * @property rainfallStatus Human-readable rain description from GripMatrix
 * @property crosswindKmh Calculated lateral crosswind in km/h
 */
data class HazardAlert(
    val dangerLevel: GripMatrix.DangerLevel,
    val distanceMetres: Int,
    val rainfallStatus: String,
    val crosswindKmh: Double,
)

/**
 * ViewModel for the In-Flight HUD.
 *
 * Data flows:
 * - [GpsStateHolder] → rider GPS → Zone 1 speed + camera, next-turn, hazard computation
 * - [RouteStateHolder] → full polyline + maneuvers → Zone 2 route line, turn instructions
 * - [RouteRepository.observeNodes] → weather nodes → Zone 2 GripMatrix gradient + hazard detection
 * - [RouteRepository.observeShelters] → shelter POIs → Zone 2 markers + Zone 1 "nearest cover" alert
 * - [BatterySurvivalManager] → operational state → survival mode
 * - [ConvoyMeshManager] → convoy rider positions → Zone 2 badges
 *
 * On init, if [RouteStateHolder] is empty (process was killed), automatically restores the last
 * synced route from Room so the rider doesn't need to re-sync weather.
 */
@HiltViewModel
class InFlightViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val batterySurvivalManager: BatterySurvivalManager,
    private val audioRouteManager: AudioRouteManager,
    private val detourManager: DetourManager,
    private val routeRepository: RouteRepository,
    private val convoyMeshManager: ConvoyMeshManager,
    private val gpsStateHolder: GpsStateHolder,
    private val routeStateHolder: RouteStateHolder,
    private val gripMatrix: GripMatrix,
) : ViewModel() {

    private val _state = MutableStateFlow(InFlightUiState())
    val state: StateFlow<InFlightUiState> = _state.asStateFlow()

    /** Tracks the last danger level to detect zone transitions for TTS alerts. */
    private var lastAlertedDangerLevel: GripMatrix.DangerLevel = GripMatrix.DangerLevel.DRY

    private var shelterJob: Job? = null

    init {
        observeBatterySurvivalState()
        observeWeatherNodes()
        observeConvoyRiders()
        observeGps()
        observeRoute()
        observePoiFilters()
        restoreRouteIfNeeded()
    }

    /** Keeps [InFlightUiState.enabledPoiTypes] in sync with the DataStore written by [SettingsViewModel]. */
    private fun observePoiFilters() {
        viewModelScope.launch {
            context.slickSettingsDataStore.data
                .map { prefs ->
                    val raw = prefs[KEY_ENABLED_POI_TYPES_INFLIGHT]
                    if (raw != null) raw.split(",").filter { it.isNotBlank() }.toSet()
                    else DEFAULT_ENABLED_POI_TYPES
                }
                .collectLatest { types ->
                    _state.value = _state.value.copy(enabledPoiTypes = types)
                    Timber.d("InFlight: POI filters updated → %s", types.joinToString())
                }
        }
    }

    private fun observeBatterySurvivalState() {
        viewModelScope.launch {
            batterySurvivalManager.systemState.collectLatest { mode ->
                _state.value = _state.value.copy(operationalMode = mode)
            }
        }
    }

    private fun observeWeatherNodes() {
        viewModelScope.launch {
            routeRepository.observeNodes().collectLatest { nodes ->
                _state.value = _state.value.copy(weatherNodes = nodes)
                Timber.d("InFlight: %d weather nodes updated", nodes.size)
            }
        }
    }

    private fun observeConvoyRiders() {
        viewModelScope.launch {
            convoyMeshManager.connectedRiders.collectLatest { riders ->
                _state.value = _state.value.copy(convoyRiders = riders)
            }
        }
    }

    /**
     * Observes GPS from [GpsStateHolder] and triggers all position-dependent computations:
     * next turn instruction, next hazard node + nearest shelter, remaining distance, TTS alerts.
     */
    private fun observeGps() {
        viewModelScope.launch {
            gpsStateHolder.location.collectLatest { gps ->
                if (!gps.hasFix) return@collectLatest

                _state.value = _state.value.copy(
                    riderLat = gps.lat,
                    riderLon = gps.lon,
                    speedKmh = gps.speedKmh,
                    riderBearingDeg = gps.bearingDeg,
                )

                // Run computations on Default dispatcher -- avoid blocking main thread
                viewModelScope.launch(Dispatchers.Default) {
                    computeNextTurn(gps.lat, gps.lon)
                    computeNextHazardAndShelter(gps.lat, gps.lon)
                    computeRemainingDistance(gps.lat, gps.lon)
                    computeNextStops(gps.lat, gps.lon)
                }
            }
        }
    }

    /** Route polyline + maneuvers → Zone 2 route line; also re-subscribes to shelters. */
    private fun observeRoute() {
        viewModelScope.launch {
            routeStateHolder.state.collectLatest { navState ->
                _state.value = _state.value.copy(navState = navState)
                Timber.d("InFlight: route updated (%d pts, %d maneuvers)",
                    navState.fullPolyline.size, navState.maneuvers.size)

                // Re-subscribe shelter observation when corridor changes
                if (navState.corridorId.isNotBlank()) {
                    shelterJob?.cancel()
                    shelterJob = viewModelScope.launch {
                        routeRepository.observeShelters(navState.corridorId)
                            .collectLatest { shelters ->
                                _state.value = _state.value.copy(shelters = shelters)
                                Timber.d("InFlight: %d shelters loaded for corridor", shelters.size)
                            }
                    }
                }
            }
        }
    }

    /**
     * Restores the last synced route from Room if [RouteStateHolder] is empty.
     *
     * This fires silently on startup and covers the case where the process was killed
     * by the OS (battery saver, OEM task killer) and the rider reopens the app mid-ride.
     * The weather nodes are already in Room; this restores the route polyline and maneuvers.
     */
    private fun restoreRouteIfNeeded() {
        if (routeStateHolder.state.value.hasRoute) return  // already set (fresh session)
        viewModelScope.launch(Dispatchers.IO) {
            val restored = routeRepository.restoreLastRoute()
            if (restored) {
                Timber.i("InFlight: route restored from Room -- no re-sync required")
            } else {
                Timber.d("InFlight: no persisted route found, waiting for pre-flight sync")
            }
        }
    }

    // ─── Navigation computation ───────────────────────────────────────────────

    private fun computeNextTurn(lat: Double, lon: Double) {
        val turn = routeStateHolder.computeNextTurn(lat, lon)
        _state.value = _state.value.copy(
            nextTurnArrow = turn.arrow,
            nextTurnInstruction = turn.instruction,
            nextTurnDistanceMetres = turn.distanceMetres,
        )
    }

    /**
     * Computes straight-line distance from rider to destination.
     *
     * Displayed in Zone 1 so the rider always knows how far they have to go.
     * Uses Haversine (straight-line) for performance -- accurate enough for this display.
     */
    private fun computeRemainingDistance(lat: Double, lon: Double) {
        val destination = routeStateHolder.state.value.destination ?: return
        val distKm = haversineKm(lat, lon, destination.lat, destination.lon)
        _state.value = _state.value.copy(remainingDistanceKm = distKm)
    }

    /**
     * Finds the next 5 POIs **ahead of the rider** along the route direction.
     *
     * Algorithm:
     * 1. Filter shelters to only enabled POI types.
     * 2. Find the rider's nearest polyline index (sample every 10th point for performance).
     * 3. For each filtered shelter, find its nearest polyline index.
     * 4. Keep only shelters whose polyline index > riderIndex (ahead on route).
     * 5. Sort by polyline index ascending (closest along-route first), take 5.
     */
    private fun computeNextStops(lat: Double, lon: Double) {
        val polyline = routeStateHolder.state.value.fullPolyline
        val allShelters = _state.value.shelters
        val enabledTypes = _state.value.enabledPoiTypes

        if (polyline.size < 2 || allShelters.isEmpty()) {
            _state.value = _state.value.copy(nextStops = emptyList())
            return
        }

        val filteredShelters = allShelters.filter { it.type in enabledTypes }
        if (filteredShelters.isEmpty()) {
            _state.value = _state.value.copy(nextStops = emptyList())
            return
        }

        // Find rider's nearest polyline index (sample every 10th for performance)
        val riderPolylineIdx = polyline.indices
            .filter { it % 10 == 0 || it == polyline.lastIndex }
            .minByOrNull { i -> haversineKm(lat, lon, polyline[i].lat, polyline[i].lon) }
            ?: 0

        // For each shelter, find nearest polyline index and keep only forward ones
        data class ShelterWithIdx(val shelter: ShelterEntity, val polyIdx: Int, val distKm: Double)
        val forwardStops = filteredShelters.mapNotNull { shelter ->
            val nearestIdx = polyline.indices
                .filter { it % 10 == 0 || it == polyline.lastIndex }
                .minByOrNull { i ->
                    haversineKm(shelter.latitude, shelter.longitude, polyline[i].lat, polyline[i].lon)
                } ?: return@mapNotNull null

            if (nearestIdx <= riderPolylineIdx) return@mapNotNull null  // behind or at rider
            val distKm = haversineKm(lat, lon, shelter.latitude, shelter.longitude)
            ShelterWithIdx(shelter, nearestIdx, distKm)
        }
            .sortedBy { it.polyIdx }
            .take(5)

        _state.value = _state.value.copy(nextStops = forwardStops.map { it.shelter })
        Timber.d("InFlight: %d next stops computed (riderIdx=%d)", forwardStops.size, riderPolylineIdx)
    }

    /**
     * Finds the next HIGH or EXTREME weather node ahead of the rider,
     * then finds the nearest take-cover POI to that hazard.
     *
     * Triggers a TTS alert when crossing from DRY/MODERATE into HIGH/EXTREME for the first time.
     */
    private fun computeNextHazardAndShelter(lat: Double, lon: Double) {
        val nodes = _state.value.weatherNodes
        val shelters = _state.value.shelters

        if (nodes.isEmpty()) {
            _state.value = _state.value.copy(nextHazard = null, nearestShelter = null)
            return
        }

        // Find the nearest hazardous node that is at least 500m ahead (not already behind us)
        data class NodeWithReport(val node: WeatherNodeEntity, val report: GripMatrix.NodeDangerReport, val distM: Double)
        val nextHazardNode = nodes.mapNotNull { node ->
            val distM = haversineKm(lat, lon, node.latitude, node.longitude) * 1000.0
            if (distM < 500 || distM > 120_000) return@mapNotNull null
            val report = gripMatrix.evaluateNode(node).getOrNull() ?: return@mapNotNull null
            if (report.dangerLevel == GripMatrix.DangerLevel.DRY) return@mapNotNull null
            NodeWithReport(node, report, distM)
        }.minByOrNull { it.distM }

        if (nextHazardNode == null) {
            _state.value = _state.value.copy(nextHazard = null, nearestShelter = null)
            lastAlertedDangerLevel = GripMatrix.DangerLevel.DRY
            return
        }

        val alert = HazardAlert(
            dangerLevel = nextHazardNode.report.dangerLevel,
            distanceMetres = nextHazardNode.distM.toInt(),
            rainfallStatus = nextHazardNode.report.rainfallStatus,
            crosswindKmh = nextHazardNode.report.crosswindKmh,
        )

        val nearestShelter = shelters
            .mapNotNull { shelter ->
                val distKm = haversineKm(nextHazardNode.node.latitude, nextHazardNode.node.longitude,
                    shelter.latitude, shelter.longitude)
                if (distKm > 15.0) null else Pair(shelter, distKm)
            }
            .minByOrNull { (_, d) -> d }
            ?.first

        _state.value = _state.value.copy(nextHazard = alert, nearestShelter = nearestShelter)
        triggerHazardAlertIfNeeded(alert, nearestShelter)
    }

    private fun triggerHazardAlertIfNeeded(alert: HazardAlert, shelter: ShelterEntity?) {
        val isNewDangerZone = alert.dangerLevel > lastAlertedDangerLevel
        if (!isNewDangerZone || _state.value.isAudioMuted) return

        lastAlertedDangerLevel = alert.dangerLevel

        val distKm = alert.distanceMetres / 1000.0
        val message = buildString {
            when (alert.dangerLevel) {
                GripMatrix.DangerLevel.EXTREME -> append("EXTREME HAZARD in %.0f km. ".format(distKm))
                GripMatrix.DangerLevel.HIGH -> append("High danger zone in %.0f km. ".format(distKm))
                else -> append("Conditions deteriorating in %.0f km. ".format(distKm))
            }
            if (alert.rainfallStatus.contains("rain", ignoreCase = true)) append("Rain on road. ")
            if (alert.crosswindKmh > 20.0) append("Crosswind %.0f km/h. ".format(alert.crosswindKmh))
            if (shelter != null) {
                append("${shelter.name} %.1f km away.".format(
                    haversineKm(_state.value.riderLat, _state.value.riderLon,
                        shelter.latitude, shelter.longitude)))
            }
        }

        viewModelScope.launch {
            audioRouteManager.speakTacticalAlert(message)
                .onFailure { e -> Timber.w(e, "Hazard TTS alert failed") }
        }
    }

    // ─── User actions ─────────────────────────────────────────────────────────

    fun onMarkHazard() {
        val lat = _state.value.riderLat
        val lon = _state.value.riderLon
        if (lat == 0.0 && lon == 0.0) {
            Timber.w("MARK HAZARD: no GPS fix yet")
            return
        }
        Timber.i("Hazard marked at %.4f, %.4f", lat, lon)
        _state.value = _state.value.copy(hazardPins = _state.value.hazardPins + Pair(lat, lon))

        val convoyId = convoyMeshManager.currentConvoyId ?: return
        viewModelScope.launch {
            convoyMeshManager.broadcastPosition(
                lat = lat, lon = lon,
                speedKmh = _state.value.speedKmh,
                bearingDeg = _state.value.riderBearingDeg.toDouble(),
                role = convoyMeshManager.currentRole,
                convoyId = convoyId,
            ).onFailure { e -> Timber.w(e, "MARK HAZARD broadcast failed") }
        }
    }

    fun onToggleMute() {
        val isMuted = audioRouteManager.toggleMute()
        _state.value = _state.value.copy(isAudioMuted = isMuted)
        if (!isMuted) lastAlertedDangerLevel = GripMatrix.DangerLevel.DRY
    }

    /**
     * Called when the user pans or zooms the map manually.
     * Disengages auto-follow so the rider can inspect the route ahead.
     * The recenter button appears in Zone 2 when follow-mode is disengaged.
     */
    fun onMapInteraction() {
        if (_state.value.isFollowingRider) {
            _state.value = _state.value.copy(isFollowingRider = false)
            Timber.d("InFlight: camera follow disengaged (user interaction)")
        }
    }

    /**
     * Called when the rider taps the recenter button in Zone 2.
     * Re-engages auto-follow and animates the camera back to the rider.
     */
    fun onRecenter() {
        _state.value = _state.value.copy(isFollowingRider = true)
        Timber.d("InFlight: camera follow re-engaged (recenter)")
    }

    // ─── Haversine (local copy to avoid DI dependency just for math) ──────────

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * asin(sqrt(a))
    }
}
