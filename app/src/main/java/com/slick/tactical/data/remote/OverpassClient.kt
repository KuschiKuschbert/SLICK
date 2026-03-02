package com.slick.tactical.data.remote

import com.slick.tactical.data.local.entity.ShelterEntity
import com.slick.tactical.engine.weather.Coordinate
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.Serializable
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Queries the OpenStreetMap Overpass API for take-cover and refuel points along the route.
 *
 * Called during pre-flight sync to populate [ShelterEntity] in Room DB.
 * All lookups during the ride use the local DB -- no network required.
 *
 * POI categories (within 10km of the route bounding box):
 *
 * ── Critical shelter (fuel + weather canopy) ─────────────────────────────────
 * - `amenity=fuel` — servo / petrol station (large weather canopies, always first choice)
 * - `highway=rest_area` — roadside rest stops
 * - `amenity=shelter` — formal shelter structures
 *
 * ── Take-cover spots (dry inside, rider can wait out rain) ───────────────────
 * - `amenity=pub` — pub (QLD staple on regional routes)
 * - `amenity=bar` — bar
 * - `amenity=cafe` — cafe
 * - `amenity=restaurant` — restaurant
 * - `amenity=fast_food` — roadhouse fast food
 * - `tourism=hotel` — hotel
 * - `tourism=motel` — motel
 * - `shop=convenience` — IGA, servo shop, corner store (often at servos)
 *
 * ── Basic amenities ──────────────────────────────────────────────────────────
 * - `amenity=toilets` — public toilets
 * - `amenity=drinking_water` — free water (dehydration risk on QLD summer runs)
 *
 * API: https://overpass-api.de/api/interpreter (public, rate-limited, no key needed)
 */
@Singleton
class OverpassClient @Inject constructor(
    private val httpClient: HttpClient,
) {

    private val overpassUrl = "https://overpass-api.de/api/interpreter"

    /**
     * Fetches all take-cover POIs within the route corridor bounding box.
     *
     * Uses a 10km padded bounding box around the route nodes to capture
     * servos and pubs just off the highway -- common on regional QLD routes.
     *
     * @param nodes Route coordinates (sampled every 10th point for bbox calculation)
     * @param routeCorridorId Groups POIs to the current route for later lookup
     * @return Result containing list of [ShelterEntity] (may be empty in remote areas)
     */
    suspend fun fetchSheltersAlongRoute(
        nodes: List<Coordinate>,
        radiusMetres: Int = 10_000,   // 10km corridor on each side
        routeCorridorId: String,
    ): Result<List<ShelterEntity>> {
        if (nodes.isEmpty()) return Result.success(emptyList())

        return try {
            val minLat = nodes.minOf { it.lat } - 0.1   // ~11km padding
            val maxLat = nodes.maxOf { it.lat } + 0.1
            val minLon = nodes.minOf { it.lon } - 0.15
            val maxLon = nodes.maxOf { it.lon } + 0.15

            val query = buildQuery(minLat, minLon, maxLat, maxLon)

            val response = httpClient.get(overpassUrl) {
                parameter("data", query)
            }

            val overpassResponse = response.body<OverpassResponse>()
            val shelters = overpassResponse.elements.mapNotNull { element ->
                element.toShelterEntity(routeCorridorId)
            }

            val breakdown = shelters.groupBy { it.type }.map { (k, v) -> "$k=${v.size}" }.joinToString(", ")
            Timber.i("Overpass: %d POIs along corridor (%s)", shelters.size, breakdown)
            Result.success(shelters)
        } catch (e: Exception) {
            Timber.w(e, "Overpass API query failed -- shelters unavailable offline")
            Result.failure(Exception("Overpass query failed: ${e.localizedMessage}", e))
        }
    }

    private fun buildQuery(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): String {
        val bbox = "$minLat,$minLon,$maxLat,$maxLon"
        return """
            [out:json][timeout:30];
            (
              node["amenity"="fuel"]($bbox);
              node["amenity"="shelter"]($bbox);
              node["amenity"="toilets"]($bbox);
              node["amenity"="drinking_water"]($bbox);
              node["amenity"="pub"]($bbox);
              node["amenity"="bar"]($bbox);
              node["amenity"="cafe"]($bbox);
              node["amenity"="restaurant"]($bbox);
              node["amenity"="fast_food"]($bbox);
              node["tourism"="hotel"]($bbox);
              node["tourism"="motel"]($bbox);
              node["shop"="convenience"]($bbox);
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

    val amenity = tags["amenity"] ?: tags["highway"] ?: tags["tourism"] ?: tags["shop"] ?: return null
    val name = tags["name"] ?: tags["operator"] ?: tags["brand"] ?: amenity.replaceFirstChar { it.uppercase() }

        val shelterType = when (amenity) {
            "fuel" -> ShelterType.FUEL
            "shelter" -> ShelterType.SHELTER
            "toilets" -> ShelterType.TOILET
            "drinking_water" -> ShelterType.WATER
            "rest_area" -> ShelterType.REST_AREA
            "pub", "bar" -> ShelterType.PUB
            "cafe", "restaurant", "fast_food" -> ShelterType.CAFE
            "hotel", "motel" -> ShelterType.HOTEL
            "convenience" -> ShelterType.CONVENIENCE
            else -> return null
        }

    val isOpen24h = tags["opening_hours"]?.contains("24/7") == true ||
        tags["access"] == "yes" ||
        shelterType == ShelterType.FUEL  // Most QLD highway servos are 24h or automated

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

/**
 * Standardised shelter type keys. Match these in [Zone2Map] for color-coded markers.
 */
object ShelterType {
    const val FUEL = "fuel"             // ⛽ Petrol station — critical, large canopy
    const val REST_AREA = "rest_area"   // 🛑 Roadside rest area
    const val SHELTER = "shelter"       // 🏠 Formal shelter structure
    const val PUB = "pub"               // 🍺 Pub / bar — take cover, QLD staple
    const val CAFE = "cafe"             // ☕ Cafe / restaurant / fast food
    const val HOTEL = "hotel"           // 🏨 Hotel / motel
    const val CONVENIENCE = "convenience" // 🛒 Servo shop / IGA
    const val TOILET = "toilet"         // 🚻 Public toilet
    const val WATER = "water"           // 💧 Drinking water
}
