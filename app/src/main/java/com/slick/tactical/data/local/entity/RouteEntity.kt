package com.slick.tactical.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted snapshot of the active route so the In-Flight HUD can restore after process death
 * without requiring the rider to re-sync weather.
 *
 * There is always at most one row in this table (id = "current_route").
 * The row is written after every successful [com.slick.tactical.data.repository.RouteRepository.fetchRouteAndSync]
 * and read back by [com.slick.tactical.data.repository.RouteRepository.restoreLastRoute].
 *
 * @property polylineJson JSON-encoded [List<Coordinate>] — the full road-following polyline from Valhalla
 * @property maneuversJson JSON-encoded [List<RouteManeuver>] — turn-by-turn instructions
 * @property savedAt Epoch milliseconds when the route was last synced (for display only)
 */
@Entity(tableName = "route_cache")
data class RouteEntity(
    @PrimaryKey val id: String = "current_route",
    val polylineJson: String,
    val maneuversJson: String,
    val originLat: Double,
    val originLon: Double,
    val destinationLat: Double,
    val destinationLon: Double,
    val totalDistanceKm: Double,
    val corridorId: String,
    val savedAt: Long,
)
