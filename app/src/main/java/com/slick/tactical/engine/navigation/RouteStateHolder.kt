package com.slick.tactical.engine.navigation

import com.slick.tactical.engine.weather.Coordinate
import com.slick.tactical.engine.location.GpsStateHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Singleton that holds the current active route and navigation state.
 *
 * Written by [com.slick.tactical.data.repository.RouteRepository] after a successful
 * Valhalla route fetch. Observed by [com.slick.tactical.ui.inflight.InFlightViewModel]
 * to drive the Zone 2 map and Zone 1 telemetry.
 *
 * Architecture: same bridge pattern as [GpsStateHolder] — crosses Engine Room / UI boundary
 * without coupling the repository to the ViewModel directly.
 */
@Singleton
class RouteStateHolder @Inject constructor() {

    private val _state = MutableStateFlow(NavigationState())
    val state: StateFlow<NavigationState> = _state.asStateFlow()

    /** Called by RouteRepository after a successful Valhalla fetch. */
    fun setRoute(
        polyline: List<Coordinate>,
        maneuvers: List<RouteManeuver>,
        origin: Coordinate,
        destination: Coordinate,
        totalDistanceKm: Double,
        corridorId: String = "",
    ) {
        _state.value = NavigationState(
            fullPolyline = polyline,
            maneuvers = maneuvers,
            origin = origin,
            destination = destination,
            totalDistanceKm = totalDistanceKm,
            corridorId = corridorId,
            hasRoute = true,
        )
        Timber.i("RouteStateHolder: route set (%d polyline pts, %d maneuvers, %.1f km)",
            polyline.size, maneuvers.size, totalDistanceKm)
    }

    /** Clears the active route (e.g., when the convoy ends). */
    fun clear() {
        _state.value = NavigationState()
        Timber.d("RouteStateHolder: route cleared")
    }

    /**
     * Computes the next turn instruction for the rider's current GPS position.
     *
     * Finds the nearest point on the route polyline using a stepped search (every 10th point),
     * then walks forward to the next maneuver. Returns the maneuver and distance to it.
     *
     * Performance: O(n/10) where n = polyline points. For the 400km Kawana→Yeppoon run
     * with ~25,000 Valhalla points this is ~2,500 comparisons per GPS update — fast on any
     * modern Android device without a background thread.
     *
     * @param riderLat Current rider latitude
     * @param riderLon Current rider longitude
     * @return [NextTurn] with the maneuver arrow, instruction text, and distance in metres
     */
    fun computeNextTurn(riderLat: Double, riderLon: Double): NextTurn {
        val nav = _state.value
        if (!nav.hasRoute || nav.polyline.isEmpty() || nav.maneuvers.isEmpty()) {
            return NextTurn()
        }

        // Step 1: find nearest polyline index (sample every 10th point for speed)
        var minDistKm = Double.MAX_VALUE
        var nearestIdx = 0
        nav.polyline.forEachIndexed { idx, coord ->
            if (idx % 10 == 0 || idx == nav.polyline.lastIndex) {
                val d = haversineKm(riderLat, riderLon, coord.lat, coord.lon)
                if (d < minDistKm) {
                    minDistKm = d
                    nearestIdx = idx
                }
            }
        }

        // Step 2: find the next maneuver after current position
        val nextManeuver = nav.maneuvers.firstOrNull { it.beginShapeIndex > nearestIdx }
            ?: nav.maneuvers.lastOrNull()
            ?: return NextTurn()

        // Step 3: sum distances along polyline from rider to maneuver start
        val targetIdx = nextManeuver.beginShapeIndex.coerceAtMost(nav.polyline.lastIndex)
        var distM = 0.0
        for (i in nearestIdx until targetIdx) {
            val a = nav.polyline[i]
            val b = nav.polyline.getOrNull(i + 1) ?: break
            distM += haversineKm(a.lat, a.lon, b.lat, b.lon) * 1000.0
        }

        return NextTurn(
            arrow = nextManeuver.arrow,
            instruction = nextManeuver.instruction,
            distanceMetres = distM.roundToInt(),
        )
    }

    // ─── Haversine (local copy -- avoids DI just for a math function) ──────────

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2).pow(2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2).pow(2)
        return r * 2 * Math.asin(Math.sqrt(a))
    }

    private fun Double.pow(exp: Int): Double {
        var result = 1.0
        repeat(exp) { result *= this }
        return result
    }
}

/**
 * Snapshot of all navigation data needed by the UI.
 *
 * @property fullPolyline Complete road-following route (decoded from Valhalla polyline6)
 * @property maneuvers Ordered turn instructions
 * @property origin Route start coordinate (for start marker on map)
 * @property destination Route end coordinate (for end marker on map)
 * @property totalDistanceKm Total route distance for ETA display
 * @property hasRoute True when a valid route has been loaded
 */
data class NavigationState(
    val fullPolyline: List<Coordinate> = emptyList(),
    val maneuvers: List<RouteManeuver> = emptyList(),
    val origin: Coordinate? = null,
    val destination: Coordinate? = null,
    val totalDistanceKm: Double = 0.0,
    val corridorId: String = "",
    val hasRoute: Boolean = false,
) {
    val polyline: List<Coordinate> get() = fullPolyline
}

/**
 * The next turn instruction at the rider's current position.
 *
 * @property arrow Unicode directional arrow (↑ ↗ → ↘ ↙ ← ↖ etc.)
 * @property instruction Full human-readable instruction from Valhalla
 * @property distanceMetres Distance to the maneuver start in metres
 */
data class NextTurn(
    val arrow: String = "↑",
    val instruction: String = "Follow route",
    val distanceMetres: Int = 0,
)
