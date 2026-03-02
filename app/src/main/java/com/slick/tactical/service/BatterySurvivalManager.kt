package com.slick.tactical.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import com.slick.tactical.engine.health.DeviceHealthManager
import com.slick.tactical.util.SlickConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Monitors Android power save mode and battery level, managing [OperationalState] transitions.
 *
 * When [OperationalState.SURVIVAL_MODE] is active:
 * - MapLibre GPU rendering is suspended by the UI layer observing [systemState]
 * - Wi-Fi Direct is terminated; P2P degrades to BLE heartbeats
 * - GPS throttles to 30-second hardware batches
 * - Screen shows text-only HUD
 *
 * Register [initializePowerListener] before the convoy session starts.
 * Deregister via [destroy] when the service is torn down.
 */
@Singleton
class BatterySurvivalManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val healthManager: DeviceHealthManager,
) {

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    private val _systemState = MutableStateFlow(OperationalState.FULL_TACTICAL)

    /**
     * Observed by [com.slick.tactical.service.ConvoyForegroundService] and UI composables.
     * Emits [OperationalState.SURVIVAL_MODE] when the device enters battery saver.
     */
    val systemState: StateFlow<OperationalState> = _systemState.asStateFlow()

    private var powerReceiver: BroadcastReceiver? = null

    /**
     * Registers the [PowerManager.ACTION_POWER_SAVE_MODE_CHANGED] broadcast receiver
     * and performs an immediate state evaluation.
     *
     * Must be called from [com.slick.tactical.service.ConvoyForegroundService.onCreate].
     *
     * @return Result indicating success or registration failure
     */
    fun initializePowerListener(): Result<Unit> = try {
        val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                evaluatePowerState()
            }
        }
        context.registerReceiver(receiver, filter)
        powerReceiver = receiver

        // Evaluate immediately in case power saver is already active
        evaluatePowerState()

        Timber.d("BatterySurvivalManager initialized at %s", LocalTime.now().format(timeFormatter))
        Result.success(Unit)
    } catch (e: Exception) {
        Timber.e(e, "Failed to initialize power listener")
        Result.failure(Exception("Power listener registration failed: ${e.localizedMessage}", e))
    }

    /**
     * Unregisters the broadcast receiver. Call from [com.slick.tactical.service.ConvoyForegroundService.onDestroy].
     */
    fun destroy() {
        powerReceiver?.let {
            runCatching { context.unregisterReceiver(it) }
                .onFailure { e -> Timber.w(e, "Power receiver was already unregistered") }
        }
        powerReceiver = null
    }

    private fun evaluatePowerState() {
        val batteryLevel = healthManager.getBatteryLevelPercent().getOrElse { -1 }
        val isPowerSaveActive = powerManager.isPowerSaveMode
        val isBatteryLow = batteryLevel in 0..SlickConstants.SURVIVAL_MODE_BATTERY_THRESHOLD

        val newState = if (isPowerSaveActive || isBatteryLow) {
            OperationalState.SURVIVAL_MODE
        } else {
            OperationalState.FULL_TACTICAL
        }

        if (_systemState.value != newState) {
            val time = LocalTime.now().format(timeFormatter)
            Timber.i("Transitioning to %s at %s (powerSave=%b, battery=%d%%)", newState, time, isPowerSaveActive, batteryLevel)
            _systemState.value = newState
        }
    }
}

/**
 * Represents the two operational states of the SLICK engine room.
 *
 * Transitions are driven by [BatterySurvivalManager] and observed by:
 * - [com.slick.tactical.engine.location.BatchedLocationManager] (GPS polling rate)
 * - [com.slick.tactical.engine.mesh.ConvoyMeshManager] (Wi-Fi Direct vs BLE)
 * - UI layer (map visibility vs text-only HUD)
 */
enum class OperationalState {
    /** 60fps map, 1Hz GPS, Wi-Fi Direct mesh. Normal riding mode. */
    FULL_TACTICAL,

    /** Text-only HUD, 30s GPS, BLE heartbeats only. Battery or thermal preservation. */
    SURVIVAL_MODE,
}
