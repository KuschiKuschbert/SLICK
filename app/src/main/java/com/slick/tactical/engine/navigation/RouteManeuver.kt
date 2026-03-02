package com.slick.tactical.engine.navigation

/**
 * A single turn instruction from the Valhalla routing engine.
 *
 * @property type Valhalla maneuver type code (0=straight, 10=right, 15=left, etc.)
 * @property instruction Full text instruction (e.g., "Turn right onto Bruce Highway")
 * @property distanceKm Distance in km from this maneuver to the next
 * @property timeSeconds Travel time in seconds to the next maneuver
 * @property beginShapeIndex Index into the decoded route polyline where this maneuver begins
 */
data class RouteManeuver(
    val type: Int,
    val instruction: String,
    val distanceKm: Double,
    val timeSeconds: Int,
    val beginShapeIndex: Int,
) {
    /**
     * Maps the Valhalla maneuver type to a Unicode directional arrow for Zone 1 display.
     * Monospace-compatible characters that render clearly on OLED at 28-32sp.
     */
    val arrow: String get() = when (type) {
        0, 7, 8, 17, 22, 25, 38, 39, 40, 41, 42 -> "↑"  // straight / continue / merge
        1 -> "↑"     // start
        2, 18 -> "↗"  // start right / ramp right
        3, 19 -> "↖"  // start left / ramp left
        4, 5, 6, 31 -> "●"  // destination
        9, 23 -> "↗"  // slight right / stay right
        10, 20, 36 -> "→"  // right / exit right / merge right
        11 -> "↱"    // sharp right
        12, 13 -> "↩"  // U-turn
        14 -> "↲"    // sharp left
        15, 21, 24, 37 -> "←"  // left / exit left / stay left / merge left
        16 -> "↖"    // slight left
        26, 27 -> "↻"  // roundabout
        28, 29 -> "⛴"  // ferry
        else -> "↑"
    }
}

/**
 * The complete navigation result for an active route.
 *
 * @property polyline Full road-following GPS coordinates from Valhalla (decoded polyline6)
 * @property maneuvers Ordered list of turn instructions
 * @property totalDistanceKm Total route distance in km
 * @property totalTimeSeconds Estimated total travel time in seconds
 */
data class RouteResult(
    val polyline: List<com.slick.tactical.engine.weather.Coordinate>,
    val maneuvers: List<RouteManeuver>,
    val totalDistanceKm: Double,
    val totalTimeSeconds: Double,
)
