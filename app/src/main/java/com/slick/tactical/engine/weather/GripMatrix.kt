package com.slick.tactical.engine.weather

import com.slick.tactical.data.local.entity.WeatherNodeEntity
import com.slick.tactical.util.SlickConstants
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sin

/**
 * Calculates the road surface danger profile for each route node.
 *
 * Primary outputs per node:
 * - Crosswind lateral force (km/h) -- from wind speed + bearing delta
 * - Asphalt Memory / residual wetness -- from 15-min precipitation history
 * - Overall danger classification (DRY / MODERATE / HIGH / EXTREME)
 *
 * These values drive the MapLibre GripMatrix gradient polyline colours:
 * DRY = grey, MODERATE/HIGH = cyan, EXTREME = orange/red.
 *
 * Data source: Open-Meteo API via [com.slick.tactical.data.remote.OpenMeteoClient].
 * Resolution: 1km grid -- labeled "Residual Risk Index" to the user, not "surface sensor".
 */
@Singleton
class GripMatrix @Inject constructor() {

    enum class DangerLevel {
        DRY,      // No precipitation, optimal conditions
        MODERATE, // Light rain or residual wetness
        HIGH,     // Active rain or strong crosswind
        EXTREME,  // Heavy rain, severe crosswind, or combined hazards
    }

    data class NodeDangerReport(
        val crosswindKmh: Double,
        val rainfallStatus: String,
        val gripStatus: String,
        val dangerLevel: DangerLevel,
        val residualWetness: Boolean,
    )

    /**
     * Calculates the full danger profile for a weather node.
     *
     * Uses the Asphalt Memory formula: if rain stopped but fell in the past 30 min,
     * the surface is still hazardous (oil-water emulsion at its most slippery).
     *
     * Crosswind formula: crosswindKmh = windSpeedKmh * |sin(windDir - routeBearing)|
     *
     * @param node [WeatherNodeEntity] with weather data populated by Open-Meteo sync
     * @return Result containing [NodeDangerReport], or failure if data is invalid
     */
    fun evaluateNode(node: WeatherNodeEntity): Result<NodeDangerReport> = try {
        // ── Crosswind ────────────────────────────────────────────────────────
        val angleDiffRad = Math.toRadians(abs(node.windDirDegrees - node.routeBearingDeg))
        val crosswindKmh = abs(node.windSpeedKmh * sin(angleDiffRad))

        // ── Asphalt Memory (residual wetness) ────────────────────────────────
        val pastRainMm = node.precipT30Mm + node.precipT15Mm
        val isCurrentlyRaining = node.precipNowMm > 0.0
        val residualWetness = !isCurrentlyRaining && pastRainMm > SlickConstants.ASPHALT_MEMORY_MIN_RAINFALL_MM

        val rainfallStatus = when {
            isCurrentlyRaining && node.precipNowMm > 5.0 -> "Heavy rain - Hydroplane risk elevated"
            isCurrentlyRaining && node.precipNowMm > 2.0 -> "Moderate rain - Reduced grip"
            isCurrentlyRaining -> "Light rain - Visor clearing required"
            residualWetness -> "Residual wetness - Oil slick probability elevated"
            else -> "Dry - Optimal grip"
        }

        // ── Grip Index (temperature + moisture) ──────────────────────────────
        val gripStatus = when {
            node.tempCelsius < 10.0 && isCurrentlyRaining -> "Critical - Cold wet asphalt"
            node.tempCelsius < 15.0 -> "Sub-optimal - Tyre operating temperature not reached"
            else -> "Optimal"
        }

        // ── Overall Danger Level ─────────────────────────────────────────────
        val dangerLevel = when {
            crosswindKmh > 35.0 || node.precipNowMm > 5.0 || node.visibilityKm < 1.0 -> DangerLevel.EXTREME
            crosswindKmh > 20.0 || node.precipNowMm > 2.0 || residualWetness -> DangerLevel.HIGH
            crosswindKmh > 15.0 || node.precipNowMm > 0.0 -> DangerLevel.MODERATE
            else -> DangerLevel.DRY
        }

        Timber.d(
            "Node %s: crosswind=%.1f km/h, %s, danger=%s",
            node.nodeId,
            crosswindKmh,
            rainfallStatus,
            dangerLevel,
        )

        Result.success(
            NodeDangerReport(
                crosswindKmh = crosswindKmh,
                rainfallStatus = rainfallStatus,
                gripStatus = gripStatus,
                dangerLevel = dangerLevel,
                residualWetness = residualWetness,
            ),
        )
    } catch (e: Exception) {
        Timber.e(e, "GripMatrix evaluation failed for node: %s", node.nodeId)
        Result.failure(Exception("GripMatrix failed at node ${node.nodeId}: ${e.localizedMessage}", e))
    }

    /**
     * Converts a [DangerLevel] to the MapLibre gradient colour value.
     * Used by the UI layer to construct the line-gradient expression.
     *
     * @return Hex colour string compatible with MapLibre's `color()` expression
     */
    fun dangerLevelToColor(dangerLevel: DangerLevel): String = when (dangerLevel) {
        DangerLevel.DRY -> "#9E9E9E"       // Grey
        DangerLevel.MODERATE -> "#00E5FF"  // Cyan (Wash)
        DangerLevel.HIGH -> "#FF9800"      // Amber
        DangerLevel.EXTREME -> "#FF5722"   // Alert orange
    }
}
