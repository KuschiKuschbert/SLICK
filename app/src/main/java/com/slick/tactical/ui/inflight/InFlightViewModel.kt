package com.slick.tactical.ui.inflight

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slick.tactical.engine.audio.AudioRouteManager
import com.slick.tactical.engine.mesh.DetourManager
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

/** UI state for the In-Flight HUD. Immutable snapshot observed by the Composable. */
data class InFlightUiState(
    val speedKmh: Double = 0.0,
    val nextTurnDistanceMetres: Int = 0,
    val nextTurnArrow: String = "→",
    val eta24h: String = "--:--",
    val isAudioMuted: Boolean = false,
    val operationalMode: OperationalState = OperationalState.FULL_TACTICAL,
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
) : ViewModel() {

    private val _state = MutableStateFlow(InFlightUiState())

    /** Collected by [InFlightHudScreen] via collectAsState(). */
    val state: StateFlow<InFlightUiState> = _state.asStateFlow()

    init {
        observeBatterySurvivalState()
    }

    private fun observeBatterySurvivalState() {
        viewModelScope.launch {
            batterySurvivalManager.systemState.collectLatest { operationalState ->
                _state.value = _state.value.copy(operationalMode = operationalState)
                Timber.d("InFlightHUD: operational state changed to %s", operationalState)
            }
        }
    }

    /**
     * Marks a hazard at the current rider position.
     * TODO Phase 6: Add hazard pin to MapLibre and broadcast to convoy via ConvoyMeshManager.
     */
    fun onMarkHazard() {
        Timber.i("Hazard marked by rider")
        // TODO Phase 6: Get current GPS position and broadcast hazard to convoy
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
    fun updateTelemetry(speedKmh: Double, distanceToTurnM: Int, turnArrow: String, eta24h: String) {
        _state.value = _state.value.copy(
            speedKmh = speedKmh,
            nextTurnDistanceMetres = distanceToTurnM,
            nextTurnArrow = turnArrow,
            eta24h = eta24h,
        )
    }
}
