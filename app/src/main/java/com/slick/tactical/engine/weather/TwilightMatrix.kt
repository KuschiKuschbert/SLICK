package com.slick.tactical.engine.weather

import com.slick.tactical.util.SlickConstants
import timber.log.Timber
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Elevates node hazard rating during civil twilight windows.
 *
 * Macropod (kangaroo/wallaby) activity peaks during dawn and dusk on Queensland highways.
 * This elevation is applied independently of weather conditions.
 *
 * Twilight window: [SlickConstants.TWILIGHT_WINDOW_MINUTES] minutes either side
 * of the sunrise/sunset time for the node's GPS coordinate.
 *
 * Data source: Open-Meteo `daily.sunrise` and `daily.sunset` fields.
 */
@Singleton
class TwilightMatrix @Inject constructor() {

    private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /**
     * Determines if a route node falls within a wildlife-active twilight window.
     *
     * @param nodeArrival24h Projected arrival time at the node in HH:mm (24h)
     * @param sunriseTime24h Sunrise time for the node's location in HH:mm (from Open-Meteo)
     * @param sunsetTime24h Sunset time for the node's location in HH:mm (from Open-Meteo)
     * @return Result containing true if twilight hazard applies, false if safe window
     */
    fun isTwilightHazard(
        nodeArrival24h: String,
        sunriseTime24h: String,
        sunsetTime24h: String,
    ): Result<Boolean> = try {
        val arrivalTime = LocalTime.parse(nodeArrival24h, timeFormatter)
        val sunrise = LocalTime.parse(sunriseTime24h, timeFormatter)
        val sunset = LocalTime.parse(sunsetTime24h, timeFormatter)

        val window = SlickConstants.TWILIGHT_WINDOW_MINUTES

        val isDawn = arrivalTime.isAfter(sunrise.minusMinutes(window)) &&
            arrivalTime.isBefore(sunrise.plusMinutes(window))

        val isDusk = arrivalTime.isAfter(sunset.minusMinutes(window)) &&
            arrivalTime.isBefore(sunset.plusMinutes(window))

        val isHazard = isDawn || isDusk

        if (isHazard) {
            Timber.d(
                "Twilight hazard at %s (dawn=%b, dusk=%b, sunrise=%s, sunset=%s)",
                nodeArrival24h,
                isDawn,
                isDusk,
                sunriseTime24h,
                sunsetTime24h,
            )
        }

        Result.success(isHazard)
    } catch (e: Exception) {
        Timber.e(e, "TwilightMatrix calculation failed for arrival=%s", nodeArrival24h)
        Result.failure(Exception("Twilight calculation failed: ${e.localizedMessage}", e))
    }
}
