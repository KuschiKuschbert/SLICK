package com.slick.tactical.engine.health

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.slick.tactical.util.SlickConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Monitors device thermals and battery to enforce the Musk Protocol:
 * throttle processing when temperature exceeds 40°C.
 *
 * Temperature source: [BatteryManager.EXTRA_TEMPERATURE] (tenths of Celsius).
 */
@Singleton
class DeviceHealthManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    enum class PerformanceMode {
        /** 60fps map, 1Hz GPS, Wi-Fi Direct mesh. Normal operation. */
        FULL_PERFORMANCE,

        /** 15fps map, 5s GPS. Thermal throttling active (>40°C). */
        THROTTLED,

        /** No map render, minimal GPS. Critical thermal state (>45°C). */
        CRITICAL_LOW,
    }

    /**
     * Returns the current performance mode based on device temperature.
     *
     * Called before each GPS poll and map frame budget decision.
     *
     * @return [PerformanceMode] based on current battery temperature
     */
    fun getPerformanceMode(): PerformanceMode {
        val tempCelsius = getBatteryTemperatureCelsius()
            .getOrElse {
                Timber.w("Could not read battery temperature, defaulting to THROTTLED for safety")
                return PerformanceMode.THROTTLED
            }

        return when {
            tempCelsius > SlickConstants.THERMAL_CRITICAL_CELSIUS -> {
                Timber.w("CRITICAL thermal state: %.1f°C -- suspending map render", tempCelsius)
                PerformanceMode.CRITICAL_LOW
            }
            tempCelsius > SlickConstants.THERMAL_THROTTLE_CELSIUS -> {
                Timber.d("Thermal throttle active: %.1f°C", tempCelsius)
                PerformanceMode.THROTTLED
            }
            else -> PerformanceMode.FULL_PERFORMANCE
        }
    }

    /**
     * Returns current battery temperature in Celsius.
     *
     * BatteryManager reports in tenths of a degree (e.g., 320 = 32.0°C).
     *
     * @return Result containing temperature in Celsius, or failure if unavailable
     */
    fun getBatteryTemperatureCelsius(): Result<Double> {
        return try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val intent = context.registerReceiver(null, filter)
                ?: return Result.failure(Exception("Battery intent unavailable"))
            val rawTemp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            if (rawTemp == Int.MIN_VALUE) {
                return Result.failure(Exception("EXTRA_TEMPERATURE not present in battery intent"))
            }
            Result.success(rawTemp / 10.0)
        } catch (e: Exception) {
            Timber.e(e, "Failed to read battery temperature")
            Result.failure(Exception("Battery temperature read failed: ${e.localizedMessage}", e))
        }
    }

    /**
     * Returns current battery level as a percentage (0-100).
     *
     * Used by [com.slick.tactical.service.BatterySurvivalManager] for Survival Mode detection.
     *
     * @return Result containing battery percentage, or failure if unavailable
     */
    fun getBatteryLevelPercent(): Result<Int> {
        return try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val intent = context.registerReceiver(null, filter)
                ?: return Result.failure(Exception("Battery intent unavailable"))
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) {
                return Result.failure(Exception("Invalid battery level data: level=$level scale=$scale"))
            }
            Result.success(level * 100 / scale)
        } catch (e: Exception) {
            Timber.e(e, "Failed to read battery level")
            Result.failure(Exception("Battery level read failed: ${e.localizedMessage}", e))
        }
    }
}
