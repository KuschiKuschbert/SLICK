package com.slick.tactical.engine.weather

import com.slick.tactical.data.local.entity.WeatherNodeEntity
import com.slick.tactical.util.SlickConstants
import timber.log.Timber
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Slices a GPS polyline into 10km weather nodes with projected 24h arrival times.
 *
 * This is the primary GripMatrix engine entry point. It takes a raw polyline from
 * the Valhalla routing API and produces the array of [WeatherNodeEntity] stubs
 * that [GripMatrix] populates with Open-Meteo weather data.
 *
 * Distance calculation uses the Haversine formula (Earth radius 6371.0 km).
 * Arrival time is estimated from average speed -- no traffic data (Buffett Protocol: offline-first).
 */
@Singleton
class RouteForecaster @Inject constructor() {

    private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /**
     * Generates weather node stubs for a route polyline.
     *
     * Nodes are placed every [SlickConstants.NODE_INTERVAL_KM] km along the polyline.
     * The first node is always at the route start. The last node is always at the destination.
     *
     * @param polyline List of GPS coordinates forming the route
     * @param averageSpeedKmh Expected average speed in km/h (used for ETA calculation)
     * @param departureTime 24h departure time for ETA offset calculation
     * @return Result containing list of [WeatherNodeEntity] stubs (weather fields unpopulated),
     *         or failure with cause if polyline is invalid
     */
    fun generateNodes(
        polyline: List<Coordinate>,
        averageSpeedKmh: Double,
        departureTime: LocalTime,
    ): Result<List<WeatherNodeEntity>> = try {
        require(polyline.isNotEmpty()) { "Route polyline is empty" }
        require(averageSpeedKmh > 0.0) { "Speed must be positive: $averageSpeedKmh km/h" }
        require(polyline.size >= 2) { "Polyline must have at least 2 coordinates" }

        val nodes = mutableListOf<WeatherNodeEntity>()
        var accumulatedDistanceKm = 0.0
        var lastNodeDistanceKm = 0.0

        // Origin node
        nodes.add(createNodeStub(polyline.first(), 0.0, departureTime, polyline))

        for (i in 1 until polyline.size) {
            val segmentKm = haversineDistance(polyline[i - 1], polyline[i])
            accumulatedDistanceKm += segmentKm

            if (accumulatedDistanceKm - lastNodeDistanceKm >= SlickConstants.NODE_INTERVAL_KM) {
                val eta = calculateEta(departureTime, accumulatedDistanceKm, averageSpeedKmh)
                nodes.add(createNodeStub(polyline[i], accumulatedDistanceKm, eta, polyline))
                lastNodeDistanceKm = accumulatedDistanceKm
            }
        }

        // Destination node (always included)
        val totalEta = calculateEta(departureTime, accumulatedDistanceKm, averageSpeedKmh)
        nodes.add(createNodeStub(polyline.last(), accumulatedDistanceKm, totalEta, polyline))

        Timber.d("RouteForecaster: %d nodes sliced from %d polyline points (%.1f km total)", nodes.size, polyline.size, accumulatedDistanceKm)
        Result.success(nodes)
    } catch (e: IllegalArgumentException) {
        Result.failure(e)
    } catch (e: Exception) {
        Timber.e(e, "RouteForecaster failed")
        Result.failure(Exception("Node generation failed: ${e.localizedMessage}", e))
    }

    /**
     * Calculates great-circle distance between two GPS coordinates.
     *
     * Formula: Haversine with Earth radius = [SlickConstants.EARTH_RADIUS_KM] (6371.0 km).
     * Gives "as the crow flies" distance -- road distance is longer due to curves.
     *
     * @return Distance in kilometres
     */
    fun haversineDistance(from: Coordinate, to: Coordinate): Double {
        val dLat = Math.toRadians(to.lat - from.lat)
        val dLon = Math.toRadians(to.lon - from.lon)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(from.lat)) * cos(Math.toRadians(to.lat)) * sin(dLon / 2).pow(2)
        return SlickConstants.EARTH_RADIUS_KM * 2 * asin(sqrt(a))
    }

    /**
     * Calculates the route bearing (azimuth) from [from] to [to] in degrees (0-360).
     * Used for crosswind vector calculation in [GripMatrix].
     */
    fun calculateBearing(from: Coordinate, to: Coordinate): Double {
        val lat1 = Math.toRadians(from.lat)
        val lat2 = Math.toRadians(to.lat)
        val dLon = Math.toRadians(to.lon - from.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    private fun calculateEta(departure: LocalTime, distanceKm: Double, speedKmh: Double): LocalTime {
        val travelMinutes = (distanceKm / speedKmh * 60).toLong()
        return departure.plusMinutes(travelMinutes)
    }

    private fun createNodeStub(
        coord: Coordinate,
        distanceKm: Double,
        eta: LocalTime,
        polyline: List<Coordinate>,
    ): WeatherNodeEntity {
        val nodeId = "%.4f_%.4f".format(coord.lat, coord.lon)
        val bearing = if (polyline.size >= 2) {
            val idx = polyline.indexOfFirst { it.lat == coord.lat && it.lon == coord.lon }
            val nextIdx = (idx + 1).coerceAtMost(polyline.size - 1)
            if (idx != nextIdx) calculateBearing(polyline[idx], polyline[nextIdx]) else 0.0
        } else {
            0.0
        }

        return WeatherNodeEntity(
            nodeId = nodeId,
            latitude = coord.lat,
            longitude = coord.lon,
            estimatedArrival24h = eta.format(timeFormatter),
            windSpeedKmh = 0.0,        // Populated by GripMatrix
            windDirDegrees = 0.0,
            routeBearingDeg = bearing,
            precipT30Mm = 0.0,
            precipT15Mm = 0.0,
            precipNowMm = 0.0,
            tempCelsius = 0.0,
            visibilityKm = 10.0,
            isTwilightHazard = false,   // Populated by TwilightMatrix
            isSolarGlareRisk = false,   // Populated by SolarGlareVector
            lastUpdated24h = "00:00",
        )
    }
}

/**
 * A GPS coordinate pair.
 *
 * @property lat Latitude in decimal degrees (negative = south)
 * @property lon Longitude in decimal degrees (positive = east)
 */
data class Coordinate(val lat: Double, val lon: Double)
