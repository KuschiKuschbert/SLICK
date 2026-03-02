package com.slick.tactical.ui.inflight

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slick.tactical.data.local.entity.WeatherNodeEntity
import com.slick.tactical.data.repository.RouteRepository
import com.slick.tactical.engine.audio.AudioRouteManager
import com.slick.tactical.engine.location.GpsStateHolder
import com.slick.tactical.engine.mesh.ConvoyMeshManager
import com.slick.tactical.engine.mesh.DetourManager
import com.slick.tactical.engine.mesh.RiderState
import com.slick.tactical.engine.navigation.NavigationState
import com.slick.tactical.engine.navigation.RouteStateHolder
import com.slick.tactical.service.BatterySurvivalManager
import com.slick.tactical.service.OperationalState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** Complete UI snapshot for all three HUD zones. Immutable. */
data class InFlightUiState(
    // ── Zone 1: Telemetry ─────────────────────────────────────────────────────
    val speedKmh: Double = 0.0,
    val nextTurnArrow: String = "↑",
    val nextTurnInstruction: String = "Follow route",
    val nextTurnDistanceMetres: Int = 0,
    val eta24h: String = "--:--",

    // ── Zone 2: Map ───────────────────────────────────────────────────────────
    val riderLat: Double = -26.7380,
    val riderLon: Double = 153.1230,
    val riderBearingDeg: Float = 0f,
    val navState: NavigationState = NavigationState(),
    val weatherNodes: List<WeatherNodeEntity> = emptyList(),
    val convoyRiders: Map<String, RiderState> = emptyMap(),

    // ── Zone 3 / system ───────────────────────────────────────────────────────
    val isAudioMuted: Boolean = false,
    val operationalMode: OperationalState = OperationalState.FULL_TACTICAL,
    val hazardPins: List<Pair<Double, Double>> = emptyList(),
)

/**
 * ViewModel for the In-Flight HUD.
 *
 * Data flows:
 * - [GpsStateHolder] → rider position + speed → Zone 1 + Zone 2 camera
 * - [RouteStateHolder] → full polyline + maneuvers → Zone 2 map + Zone 1 turn instruction
 * - [RouteRepository.observeNodes] → weather nodes → Zone 2 GripMatrix gradient
 * - [BatterySurvivalManager] → operational state → survival mode navigation
 * - [ConvoyMeshManager] → convoy riders → Zone 2 badges
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
) : ViewModel() {

    private val _state = MutableStateFlow(InFlightUiState())
    val state: StateFlow<InFlightUiState> = _state.asStateFlow()

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

    /** Live GripMatrix nodes from Room DB → Zone 2 weather gradient. */
    private fun observeWeatherNodes() {
        viewModelScope.launch {
            routeRepository.observeNodes().collectLatest { nodes ->
                _state.value = _state.value.copy(weatherNodes = nodes)
                Timber.d("InFlight: %d weather nodes updated", nodes.size)
            }
        }
    }

    /** Convoy rider positions from P2P mesh → Zone 2 badges. */
    private fun observeConvoyRiders() {
        viewModelScope.launch {
            convoyMeshManager.connectedRiders.collectLatest { riders ->
                _state.value = _state.value.copy(convoyRiders = riders)
            }
        }
    }

    /**
     * Live GPS from [GpsStateHolder] → Zone 1 speed + Zone 2 camera.
     *
     * Also triggers next-turn computation whenever position changes.
     * Computation runs on IO dispatcher to avoid blocking the main thread.
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
                computeNextTurn(gps.lat, gps.lon)
            }
        }
    }

    /** Route polyline + maneuvers from [RouteStateHolder] → Zone 2 route line. */
    private fun observeRoute() {
        viewModelScope.launch {
            routeStateHolder.state.collectLatest { navState ->
                _state.value = _state.value.copy(navState = navState)
                Timber.d("InFlight: route updated (%d pts, %d maneuvers)",
                    navState.fullPolyline.size, navState.maneuvers.size)
            }
        }
    }

    /**
     * Computes the next turn instruction from the rider's current position.
     * Runs on IO to avoid blocking the main thread for large polylines.
     */
    private fun computeNextTurn(lat: Double, lon: Double) {
        viewModelScope.launch(Dispatchers.Default) {
            val turn = routeStateHolder.computeNextTurn(lat, lon)
            _state.value = _state.value.copy(
                nextTurnArrow = turn.arrow,
                nextTurnInstruction = turn.instruction,
                nextTurnDistanceMetres = turn.distanceMetres,
            )
        }
    }

    // ─── User actions ─────────────────────────────────────────────────────────

    /**
     * Marks a hazard at the current rider GPS position.
     * Stores locally and broadcasts to convoy.
     */
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
    }
}
