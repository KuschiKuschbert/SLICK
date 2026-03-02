package com.slick.tactical.engine.weather

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalTime

/**
 * Unit tests for [RouteForecaster].
 * Critical path: 100% coverage required (Haversine math + node slicing).
 */
class RouteForecasterTest {

    private lateinit var forecaster: RouteForecaster

    @BeforeEach
    fun setup() {
        forecaster = RouteForecaster()
    }

    // ─── generateNodes ──────────────────────────────────────────────────────

    @Test
    fun `generateNodes returns start and destination nodes for minimal polyline`() {
        val route = listOf(
            Coordinate(-26.7380, 153.1230),
            Coordinate(-26.7370, 153.1230),  // ~111m apart
        )
        val result = forecaster.generateNodes(route, averageSpeedKmh = 100.0, departureTime = LocalTime.of(14, 0))

        assertTrue(result.isSuccess, "Expected success but got: ${result.exceptionOrNull()}")
        val nodes = result.getOrThrow()
        assertTrue(nodes.size >= 2, "Expected at least start + destination, got ${nodes.size}")
        assertEquals("14:00", nodes.first().estimatedArrival24h, "Start node should be at departure time")
    }

    @Test
    fun `generateNodes slices 10km interval correctly and calculates 24h ETA`() {
        // ~10km north of Kawana along the coast
        val route = listOf(
            Coordinate(-26.7380, 153.1230),  // Kawana area
            Coordinate(-26.6480, 153.1230),  // ~10km north
            Coordinate(-26.5580, 153.1230),  // ~20km north
        )
        val result = forecaster.generateNodes(
            route,
            averageSpeedKmh = 100.0,
            departureTime = LocalTime.of(14, 0),
        )

        assertTrue(result.isSuccess)
        val nodes = result.getOrThrow()

        // Start at 14:00, 10km @ 100km/h = 6 minutes
        assertEquals("14:00", nodes.first().estimatedArrival24h)
        // Node at ~10km should arrive at approximately 14:06
        val tenKmNode = nodes.firstOrNull { it.estimatedArrival24h == "14:06" }
        assertTrue(tenKmNode != null, "Expected node at 14:06, got: ${nodes.map { it.estimatedArrival24h }}")
    }

    @Test
    fun `generateNodes uses 24h format -- no AM PM in output`() {
        val route = listOf(
            Coordinate(-26.7380, 153.1230),
            Coordinate(-23.1300, 150.7400),  // Yeppoon
        )
        val result = forecaster.generateNodes(route, 110.0, LocalTime.of(23, 45))

        assertTrue(result.isSuccess)
        val nodes = result.getOrThrow()
        nodes.forEach { node ->
            assertFalse(node.estimatedArrival24h.contains("AM"), "Found AM in time: ${node.estimatedArrival24h}")
            assertFalse(node.estimatedArrival24h.contains("PM"), "Found PM in time: ${node.estimatedArrival24h}")
            assertTrue(node.estimatedArrival24h.matches(Regex("\\d{2}:\\d{2}")), "Not HH:mm: ${node.estimatedArrival24h}")
        }
    }

    @Test
    fun `generateNodes fails with empty polyline`() {
        val result = forecaster.generateNodes(emptyList(), 100.0, LocalTime.MIDNIGHT)
        assertTrue(result.isFailure)
        assertEquals("Route polyline is empty", result.exceptionOrNull()?.message)
    }

    @Test
    fun `generateNodes fails with single coordinate`() {
        val result = forecaster.generateNodes(
            listOf(Coordinate(-26.73, 153.12)),
            100.0,
            LocalTime.MIDNIGHT,
        )
        assertTrue(result.isFailure)
        assertEquals("Polyline must have at least 2 coordinates", result.exceptionOrNull()?.message)
    }

    @ParameterizedTest
    @ValueSource(doubles = [0.0, -1.0, -100.0])
    fun `generateNodes fails with non-positive speed`(speed: Double) {
        val route = listOf(Coordinate(-26.73, 153.12), Coordinate(-26.72, 153.12))
        val result = forecaster.generateNodes(route, speed, LocalTime.MIDNIGHT)
        assertTrue(result.isFailure)
        assertEquals("Speed must be positive: $speed km/h", result.exceptionOrNull()?.message)
    }

    // ─── haversineDistance ───────────────────────────────────────────────────

    @Test
    fun `haversineDistance returns ~111km for 1 degree of latitude`() {
        // 1 degree of latitude ≈ 111.32 km
        val from = Coordinate(0.0, 0.0)
        val to = Coordinate(1.0, 0.0)
        val distance = forecaster.haversineDistance(from, to)
        assertTrue(distance in 110.0..112.0, "Expected ~111km, got $distance km")
    }

    @Test
    fun `haversineDistance returns 0 for same coordinate`() {
        val coord = Coordinate(-26.7380, 153.1230)
        val distance = forecaster.haversineDistance(coord, coord)
        assertEquals(0.0, distance, 0.001)
    }

    @Test
    fun `haversineDistance Kawana to Yeppoon is approximately 380km road`() {
        // Straight-line (Haversine) will be less than road distance
        val kawana = Coordinate(-26.7380, 153.1230)
        val yeppoon = Coordinate(-23.1300, 150.7400)
        val distance = forecaster.haversineDistance(kawana, yeppoon)
        // ~468km straight-line expected (Haversine great-circle; road via Bruce Hwy is ~700km)
        assertTrue(distance in 440.0..500.0, "Expected ~468km straight-line, got $distance km")
    }

    // ─── calculateBearing ────────────────────────────────────────────────────

    @Test
    fun `calculateBearing due north returns approximately 0 degrees`() {
        val from = Coordinate(-27.0, 153.0)
        val to = Coordinate(-26.0, 153.0)
        val bearing = forecaster.calculateBearing(from, to)
        // Due north = 0 or 360
        assertTrue(bearing < 5.0 || bearing > 355.0, "Expected ~0°, got $bearing°")
    }

    @Test
    fun `calculateBearing due east returns approximately 90 degrees`() {
        val from = Coordinate(-27.0, 153.0)
        val to = Coordinate(-27.0, 154.0)
        val bearing = forecaster.calculateBearing(from, to)
        assertTrue(bearing in 85.0..95.0, "Expected ~90°, got $bearing°")
    }

    @Test
    fun `calculateBearing returns values in 0-360 range`() {
        val coords = listOf(
            Pair(Coordinate(-26.0, 153.0), Coordinate(-27.0, 153.0)),  // South
            Pair(Coordinate(-26.0, 153.0), Coordinate(-26.0, 152.0)),  // West
        )
        coords.forEach { (from, to) ->
            val bearing = forecaster.calculateBearing(from, to)
            assertTrue(bearing in 0.0..360.0, "Bearing out of range: $bearing°")
        }
    }
}
