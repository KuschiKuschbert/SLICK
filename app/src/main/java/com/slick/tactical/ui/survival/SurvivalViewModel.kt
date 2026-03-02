package com.slick.tactical.ui.survival

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slick.tactical.engine.health.DeviceHealthManager
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

data class SurvivalUiState(
    val speedKmh: Double = 0.0,
    val nextTurnDistanceMetres: Int = 0,
    val nextTurnArrow: String = "→",
    val batteryPercent: Int = 15,
    val operationalMode: OperationalState = OperationalState.SURVIVAL_MODE,
)

/**
 * ViewModel for [SurvivalHudScreen].
 *
 * Observes [BatterySurvivalManager.systemState] to detect power restoration.
 * When power is restored (rider plugs into bike USB), emits FULL_TACTICAL
 * which triggers navigation back to [com.slick.tactical.ui.inflight.InFlightHudScreen].
 */
@HiltViewModel
class SurvivalViewModel @Inject constructor(
    private val batterySurvivalManager: BatterySurvivalManager,
    private val healthManager: DeviceHealthManager,
) : ViewModel() {

    private val _state = MutableStateFlow(SurvivalUiState())
    val state: StateFlow<SurvivalUiState> = _state.asStateFlow()

    init {
        observePowerState()
        refreshBatteryLevel()
    }

    private fun observePowerState() {
        viewModelScope.launch {
            batterySurvivalManager.systemState.collectLatest { operationalState ->
                _state.value = _state.value.copy(operationalMode = operationalState)
                Timber.d("SurvivalHUD: operational state = %s", operationalState)
            }
        }
    }

    private fun refreshBatteryLevel() {
        val level = healthManager.getBatteryLevelPercent().getOrElse { 15 }
        _state.value = _state.value.copy(batteryPercent = level)
    }

    fun updateTelemetry(speedKmh: Double, distanceToTurnM: Int, turnArrow: String) {
        _state.value = _state.value.copy(
            speedKmh = speedKmh,
            nextTurnDistanceMetres = distanceToTurnM,
            nextTurnArrow = turnArrow,
        )
    }
}
