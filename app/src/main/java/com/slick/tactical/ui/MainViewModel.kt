package com.slick.tactical.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject

/**
 * Application-level ViewModel for cross-cutting concerns:
 * - Lux sensor monitoring for Direct Sun Mode auto-inversion
 *
 * Direct Sun Mode activates when [Sensor.TYPE_LIGHT] returns max lux (hardware saturation),
 * indicating the phone is in direct sunlight. Inverts the theme to white background / black text
 * to maintain legibility when the OLED dark theme becomes invisible in direct Queensland sun.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel(), SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

    /** Max lux reported by the sensor hardware (device-specific). Typically ~40,000-100,000 lux. */
    private var maxLux: Float = lightSensor?.maximumRange ?: 40_000f

    /** Threshold: 90% of maximum sensor range = "direct sun" */
    private val directSunThreshold: Float get() = maxLux * 0.9f

    private val _isDirectSunMode = MutableStateFlow(false)

    /**
     * Observed by [SlickTheme] in [MainActivity].
     * True when ambient light sensor hits near-maximum -- phone is in direct sunlight.
     */
    val isDirectSunMode: StateFlow<Boolean> = _isDirectSunMode.asStateFlow()

    init {
        lightSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            Timber.d("Lux sensor registered: maxRange=%.0f lux", maxLux)
        } ?: Timber.w("TYPE_LIGHT sensor not available -- Direct Sun Mode disabled")
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_LIGHT) return
        val lux = event.values[0]
        val wasDirectSun = _isDirectSunMode.value
        val isDirectSun = lux >= directSunThreshold

        if (wasDirectSun != isDirectSun) {
            _isDirectSunMode.value = isDirectSun
            Timber.d(
                "Direct Sun Mode %s (lux=%.0f, threshold=%.0f)",
                if (isDirectSun) "ON" else "OFF",
                lux,
                directSunThreshold,
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    override fun onCleared() {
        sensorManager.unregisterListener(this)
        super.onCleared()
    }
}
