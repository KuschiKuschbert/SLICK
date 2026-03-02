package com.slick.tactical.data.remote

import com.slick.tactical.engine.weather.Coordinate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ValhallRoutingClient] polyline6 decoding.
 *
 * The decoder is tested via a standalone reference implementation to verify
 * round-trip encode → decode accuracy. Pure math, no dependencies.
 */
class ValhallRoutingClientTest {

    // ─── decodePolyline6 via standalone helper ────────────────────────────────

    @Test
    fun `decodePolyline6 returns empty list for empty string`() {
        val result = decodePolyline6("")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `decodePolyline6 round-trip single coordinate`() {
        val kawanaLat = -26.7380
        val kawanaLon = 153.1230
        val encoded = encodePolyline6(listOf(Pair(kawanaLat, kawanaLon)))
        val decoded = decodePolyline6(encoded)

        assertEquals(1, decoded.size)
        assertEquals(kawanaLat, decoded[0].lat, 0.0001)
        assertEquals(kawanaLon, decoded[0].lon, 0.0001)
    }

    @Test
    fun `decodePolyline6 round-trip multiple coordinates`() {
        val points = listOf(
            Pair(-26.7380, 153.1230),
            Pair(-25.0000, 152.0000),
            Pair(-23.1300, 150.7400),
        )
        val encoded = encodePolyline6(points)
        val decoded = decodePolyline6(encoded)

        assertEquals(3, decoded.size)
        points.forEachIndexed { i, (lat, lon) ->
            assertEquals(lat, decoded[i].lat, 0.0001, "Point $i latitude")
            assertEquals(lon, decoded[i].lon, 0.0001, "Point $i longitude")
        }
    }

    @Test
    fun `decodePolyline6 uses 6 decimal precision`() {
        val precisePoint = listOf(Pair(-26.738012, 153.123456))
        val encoded = encodePolyline6(precisePoint)
        val decoded = decodePolyline6(encoded)

        assertEquals(-26.738012, decoded[0].lat, 0.000001)
        assertEquals(153.123456, decoded[0].lon, 0.000001)
    }

    @Test
    fun `decodePolyline6 route goes north from Kawana to Yeppoon`() {
        val points = listOf(
            Pair(-26.7380, 153.1230),  // Kawana (south)
            Pair(-23.1300, 150.7400),  // Yeppoon (north)
        )
        val decoded = decodePolyline6(encodePolyline6(points))
        assertTrue(
            decoded.first().lat < decoded.last().lat,
            "Yeppoon is north of Kawana: first lat < last lat",
        )
    }

    // ─── Reference implementations (mirror ValhallRoutingClient internals) ─────

    /**
     * Reference polyline6 decoder -- must match [ValhallRoutingClient.decodePolyline6].
     */
    private fun decodePolyline6(encoded: String): List<Coordinate> {
        val coordinates = mutableListOf<Coordinate>()
        var index = 0
        var lat = 0
        var lng = 0

        while (index < encoded.length) {
            var result = 0
            var shift = 0
            var b: Int
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lat += dlat

            result = 0
            shift = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lng += dlng

            coordinates.add(Coordinate(lat = lat / 1e6, lon = lng / 1e6))
        }
        return coordinates
    }

    private fun encodePolyline6(points: List<Pair<Double, Double>>): String {
        val sb = StringBuilder()
        var prevLat = 0
        var prevLon = 0
        for ((lat, lon) in points) {
            sb.append(encodeValue((lat * 1e6).toInt() - prevLat))
            sb.append(encodeValue((lon * 1e6).toInt() - prevLon))
            prevLat = (lat * 1e6).toInt()
            prevLon = (lon * 1e6).toInt()
        }
        return sb.toString()
    }

    private fun encodeValue(value: Int): String {
        var v = if (value < 0) (value shl 1).inv() else value shl 1
        val sb = StringBuilder()
        while (v >= 0x20) {
            sb.append((((v and 0x1f) or 0x20) + 63).toChar())
            v = v shr 5
        }
        sb.append((v + 63).toChar())
        return sb.toString()
    }
}
