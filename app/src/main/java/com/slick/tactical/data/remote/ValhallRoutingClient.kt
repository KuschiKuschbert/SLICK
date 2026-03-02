package com.slick.tactical.data.remote

import com.slick.tactical.BuildConfig
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
 * Fetches a motorcycle route polyline from the Valhalla open-source routing engine.
 *
 * Endpoint: [BuildConfig.VALHALLA_BASE_URL]/route
 * Costing profile: "motorcycle" (respects road types appropriate for bikes)
 *
 * The returned polyline is a list of GPS [Coordinate] objects sliced by
 * [com.slick.tactical.engine.weather.RouteForecaster] into 10km GripMatrix nodes.
 *
 * Valhalla returns routes as an encoded polyline6 string. We decode it here
 * and return raw [Coordinate] objects -- no encoding dependency needed.
 */
@Singleton
class ValhallRoutingClient @Inject constructor(
    private val httpClient: HttpClient,
) {

    /**
     * Fetches a route polyline between [origin] and [destination], with an optional [waypoint].
     *
     * @param origin Start coordinate
     * @param destination End coordinate
     * @param waypoint Optional intermediate stop (via point)
     * @return Result containing ordered list of [Coordinate] points
     */
    suspend fun fetchRoute(
        origin: Coordinate,
        destination: Coordinate,
        waypoint: Coordinate? = null,
    ): Result<List<Coordinate>> {
        return try {
            val requestBody = buildValhallRequest(origin, destination, waypoint)

            val response = httpClient.post("${BuildConfig.VALHALLA_BASE_URL}/route") {
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }

            val routeResponse = response.body<ValhallRouteResponse>()

            if (routeResponse.trip.legs.isEmpty()) {
                return Result.failure(Exception("Valhalla returned empty route legs"))
            }

            // Combine all leg shapes for multi-leg routes (when waypoint is provided)
            val allCoordinates = routeResponse.trip.legs.flatMap { leg ->
                decodePolyline6(leg.shape)
            }

            if (allCoordinates.isEmpty()) {
                return Result.failure(Exception("Decoded polyline is empty"))
            }

            Timber.d("Valhalla route (waypoint=%s): %d points, %.1f km",
                waypoint?.let { "(%.4f,%.4f)".format(it.lat, it.lon) } ?: "none",
                allCoordinates.size,
                routeResponse.trip.summary.length)

            Result.success(allCoordinates)
        } catch (e: Exception) {
            Timber.e(e, "Valhalla routing failed (waypoint)")
            Result.failure(Exception("Route fetch failed: ${e.localizedMessage}", e))
        }
    }

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
     *
     * Valhalla uses polyline6 encoding (6 decimal places = ~10cm precision).
     * Standard Google polyline5 uses 5 decimal places (~1m precision).
     * The precision multiplier for polyline6 is 1e6 instead of 1e5.
     *
     * @param encoded Valhalla polyline6 encoded string
     * @return List of [Coordinate] objects in route order
     */
    fun decodePolyline6(encoded: String): List<Coordinate> {
        val coordinates = mutableListOf<Coordinate>()
        var index = 0
        var lat = 0
        var lng = 0

        while (index < encoded.length) {
            // Decode latitude
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

            // Decode longitude
            result = 0
            shift = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lng += dlng

            // Polyline6: divide by 1e6 for 6 decimal places
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
    val shape: String,  // Polyline6 encoded string
    val summary: ValhallSummary,
)

@Serializable
data class ValhallSummary(
    val length: Double = 0.0,  // Distance in km
    val time: Double = 0.0,    // Duration in seconds
)
