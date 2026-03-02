package com.slick.tactical.data.remote

import com.slick.tactical.data.local.entity.ShelterEntity
import com.slick.tactical.engine.weather.Coordinate
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Queries the OpenStreetMap Overpass API for emergency shelters along the route corridor.
 *
 * Called during pre-flight sync to populate [com.slick.tactical.data.local.entity.ShelterEntity]
 * in Room DB. Used by [com.slick.tactical.engine.haven.EmergencyHavenLocator] to find
 * the nearest shelter when a GripMatrix node reaches EXTREME danger level.
 *
 * POI types queried (within 5km of each route node):
 * - `amenity=fuel` -- petrol stations (usually have large weather canopies)
 * - `amenity=shelter` -- covered structures
 * - `amenity=toilets` -- public amenities (Australian National Toilet Map data in OSM)
 * - `amenity=drinking_water` -- free water access
 * - `highway=rest_area` -- roadside rest areas
 *
 * API endpoint: https://overpass-api.de/api/interpreter
 */
@Singleton
class OverpassClient @Inject constructor(
    private val httpClient: HttpClient,
) {

    private val overpassUrl = "https://overpass-api.de/api/interpreter"

    /**
     * Fetches emergency shelters within [radiusMetres] of the route bounding box.
     *
     * @param nodes Route coordinates used to build the bounding box query
     * @param radiusMetres Search radius around the route in metres (default: 5km)
     * @param routeCorridorId Identifier to group these shelters with a specific route
     * @return Result containing list of [ShelterEntity], or failure with cause
     */
    suspend fun fetchSheltersAlongRoute(
        nodes: List<Coordinate>,
        radiusMetres: Int = 5_000,
        routeCorridorId: String,
    ): Result<List<ShelterEntity>> {
        if (nodes.isEmpty()) return Result.success(emptyList())

        return try {
            // Build bounding box from route node coordinates
            val minLat = nodes.minOf { it.lat }
            val maxLat = nodes.maxOf { it.lat }
            val minLon = nodes.minOf { it.lon }
            val maxLon = nodes.maxOf { it.lon }

            // Overpass QL query -- finds all relevant amenities in the bounding box
            val query = buildQuery(minLat, minLon, maxLat, maxLon)

            val response = httpClient.get(overpassUrl) {
                parameter("data", query)
            }

            val overpassResponse = response.body<OverpassResponse>()
            val shelters = overpassResponse.elements.mapNotNull { element ->
                element.toShelterEntity(routeCorridorId)
            }

            Timber.i("Overpass: fetched %d POIs along route corridor", shelters.size)
            Result.success(shelters)
        } catch (e: Exception) {
            Timber.e(e, "Overpass API query failed")
            Result.failure(Exception("Overpass query failed: ${e.localizedMessage}", e))
        }
    }

    private fun buildQuery(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): String {
        val bbox = "$minLat,$minLon,$maxLat,$maxLon"
        return """
            [out:json][timeout:25];
            (
              node["amenity"="fuel"]($bbox);
              node["amenity"="shelter"]($bbox);
              node["amenity"="toilets"]($bbox);
              node["amenity"="drinking_water"]($bbox);
              node["highway"="rest_area"]($bbox);
              way["highway"="rest_area"]($bbox);
            );
            out center;
        """.trimIndent()
    }
}

// ─── Overpass API response models ─────────────────────────────────────────────

@Serializable
data class OverpassResponse(
    val elements: List<OverpassElement> = emptyList(),
)

@Serializable
data class OverpassElement(
    val type: String = "",
    val id: Long = 0,
    val lat: Double? = null,
    val lon: Double? = null,
    val center: OverpassCenter? = null,
    val tags: Map<String, String> = emptyMap(),
)

@Serializable
data class OverpassCenter(
    val lat: Double = 0.0,
    val lon: Double = 0.0,
)

private fun OverpassElement.toShelterEntity(routeCorridorId: String): ShelterEntity? {
    val resolvedLat = lat ?: center?.lat ?: return null
    val resolvedLon = lon ?: center?.lon ?: return null

    val amenity = tags["amenity"] ?: tags["highway"] ?: return null
    val name = tags["name"] ?: tags["operator"] ?: amenity.replaceFirstChar { it.uppercase() }

    val shelterType = when (amenity) {
        "fuel" -> "fuel_canopy"
        "shelter" -> "shelter"
        "toilets" -> "toilet"
        "drinking_water" -> "water"
        "rest_area" -> "rest_area"
        else -> amenity
    }

    val isOpen24h = tags["opening_hours"]?.contains("24/7") == true ||
        tags["access"] == "yes"

    return ShelterEntity(
        id = id.toString().ifBlank { UUID.randomUUID().toString() },
        type = shelterType,
        name = name,
        latitude = resolvedLat,
        longitude = resolvedLon,
        isOpen24h = isOpen24h,
        routeCorridorId = routeCorridorId,
    )
}
