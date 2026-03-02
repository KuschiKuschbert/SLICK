package com.slick.tactical.engine.weather

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [TwilightMatrix].
 * Macropod hazard window: ±30 minutes of sunrise/sunset.
 */
class TwilightMatrixTest {

    private lateinit var twilightMatrix: TwilightMatrix

    @BeforeEach
    fun setup() {
        twilightMatrix = TwilightMatrix()
    }

    @Test
    fun `arrival at sunrise time triggers twilight hazard`() {
        val result = twilightMatrix.isTwilightHazard(
            nodeArrival24h = "06:12",
            sunriseTime24h = "06:12",
            sunsetTime24h = "18:45",
        )
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow(), "Arrival exactly at sunrise should be a hazard")
    }

    @Test
    fun `arrival 25 minutes after sunset triggers twilight hazard`() {
        val result = twilightMatrix.isTwilightHazard(
            nodeArrival24h = "19:10",
            sunriseTime24h = "06:12",
            sunsetTime24h = "18:45",
        )
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow(), "Arrival 25min after sunset should be a hazard (within 30min window)")
    }

    @Test
    fun `arrival at midday is not a twilight hazard`() {
        val result = twilightMatrix.isTwilightHazard(
            nodeArrival24h = "12:00",
            sunriseTime24h = "06:12",
            sunsetTime24h = "18:45",
        )
        assertTrue(result.isSuccess)
        assertFalse(result.getOrThrow(), "Midday should not be a twilight hazard")
    }

    @Test
    fun `arrival 45 minutes after sunset is outside twilight window`() {
        val result = twilightMatrix.isTwilightHazard(
            nodeArrival24h = "19:30",
            sunriseTime24h = "06:12",
            sunsetTime24h = "18:45",  // 45 min later = outside 30-min window
        )
        assertTrue(result.isSuccess)
        assertFalse(result.getOrThrow(), "45min after sunset should be outside twilight hazard window")
    }

    @Test
    fun `uses 24h format -- not 12h`() {
        // Sunrise at 06:00 in 24h -- ensure we don't confuse 06:00 with 18:00
        val result = twilightMatrix.isTwilightHazard(
            nodeArrival24h = "06:05",
            sunriseTime24h = "06:00",
            sunsetTime24h = "18:30",
        )
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow(), "06:05 is 5 min after 06:00 sunrise -- should be hazard")
    }

    @Test
    fun `invalid time format returns failure`() {
        val result = twilightMatrix.isTwilightHazard(
            nodeArrival24h = "2:30 PM",  // Invalid -- AM/PM banned
            sunriseTime24h = "06:00",
            sunsetTime24h = "18:30",
        )
        assertTrue(result.isFailure)
    }
}
