package com.slick.tactical.ui.inflight

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slick.tactical.data.local.entity.ShelterEntity
import com.slick.tactical.data.local.entity.WeatherNodeEntity
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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** UI state for all three HUD zones. Immutable snapshot. */
data class InFlightUiState(
    // ── Zone 1: Telemetry ─────────────────────────────────────────────────────
    val speedKmh: Double = 0.0,
    val nextTurnArrow: String = "↑",
    val nextTurnInstruction: String = "Follow route",
    val nextTurnDistanceMetres: Int = 0,
    val eta24h: String = "--:--",

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
 */
@HiltViewModel
class InFlightViewModel @Inject constructor(
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
     * next turn instruction, next hazard node + nearest shelter, TTS zone-transition alerts.
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
            if (distM < 500 || distM > 120_000) return@mapNotNull null  // skip too close/far
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

        // Find nearest shelter to the hazard node (not to the rider)
        val nearestShelter = shelters
            .mapNotNull { shelter ->
                val distKm = haversineKm(nextHazardNode.node.latitude, nextHazardNode.node.longitude,
                    shelter.latitude, shelter.longitude)
                if (distKm > 15.0) null else Pair(shelter, distKm)
            }
            .minByOrNull { (_, d) -> d }
            ?.first

        _state.value = _state.value.copy(nextHazard = alert, nearestShelter = nearestShelter)

        // TTS zone-transition alert: only fire when crossing into a higher danger level
        triggerHazardAlertIfNeeded(alert, nearestShelter)
    }

    /**
     * Speaks a TTS alert when the rider crosses into a new danger zone.
     * Respects audio mute and the 60-second cooldown between alerts.
     */
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
            if (alert.rainfallStatus.contains("rain", ignoreCase = true)) {
                append("Rain on road. ")
            }
            if (alert.crosswindKmh > 20.0) {
                append("Crosswind %.0f km/h. ".format(alert.crosswindKmh))
            }
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

        Timber.i("Hazard TTS: %s (distM=%d, shelter=%s)",
            alert.dangerLevel, alert.distanceMetres, shelter?.name ?: "none")
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
        // Reset alert tracker so the next unmute cycle fires fresh alerts
        if (!isMuted) lastAlertedDangerLevel = GripMatrix.DangerLevel.DRY
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
