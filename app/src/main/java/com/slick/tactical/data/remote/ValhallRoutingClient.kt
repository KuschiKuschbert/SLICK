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
     * Fetches a route polyline between [origin] and [destination].
     *
     * @param origin Start coordinate (e.g., Kawana)
     * @param destination End coordinate (e.g., Yeppoon)
     * @return Result containing ordered list of [Coordinate] points forming the route,
     *         or failure with cause
     */
    suspend fun fetchRoute(
        origin: Coordinate,
        destination: Coordinate,
    ): Result<List<Coordinate>> {
        return try {
            val requestBody = buildValhallRequest(origin, destination)

            val response = httpClient.post("${BuildConfig.VALHALLA_BASE_URL}/route") {
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }

            val routeResponse = response.body<ValhallRouteResponse>()

            if (routeResponse.trip.legs.isEmpty()) {
                return Result.failure(Exception("Valhalla returned empty route legs"))
            }

            val encodedShape = routeResponse.trip.legs.first().shape
            val coordinates = decodePolyline6(encodedShape)

            if (coordinates.isEmpty()) {
                return Result.failure(Exception("Decoded polyline is empty"))
            }

            Timber.i(
                "Valhalla route: %d points, %.1f km from (%.4f,%.4f) to (%.4f,%.4f)",
                coordinates.size,
                routeResponse.trip.summary.length,
                origin.lat, origin.lon,
                destination.lat, destination.lon,
            )

            Result.success(coordinates)
        } catch (e: Exception) {
            Timber.e(e, "Valhalla routing failed")
            Result.failure(Exception("Route fetch failed: ${e.localizedMessage}", e))
        }
    }

    private fun buildValhallRequest(origin: Coordinate, destination: Coordinate): String {
        return Json.encodeToString(
            ValhallRouteRequest.serializer(),
            ValhallRouteRequest(
                locations = listOf(
                    ValhallLocation(lon = origin.lon, lat = origin.lat, type = "break"),
                    ValhallLocation(lon = destination.lon, lat = destination.lat, type = "break"),
                ),
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
