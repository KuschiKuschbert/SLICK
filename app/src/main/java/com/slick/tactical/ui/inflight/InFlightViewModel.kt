package com.slick.tactical.ui.inflight

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slick.tactical.data.local.entity.WeatherNodeEntity
import com.slick.tactical.data.repository.RouteRepository
import com.slick.tactical.engine.audio.AudioRouteManager
import com.slick.tactical.engine.mesh.ConvoyMeshManager
import com.slick.tactical.engine.mesh.DetourManager
import com.slick.tactical.engine.mesh.RiderState
import com.slick.tactical.service.BatterySurvivalManager
import com.slick.tactical.service.OperationalState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** UI state for the In-Flight HUD. Immutable snapshot observed by all three Zones. */
data class InFlightUiState(
    // Zone 1 telemetry
    val speedKmh: Double = 0.0,
    val nextTurnDistanceMetres: Int = 0,
    val nextTurnArrow: String = "→",
    val eta24h: String = "--:--",
    // Zone 2 map
    val riderLat: Double = -26.7380,  // Default: Kawana
    val riderLon: Double = 153.1230,
    val riderBearingDeg: Float = 0f,
    val weatherNodes: List<WeatherNodeEntity> = emptyList(),
    val convoyRiders: Map<String, RiderState> = emptyMap(),
    // Zone 3 interaction
    val isAudioMuted: Boolean = false,
    val operationalMode: OperationalState = OperationalState.FULL_TACTICAL,
    // Hazard pins marked by rider (lat, lon pairs)
    val hazardPins: List<Pair<Double, Double>> = emptyList(),
)

/**
 * ViewModel for the In-Flight HUD.
 *
 * Observes [BatterySurvivalManager.systemState] to trigger survival mode navigation.
 * Relays rider actions (mark hazard, toggle mute) to the appropriate engine managers.
 *
 * The UI only reads from this ViewModel -- it never calls engine managers directly.
 */
@HiltViewModel
class InFlightViewModel @Inject constructor(
    private val batterySurvivalManager: BatterySurvivalManager,
    private val audioRouteManager: AudioRouteManager,
    private val detourManager: DetourManager,
    private val routeRepository: RouteRepository,
    private val convoyMeshManager: ConvoyMeshManager,
) : ViewModel() {

    private val _state = MutableStateFlow(InFlightUiState())

    /** Collected by [InFlightHudScreen] via collectAsState(). */
    val state: StateFlow<InFlightUiState> = _state.asStateFlow()

    init {
        observeBatterySurvivalState()
        observeWeatherNodes()
        observeConvoyRiders()
    }

    private fun observeBatterySurvivalState() {
        viewModelScope.launch {
            batterySurvivalManager.systemState.collectLatest { operationalState ->
                _state.value = _state.value.copy(operationalMode = operationalState)
                Timber.d("InFlightHUD: operational state changed to %s", operationalState)
            }
        }
    }

    /** Live feed of GripMatrix nodes from Room DB → Zone 2 map gradient */
    private fun observeWeatherNodes() {
        viewModelScope.launch {
            routeRepository.observeNodes().collectLatest { nodes ->
                _state.value = _state.value.copy(weatherNodes = nodes)
                Timber.d("InFlightHUD: %d weather nodes updated", nodes.size)
            }
        }
    }

    /** Live convoy rider positions → Zone 2 map badges */
    private fun observeConvoyRiders() {
        viewModelScope.launch {
            convoyMeshManager.connectedRiders.collectLatest { riders ->
                _state.value = _state.value.copy(convoyRiders = riders)
            }
        }
    }

    /**
     * Marks a hazard at the current rider GPS position.
     * - Stores the hazard coordinate in [_state] for Zone2Map to render a pin
     * - Broadcasts to convoy via [ConvoyMeshManager] if active
     */
    fun onMarkHazard() {
        val lat = _state.value.riderLat
        val lon = _state.value.riderLon
        if (lat == 0.0 && lon == 0.0) {
            Timber.w("MARK HAZARD: no GPS fix yet -- skipping")
            return
        }

        Timber.i("Hazard marked at %.4f, %.4f", lat, lon)

        // Add to hazard pins list for Zone2Map rendering
        val updated = _state.value.hazardPins + Pair(lat, lon)
        _state.value = _state.value.copy(hazardPins = updated)

        // Broadcast to convoy if active
        val convoyId = convoyMeshManager.currentConvoyId ?: return
        viewModelScope.launch {
            convoyMeshManager.broadcastPosition(
                lat = lat,
                lon = lon,
                speedKmh = _state.value.speedKmh,
                bearingDeg = _state.value.riderBearingDeg.toDouble(),
                role = convoyMeshManager.currentRole,
                convoyId = convoyId,
            ).onFailure { e -> Timber.w(e, "MARK HAZARD broadcast failed") }
        }
    }

    /**
     * Toggles TTS audio mute via [AudioRouteManager].
     */
    fun onToggleMute() {
        val isMuted = audioRouteManager.toggleMute()
        _state.value = _state.value.copy(isAudioMuted = isMuted)
    }

    /**
     * Updates speed and navigation telemetry from the GPS engine.
     * Called by the service layer as location updates arrive.
     */
    fun updateTelemetry(
        speedKmh: Double,
        distanceToTurnM: Int,
        turnArrow: String,
        eta24h: String,
        lat: Double,
        lon: Double,
        bearingDeg: Float,
    ) {
        _state.value = _state.value.copy(
            speedKmh = speedKmh,
            nextTurnDistanceMetres = distanceToTurnM,
            nextTurnArrow = turnArrow,
            eta24h = eta24h,
            riderLat = lat,
            riderLon = lon,
            riderBearingDeg = bearingDeg,
        )
    }
}
