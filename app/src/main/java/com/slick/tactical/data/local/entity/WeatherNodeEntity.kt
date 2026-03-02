package com.slick.tactical.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached GripMatrix weather node for a route segment.
 *
 * Sliced from the route polyline at [com.slick.tactical.util.SlickConstants.NODE_INTERVAL_KM] intervals.
 * Populated by [com.slick.tactical.engine.weather.RouteForecaster] from Open-Meteo API.
 *
 * @property nodeId Unique identifier: "{lat}_{lon}" at 4 decimal places
 * @property estimatedArrival24h Projected arrival time in HH:mm (24h) based on route + speed
 * @property windSpeedKmh Wind speed in km/h at 10m height (Open-Meteo: windspeed_10m)
 * @property windDirDegrees Wind direction in degrees (0-360) (Open-Meteo: winddirection_10m)
 * @property routeBearingDeg Route bearing at this node in degrees (0-360)
 * @property precipT30Mm Precipitation 30 minutes ago in mm (Asphalt Memory T-30)
 * @property precipT15Mm Precipitation 15 minutes ago in mm (Asphalt Memory T-15)
 * @property precipNowMm Current precipitation in mm
 * @property tempCelsius Ambient temperature in Celsius
 * @property visibilityKm Visibility in kilometres
 * @property isTwilightHazard True if node arrival falls within twilight window (macropod risk)
 * @property isSolarGlareRisk True if sun azimuth aligns with route bearing at low elevation
 * @property lastUpdated24h Timestamp of last API refresh in HH:mm
 */
@Entity(tableName = "weather_nodes")
data class WeatherNodeEntity(
    @PrimaryKey val nodeId: String,
    val latitude: Double,
    val longitude: Double,
    val estimatedArrival24h: String,
    val windSpeedKmh: Double,
    val windDirDegrees: Double,
    val routeBearingDeg: Double,
    val precipT30Mm: Double,
    val precipT15Mm: Double,
    val precipNowMm: Double,
    val tempCelsius: Double,
    val visibilityKm: Double,
    val isTwilightHazard: Boolean,
    val isSolarGlareRisk: Boolean,
    val lastUpdated24h: String,
)
