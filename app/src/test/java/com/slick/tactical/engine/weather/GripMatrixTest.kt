package com.slick.tactical.engine.weather

import com.slick.tactical.data.local.entity.WeatherNodeEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [GripMatrix].
 * Critical path: 100% coverage required.
 */
class GripMatrixTest {

    private lateinit var gripMatrix: GripMatrix

    @BeforeEach
    fun setup() {
        gripMatrix = GripMatrix()
    }

    private fun makeNode(
        windSpeedKmh: Double = 0.0,
        windDirDegrees: Double = 0.0,
        routeBearingDeg: Double = 0.0,
        precipT30Mm: Double = 0.0,
        precipT15Mm: Double = 0.0,
        precipNowMm: Double = 0.0,
        tempCelsius: Double = 22.0,
        visibilityKm: Double = 10.0,
    ): WeatherNodeEntity = WeatherNodeEntity(
        nodeId = "test_node",
        latitude = -26.73,
        longitude = 153.12,
        estimatedArrival24h = "14:00",
        windSpeedKmh = windSpeedKmh,
        windDirDegrees = windDirDegrees,
        routeBearingDeg = routeBearingDeg,
        precipT30Mm = precipT30Mm,
        precipT15Mm = precipT15Mm,
        precipNowMm = precipNowMm,
        tempCelsius = tempCelsius,
        visibilityKm = visibilityKm,
        isTwilightHazard = false,
        isSolarGlareRisk = false,
        lastUpdated24h = "14:00",
    )

    // ─── Dry conditions ──────────────────────────────────────────────────────

    @Test
    fun `dry conditions with no wind returns DRY danger level`() {
        val node = makeNode(windSpeedKmh = 5.0, precipNowMm = 0.0)
        val result = gripMatrix.evaluateNode(node)

        assertTrue(result.isSuccess)
        val report = result.getOrThrow()
        assertEquals(GripMatrix.DangerLevel.DRY, report.dangerLevel)
        assertFalse(report.residualWetness)
    }

    // ─── Asphalt Memory (residual wetness) ───────────────────────────────────

    @Test
    fun `rain stopped 30 min ago triggers residual wetness`() {
        val node = makeNode(
            precipT30Mm = 2.0,  // Rained heavily 30 min ago
            precipT15Mm = 0.5,  // Still raining 15 min ago
            precipNowMm = 0.0,  // Currently dry
        )
        val result = gripMatrix.evaluateNode(node)

        assertTrue(result.isSuccess)
        val report = result.getOrThrow()
        assertTrue(report.residualWetness, "Expected residual wetness to be flagged")
        assertEquals(GripMatrix.DangerLevel.HIGH, report.dangerLevel)
    }

    @Test
    fun `very light historical rain below threshold does not trigger residual wetness`() {
        val node = makeNode(
            precipT30Mm = 0.1,  // Less than ASPHALT_MEMORY_MIN_RAINFALL_MM (0.2mm)
            precipT15Mm = 0.05,
            precipNowMm = 0.0,
        )
        val result = gripMatrix.evaluateNode(node)

        assertTrue(result.isSuccess)
        assertFalse(result.getOrThrow().residualWetness)
    }

    // ─── Crosswind calculations ───────────────────────────────────────────────

    @Test
    fun `perpendicular crosswind at full speed returns maximum lateral force`() {
        // Wind from east (90°), riding north (0°) = pure crosswind
        val node = makeNode(
            windSpeedKmh = 40.0,
            windDirDegrees = 90.0,
            routeBearingDeg = 0.0,
        )
        val result = gripMatrix.evaluateNode(node)

        assertTrue(result.isSuccess)
        val report = result.getOrThrow()
        // sin(90°) = 1.0, so crosswind = 40 * 1 = 40 km/h
        assertTrue(report.crosswindKmh > 35.0, "Expected EXTREME crosswind (>35 km/h), got ${report.crosswindKmh}")
        assertEquals(GripMatrix.DangerLevel.EXTREME, report.dangerLevel)
    }

    @Test
    fun `headwind has zero crosswind component`() {
        // Wind from north (0°), riding north (0°) = pure headwind, no lateral force
        val node = makeNode(
            windSpeedKmh = 50.0,
            windDirDegrees = 0.0,
            routeBearingDeg = 0.0,
        )
        val result = gripMatrix.evaluateNode(node)

        assertTrue(result.isSuccess)
        val report = result.getOrThrow()
        // sin(0°) = 0.0, so crosswind = 50 * 0 = 0 km/h
        assertTrue(report.crosswindKmh < 1.0, "Expected ~0 crosswind for headwind, got ${report.crosswindKmh}")
        assertEquals(GripMatrix.DangerLevel.DRY, report.dangerLevel)
    }

    // ─── Heavy rain ──────────────────────────────────────────────────────────

    @Test
    fun `heavy rain over 5mm returns EXTREME danger`() {
        val node = makeNode(precipNowMm = 6.0)
        val result = gripMatrix.evaluateNode(node)

        assertTrue(result.isSuccess)
        assertEquals(GripMatrix.DangerLevel.EXTREME, result.getOrThrow().dangerLevel)
    }

    // ─── Colour mapping ───────────────────────────────────────────────────────

    @Test
    fun `DRY danger maps to grey colour`() {
        assertEquals("#9E9E9E", gripMatrix.dangerLevelToColor(GripMatrix.DangerLevel.DRY))
    }

    @Test
    fun `EXTREME danger maps to alert orange`() {
        assertEquals("#FF5722", gripMatrix.dangerLevelToColor(GripMatrix.DangerLevel.EXTREME))
    }

    // ─── Metric units validation ──────────────────────────────────────────────

    @Test
    fun `crosswind value is always in km per hour -- never negative`() {
        val node = makeNode(windSpeedKmh = 30.0, windDirDegrees = 270.0, routeBearingDeg = 360.0)
        val result = gripMatrix.evaluateNode(node)

        assertTrue(result.isSuccess)
        val crosswind = result.getOrThrow().crosswindKmh
        assertTrue(crosswind >= 0.0, "Crosswind must not be negative (km/h): $crosswind")
    }
}
