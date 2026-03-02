package com.slick.tactical.engine.imu

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.slick.tactical.util.SlickConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Detects high-speed impacts using the device's internal accelerometer (IMU).
 *
 * Detection criteria (two conditions must be true simultaneously):
 * 1. Severe deceleration: acceleration spike exceeds [CRASH_G_FORCE_THRESHOLD]
 * 2. Activity Recognition confirmed IN_VEHICLE state prior to impact
 *
 * Phone-drop filter: a drop without a preceding IN_VEHICLE state does not trigger SOS.
 * The 60-second countdown gives the rider time to cancel if they're not actually injured.
 *
 * On countdown expiry: fires [SosState.SOS_TRIGGERED] which the service forwards
 * to Supabase (online) or P2P mesh (offline).
 */
@Singleton
class CrashDetectionManager @Inject constructor(
    @ApplicationContext private val context: Context,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    /** G-force threshold for crash detection (~7g peak = severe deceleration at highway speed) */
    private val CRASH_G_FORCE_THRESHOLD = 7.0f

    private val _sosState = MutableStateFlow<SosState>(SosState.Idle)

    /** Observed by [com.slick.tactical.service.ConvoyForegroundService] and the UI. */
    val sosState: StateFlow<SosState> = _sosState.asStateFlow()

    /** Set to true by Activity Recognition when IN_VEHICLE is confirmed. */
    var isInVehicle: Boolean = false

    private var countdownJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    /**
     * Registers the accelerometer listener. Call from [com.slick.tactical.service.ConvoyForegroundService.onCreate].
     *
     * @return Result indicating success or failure (e.g., sensor not available on device)
     */
    fun startMonitoring(): Result<Unit> {
        if (accelerometer == null) {
            return Result.failure(Exception("Accelerometer sensor not available on this device"))
        }
        return try {
            sensorManager.registerListener(
                this,
                accelerometer,
                SensorManager.SENSOR_DELAY_FASTEST,
            )
            Timber.i("CrashDetectionManager: monitoring started")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to start crash monitoring")
            Result.failure(Exception("Crash monitor start failed: ${e.localizedMessage}", e))
        }
    }

    /**
     * Unregisters the accelerometer listener. Call from [com.slick.tactical.service.ConvoyForegroundService.onDestroy].
     */
    fun stopMonitoring() {
        sensorManager.unregisterListener(this)
        countdownJob?.cancel()
        _sosState.value = SosState.Idle
        Timber.i("CrashDetectionManager: monitoring stopped")
    }

    /**
     * Cancels an active SOS countdown. Called when rider taps the cancel button.
     * The rider is conscious and does not need assistance.
     */
    fun cancelSos() {
        countdownJob?.cancel()
        _sosState.value = SosState.Idle
        Timber.i("SOS countdown cancelled at %s -- rider conscious", LocalTime.now().format(timeFormatter))
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_LINEAR_ACCELERATION) return

        // Calculate total linear acceleration magnitude (m/s²)
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitudeMs2 = sqrt((x * x + y * y + z * z).toDouble()).toFloat()

        // Convert to g-force (1g ≈ 9.81 m/s²)
        val gForce = magnitudeMs2 / SensorManager.GRAVITY_EARTH

        // Phone-drop filter: must have been IN_VEHICLE to trigger SOS
        if (gForce > CRASH_G_FORCE_THRESHOLD && isInVehicle && _sosState.value == SosState.Idle) {
            onImpactDetected(gForce)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // Accelerometer accuracy changes are not actionable for crash detection
    }

    private fun onImpactDetected(gForce: Float) {
        val triggeredAt = LocalTime.now().format(timeFormatter)
        Timber.w("IMPACT DETECTED at %s: %.1fg -- starting %ds SOS countdown", triggeredAt, gForce, SlickConstants.SOS_COUNTDOWN_SECONDS)

        _sosState.value = SosState.Countdown(
            secondsRemaining = SlickConstants.SOS_COUNTDOWN_SECONDS,
            triggeredAt24h = triggeredAt,
        )

        countdownJob = scope.launch {
            for (remaining in (SlickConstants.SOS_COUNTDOWN_SECONDS - 1) downTo 0) {
                delay(1_000L)
                val currentState = _sosState.value
                if (currentState !is SosState.Countdown) return@launch  // Cancelled
                _sosState.value = currentState.copy(secondsRemaining = remaining)
            }
            // Countdown complete -- fire SOS
            Timber.e("SOS TRIGGERED at %s -- rider unresponsive", LocalTime.now().format(timeFormatter))
            _sosState.value = SosState.SOS_TRIGGERED(triggeredAt24h = LocalTime.now().format(timeFormatter))
        }
    }
}

/**
 * States of the SOS detection system.
 * Transitions: Idle → Countdown → (CANCELLED → Idle) or (SOS_TRIGGERED)
 */
sealed class SosState {
    /** No impact detected. Normal monitoring. */
    object Idle : SosState()

    /**
     * Impact detected. Countdown active. Rider has [secondsRemaining] seconds to cancel.
     * UI shows large countdown and "CANCEL" button.
     */
    data class Countdown(
        val secondsRemaining: Int,
        val triggeredAt24h: String,
    ) : SosState()

    /**
     * Countdown expired. SOS alert must be transmitted immediately.
     * Service handles transmission via Supabase (online) or P2P/BLE (offline).
     */
    data class SOS_TRIGGERED(val triggeredAt24h: String) : SosState()
}
