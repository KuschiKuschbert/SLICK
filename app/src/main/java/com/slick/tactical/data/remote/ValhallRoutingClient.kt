package com.slick.tactical.data.remote

import com.slick.tactical.BuildConfig
import com.slick.tactical.engine.navigation.RouteManeuver
import com.slick.tactical.engine.navigation.RouteResult
import com.slick.tactical.engine.weather.Coordinate
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches a motorcycle route from the Valhalla open-source routing engine.
 *
 * Endpoint: [BuildConfig.VALHALLA_BASE_URL]/route
 * Costing profile: "motorcycle" (respects road types appropriate for bikes)
 *
 * Returns [RouteResult] containing:
 * - Full road-following polyline (decoded polyline6, ~1 point per 20m)
 * - Turn-by-turn maneuvers with shape indices for navigation tracking
 * - Total distance and estimated travel time
 */
@Singleton
class ValhallRoutingClient @Inject constructor(
    private val httpClient: HttpClient,
) {

    /**
     * Fetches a complete route including road-following polyline and turn-by-turn maneuvers.
     *
     * @param origin Start coordinate (e.g., Kawana)
     * @param destination End coordinate (e.g., Yeppoon)
     * @param waypoint Optional intermediate stop for detour ETA calculation
     * @return Result containing [RouteResult] with polyline + maneuvers, or failure
     */
    suspend fun fetchRoute(
        origin: Coordinate,
        destination: Coordinate,
        waypoint: Coordinate? = null,
    ): Result<RouteResult> {
        return try {
            val requestBody = buildValhallRequest(origin, destination, waypoint)
            val response = httpClient.post("${BuildConfig.VALHALLA_BASE_URL}/route") {
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }
            val routeResponse = response.body<ValhallRouteResponse>()
            val legs = routeResponse.trip.legs
            if (legs.isEmpty()) return Result.failure(Exception("Valhalla returned empty route legs"))

            var shapeIndexOffset = 0
            val allCoordinates = mutableListOf<Coordinate>()
            val allManeuvers = mutableListOf<RouteManeuver>()

            for (leg in legs) {
                val legPoints = decodePolyline6(leg.shape)
                allCoordinates.addAll(legPoints)
                for (m in leg.maneuvers) {
                    allManeuvers.add(RouteManeuver(
                        type = m.type,
                        instruction = m.instruction,
                        distanceKm = m.length,
                        timeSeconds = m.time,
                        beginShapeIndex = m.beginShapeIndex + shapeIndexOffset,
                    ))
                }
                shapeIndexOffset += legPoints.size
            }

            if (allCoordinates.isEmpty()) return Result.failure(Exception("Decoded polyline is empty"))

            Timber.i("Valhalla route: %d pts, %d maneuvers, %.1f km, %.0f min",
                allCoordinates.size, allManeuvers.size,
                routeResponse.trip.summary.length, routeResponse.trip.summary.time / 60.0)

            Result.success(RouteResult(
                polyline = allCoordinates,
                maneuvers = allManeuvers,
                totalDistanceKm = routeResponse.trip.summary.length,
                totalTimeSeconds = routeResponse.trip.summary.time,
            ))
        } catch (e: Exception) {
            Timber.w(e, "Valhalla routing failed -- will use straight-line fallback")
            Result.failure(Exception("Route fetch failed: ${e.localizedMessage}", e))
        }
    }

    /**
     * Convenience overload that returns only the polyline coordinates.
     * Used by [DetourManager] for ETA impact calculation.
     */
    suspend fun fetchRoutePolyline(
        origin: Coordinate,
        destination: Coordinate,
        waypoint: Coordinate? = null,
    ): Result<List<Coordinate>> = fetchRoute(origin, destination, waypoint)
        .map { it.polyline }

    private fun buildValhallRequest(
        origin: Coordinate,
        destination: Coordinate,
        waypoint: Coordinate? = null,
    ): String {
        val locations = buildList {
            add(ValhallLocation(lon = origin.lon, lat = origin.lat, type = "break"))
            if (waypoint != null) {
                add(ValhallLocation(lon = waypoint.lon, lat = waypoint.lat, type = "through"))
            }
            add(ValhallLocation(lon = destination.lon, lat = destination.lat, type = "break"))
        }
        return Json.encodeToString(
            ValhallRouteRequest.serializer(),
            ValhallRouteRequest(
                locations = locations,
                costing = "motorcycle",
                directionsOptions = DirectionsOptions(units = "kilometers"),
            ),
        )
    }

    /**
     * Decodes a Valhalla polyline6 encoded string into GPS coordinates.
     * Polyline6 uses 6 decimal places (10cm precision) vs Google's polyline5 (1m precision).
     */
    fun decodePolyline6(encoded: String): List<Coordinate> {
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
}

// ─── Valhalla Request / Response models ──────────────────────────────────────

@Serializable
data class ValhallRouteRequest(
    val locations: List<ValhallLocation>,
    val costing: String,
    @SerialName("directions_options") val directionsOptions: DirectionsOptions,
)

@Serializable
data class ValhallLocation(
    val lon: Double,
    val lat: Double,
    val type: String,
)

@Serializable
data class DirectionsOptions(
    val units: String = "kilometers",
)

@Serializable
data class ValhallRouteResponse(
    val trip: ValhallTrip,
)

@Serializable
data class ValhallTrip(
    val legs: List<ValhallLeg>,
    val summary: ValhallSummary,
)

@Serializable
data class ValhallLeg(
    val shape: String,
    val summary: ValhallSummary,
    val maneuvers: List<ValhallManeuver> = emptyList(),
)

@Serializable
data class ValhallManeuver(
    val type: Int = 0,
    val instruction: String = "",
    val length: Double = 0.0,        // km to next maneuver
    val time: Int = 0,               // seconds to next maneuver
    @SerialName("begin_shape_index") val beginShapeIndex: Int = 0,
    @SerialName("end_shape_index") val endShapeIndex: Int = 0,
    @SerialName("street_names") val streetNames: List<String> = emptyList(),
)

@Serializable
data class ValhallSummary(
    val length: Double = 0.0,  // Distance in km
    val time: Double = 0.0,    // Duration in seconds
)
