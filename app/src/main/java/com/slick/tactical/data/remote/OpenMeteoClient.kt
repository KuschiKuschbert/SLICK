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
            parameter("hourly", "temperature_2m,precipitation,windspeed_10m,winddirection_10m,visibility")
            parameter("minutely_15", "precipitation")
            parameter("past_minutely_15", 2)         // T-30min and T-15min
            parameter("forecast_minutely_15", 1)     // T-0 current
            parameter("daily", "sunrise,sunset")
            parameter("timezone", "Australia/Brisbane")
            parameter("models", "bom_access_global") // BOM model mandatory for QLD
            // Open-Meteo free tier needs no API key.
            // If a commercial key is added in future, inject it here via BuildConfig.
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
    val time: List<String>,                         // ISO 8601 timestamps
    @SerialName("temperature_2m") val temperature2m: List<Double>,
    val precipitation: List<Double>,                // mm
    @SerialName("windspeed_10m") val windspeed10m: List<Double>,     // km/h
    @SerialName("winddirection_10m") val winddirection10m: List<Double>, // degrees (0-360)
    val visibility: List<Double>,                   // metres
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
