package com.slick.tactical.data.remote

import com.slick.tactical.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches weather data from the Open-Meteo API for GripMatrix node population.
 *
 * Key parameters:
 * - `models=bom_access_global` -- Australian Bureau of Meteorology model (mandatory for QLD accuracy)
 * - `timezone=Australia/Brisbane` -- required for correct 24h timestamps
 * - `past_minutely_15=2` -- returns T-30min and T-15min precipitation (Asphalt Memory)
 * - `forecast_minutely_15=1` -- returns current precipitation
 *
 * IMPORTANT: [WeatherResponse.minutely15] array indexing:
 * - Index 0 = T-30min (oldest)
 * - Index 1 = T-15min
 * - Index 2 = T-0 (current, from forecast_minutely_15)
 *
 * See [slick-weather-engine](.cursor/skills/slick-weather-engine/SKILL.md) for full API reference.
 */
@Singleton
class OpenMeteoClient @Inject constructor(
    private val httpClient: HttpClient,
) {

    /**
     * Fetches comprehensive weather data for a single route node.
     *
     * @param latitude GPS latitude in decimal degrees
     * @param longitude GPS longitude in decimal degrees
     * @return Result containing [WeatherResponse], or failure with cause
     */
    suspend fun fetchNodeWeather(latitude: Double, longitude: Double): Result<WeatherResponse> = try {
        val response = httpClient.get("${BuildConfig.OPEN_METEO_BASE_URL}/v1/forecast") {
            parameter("latitude", latitude)
            parameter("longitude", longitude)
            // Hourly variables (snake_case as returned by Open-Meteo API v1)
            parameter("hourly", "temperature_2m,windspeed_10m,winddirection_10m,visibility")
            // Minutely 15: precipitation history for Asphalt Memory calculation
            // past_minutely_15=2 → T-30min (idx 0) and T-15min (idx 1)
            // forecast_minutely_15=1 → T-0 current (idx 2)
            parameter("minutely_15", "precipitation")
            parameter("past_minutely_15", 2)
            parameter("forecast_minutely_15", 1)
            parameter("daily", "sunrise,sunset")
            parameter("timezone", "Australia/Brisbane")
            // No models= parameter: Open-Meteo automatically selects the best model per location
            // (BOM ACCESS-G for Australia, ECMWF for elsewhere). Explicitly setting bom_access_global
            // can cause failures because that model does not support all minutely_15 variables.
        }
        val body = response.body<WeatherResponse>()
        Timber.d("Open-Meteo fetch success at (%.4f, %.4f)", latitude, longitude)
        Result.success(body)
    } catch (e: Exception) {
        Timber.e(e, "Open-Meteo fetch failed at (%.4f, %.4f)", latitude, longitude)
        Result.failure(Exception("Weather API failed at ($latitude,$longitude): ${e.localizedMessage}", e))
    }
}

// ─── Open-Meteo Response Data Models ─────────────────────────────────────────

@Serializable
data class WeatherResponse(
    val latitude: Double,
    val longitude: Double,
    val timezone: String,
    val hourly: HourlyData,
    @SerialName("minutely_15") val minutely15: MinutelyData,
    val daily: DailyData,
)

@Serializable
data class HourlyData(
    val time: List<String>,                                              // ISO 8601 timestamps
    @SerialName("temperature_2m") val temperature2m: List<Double>,      // °C
    @SerialName("windspeed_10m") val windspeed10m: List<Double>,        // km/h
    @SerialName("winddirection_10m") val winddirection10m: List<Double>,// degrees (0-360)
    val visibility: List<Double>,                                        // metres
    // Note: hourly precipitation removed -- we use minutely_15 for Asphalt Memory instead
)

@Serializable
data class MinutelyData(
    val time: List<String>,
    val precipitation: List<Double>, // mm
    // Index 0 = T-30min, Index 1 = T-15min, Index 2 = T-0 (current)
)

@Serializable
data class DailyData(
    val time: List<String>,
    val sunrise: List<String>,  // ISO 8601 datetime
    val sunset: List<String>,   // ISO 8601 datetime
)
