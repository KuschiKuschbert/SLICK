package com.slick.tactical.engine.haven

import com.slick.tactical.data.local.dao.ShelterDao
import com.slick.tactical.data.local.entity.ShelterEntity
import com.slick.tactical.engine.weather.Coordinate
import com.slick.tactical.util.SlickConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Finds the nearest emergency haven (fuel canopy, shelter, underpass) to a high-danger route node.
 *
 * POIs are pre-cached from the Overpass API (OpenStreetMap) during pre-flight sync.
 * No network required -- all distance calculations use local Room DB.
 *
 * Distance algorithm: Haversine formula (same as [com.slick.tactical.engine.weather.RouteForecaster]).
 * Maximum detour radius: [SlickConstants.MAX_DETOUR_RADIUS_KM] km.
 *
 * Honest limitation: shelters are sourced from OpenStreetMap data. Coverage in remote QLD
 * bush is lower than urban areas. Always label as "Nearest Known Shelter" not "Confirmed Shelter".
 */
@Singleton
class EmergencyHavenLocator @Inject constructor(
    private val shelterDao: ShelterDao,
) {

    /**
     * Finds the nearest emergency shelter to a given GPS coordinate.
     *
     * Searches the local Room database -- no network call.
     * Returns shelters within [SlickConstants.MAX_DETOUR_RADIUS_KM] km only.
     *
     * @param nodeLat Latitude of the high-danger weather node
     * @param nodeLon Longitude of the high-danger weather node
     * @param routeCorridorId Identifier for the current route (filters shelters to this corridor)
     * @return Result containing the nearest [ShelterEntity], or failure if none within radius
     */
    suspend fun findNearestShelter(
        nodeLat: Double,
        nodeLon: Double,
        routeCorridorId: String,
    ): Result<ShelterEntity> = withContext(Dispatchers.Default) {
        try {
            val shelters = shelterDao.getSheltersForCorridor(routeCorridorId)

            if (shelters.isEmpty()) {
                return@withContext Result.failure(
                    Exception("No shelters cached for corridor $routeCorridorId -- pre-flight sync may have been skipped"),
                )
            }

            val nearestWithDistance = shelters
                .map { shelter ->
                    val distanceKm = haversineDistance(
                        Coordinate(nodeLat, nodeLon),
                        Coordinate(shelter.latitude, shelter.longitude),
                    )
                    Pair(shelter, distanceKm)
                }
                .filter { (_, dist) -> dist <= SlickConstants.MAX_DETOUR_RADIUS_KM }
                .minByOrNull { (_, dist) -> dist }

            if (nearestWithDistance == null) {
                return@withContext Result.failure(
                    Exception(
                        "No shelters within %.1f km of node (%.4f, %.4f)".format(
                            SlickConstants.MAX_DETOUR_RADIUS_KM,
                            nodeLat,
                            nodeLon,
                        ),
                    ),
                )
            }

            val (shelter, distanceKm) = nearestWithDistance
            Timber.i(
                "Nearest shelter: %s (%s) at %.1f km",
                shelter.name,
                shelter.type,
                distanceKm,
            )

            Result.success(shelter)
        } catch (e: Exception) {
            Timber.e(e, "EmergencyHavenLocator failed")
            Result.failure(Exception("Shelter search failed: ${e.localizedMessage}", e))
        }
    }

    /**
     * Haversine distance between two GPS coordinates in kilometres.
     * Duplicated from RouteForecaster to keep engine/haven self-contained (no circular dependency).
     */
    private fun haversineDistance(from: Coordinate, to: Coordinate): Double {
        val dLat = Math.toRadians(to.lat - from.lat)
        val dLon = Math.toRadians(to.lon - from.lon)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(from.lat)) * cos(Math.toRadians(to.lat)) * sin(dLon / 2).pow(2)
        return SlickConstants.EARTH_RADIUS_KM * 2 * asin(sqrt(a))
    }
}
