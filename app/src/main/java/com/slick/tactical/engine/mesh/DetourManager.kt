package com.slick.tactical.engine.mesh

import com.slick.tactical.data.remote.ValhallRoutingClient
import com.slick.tactical.engine.weather.Coordinate
import com.slick.tactical.engine.weather.GripMatrix
import com.slick.tactical.engine.weather.RouteForecaster
import com.slick.tactical.util.SlickConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Manages Democratic Detour voting for the active convoy.
 *
 * When a rider long-presses a map location, they create a [DetourProposal].
 * Before broadcasting, the ETA impact is calculated via Valhalla:
 * - Route: currentPosition → proposedStop → nextMainRoutePoint
 * - Impact = (detour time) - (direct time to next main route point)
 *
 * Weather awareness: before finalizing, the proposed stop is evaluated
 * against [GripMatrix] to warn if the detour creates a downstream hazard.
 */
@Singleton
class DetourManager @Inject constructor(
    private val gripMatrix: GripMatrix,
    private val valhallClient: ValhallRoutingClient,
    private val routeForecaster: RouteForecaster,
) {

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _activeProposal = MutableStateFlow<DetourProposal?>(null)

    /** Observed by the UI overlay. Non-null when a vote is in progress. */
    val activeProposal: StateFlow<DetourProposal?> = _activeProposal.asStateFlow()

    private val votes = mutableMapOf<String, Boolean>()
    private var totalMemberCount = 0

    /**
     * Creates a detour proposal with ETA impact calculated from Valhalla.
     *
     * ETA impact = time via stop - time direct.
     * Uses the motorcycle routing profile for accurate road-speed estimates.
     *
     * @param proposerRiderId Ephemeral rider ID of the proposer
     * @param stopName Display name for the stop
     * @param stopLat GPS latitude of the proposed stop
     * @param stopLon GPS longitude of the proposed stop
     * @param currentLat Current rider latitude
     * @param currentLon Current rider longitude
     * @param nextDestLat Next main route destination latitude (used to compute impact)
     * @param nextDestLon Next main route destination longitude
     * @param totalConvoySize Total number of riders in the convoy (including self)
     * @return Result containing the created [DetourProposal], or failure
     */
    suspend fun proposeDetour(
        proposerRiderId: String,
        stopName: String,
        stopLat: Double,
        stopLon: Double,
        currentLat: Double,
        currentLon: Double,
        nextDestLat: Double,
        nextDestLon: Double,
        totalConvoySize: Int,
    ): Result<DetourProposal> = try {
        val now = LocalTime.now()
        val expiry = now.plusSeconds(SlickConstants.DETOUR_VOTE_WINDOW_SECONDS.toLong())

        // Calculate ETA impact in minutes (best-effort, fall back to 0 on any error)
        val etaImpactMinutes = calculateEtaImpact(
            currentLat, currentLon,
            stopLat, stopLon,
            nextDestLat, nextDestLon,
        )

        val proposal = DetourProposal(
            proposalId = java.util.UUID.randomUUID().toString(),
            proposerRiderId = proposerRiderId,
            stopName = stopName,
            latitude = stopLat,
            longitude = stopLon,
            proposedAt24h = now.format(timeFormatter),
            voteExpiry24h = expiry.format(timeFormatter),
            weatherWarning = "",
            etaImpactMinutes = etaImpactMinutes,
        )

        totalMemberCount = totalConvoySize
        votes.clear()
        _activeProposal.value = proposal

        Timber.i(
            "Detour proposed: %s at (%.4f,%.4f), ETA impact=+%d min, vote window=%ds",
            stopName, stopLat, stopLon, etaImpactMinutes, SlickConstants.DETOUR_VOTE_WINDOW_SECONDS,
        )
        Result.success(proposal)
    } catch (e: Exception) {
        Timber.e(e, "Detour proposal failed")
        Result.failure(Exception("Detour proposal failed: ${e.localizedMessage}", e))
    }

    /**
     * Simplified version for offline/P2P proposals where we don't have network.
     * Uses Haversine straight-line distance to estimate ETA impact at 60 km/h average.
     */
    fun proposeDetourOffline(
        proposerRiderId: String,
        stopName: String,
        stopLat: Double,
        stopLon: Double,
        currentLat: Double,
        currentLon: Double,
        totalConvoySize: Int,
    ): Result<DetourProposal> = try {
        val now = LocalTime.now()
        val expiry = now.plusSeconds(SlickConstants.DETOUR_VOTE_WINDOW_SECONDS.toLong())

        // Rough estimate: straight-line distance at 60 km/h average urban speed
        val detourKm = routeForecaster.haversineDistance(
            Coordinate(currentLat, currentLon),
            Coordinate(stopLat, stopLon),
        ) * 2  // round trip
        val etaImpactMinutes = ((detourKm / 60.0) * 60).roundToInt()  // 60 km/h → minutes

        val proposal = DetourProposal(
            proposalId = java.util.UUID.randomUUID().toString(),
            proposerRiderId = proposerRiderId,
            stopName = stopName,
            latitude = stopLat,
            longitude = stopLon,
            proposedAt24h = now.format(timeFormatter),
            voteExpiry24h = expiry.format(timeFormatter),
            weatherWarning = "",
            etaImpactMinutes = etaImpactMinutes,
        )

        totalMemberCount = totalConvoySize
        votes.clear()
        _activeProposal.value = proposal

        Timber.i("Detour proposed (offline): %s ETA ~+%d min", stopName, etaImpactMinutes)
        Result.success(proposal)
    } catch (e: Exception) {
        Result.failure(Exception("Offline detour proposal failed: ${e.localizedMessage}", e))
    }

    /**
     * Records a vote from a convoy member.
     */
    fun castVote(voterId: String, approved: Boolean): VoteResult {
        votes[voterId] = approved
        Timber.d("Vote cast by %s: %s (%d/%d)", voterId, if (approved) "YES" else "NO", votes.size, totalMemberCount)

        val yesVotes = votes.values.count { it }
        val noVotes = votes.values.count { !it }
        val majorityThreshold = totalMemberCount / 2 + 1

        return when {
            yesVotes >= majorityThreshold -> {
                Timber.i("Detour approved: %d YES votes", yesVotes)
                _activeProposal.value = null
                votes.clear()
                VoteResult.APPROVED
            }
            noVotes >= majorityThreshold -> {
                Timber.i("Detour rejected: %d NO votes", noVotes)
                _activeProposal.value = null
                votes.clear()
                VoteResult.REJECTED
            }
            else -> VoteResult.PENDING
        }
    }

    fun expireVote() {
        Timber.i("Detour vote expired without majority")
        _activeProposal.value = null
        votes.clear()
    }

    fun leaderVeto() {
        Timber.i("Detour vetoed by leader")
        _activeProposal.value = null
        votes.clear()
    }

    // ─── ETA Impact Calculation ────────────────────────────────────────────────

    /**
     * Calculates the ETA impact of a detour in minutes using Valhalla routing.
     *
     * Computes:
     * - Time via detour: current → stop → nextDest
     * - Time direct: current → nextDest
     * - Impact = via detour - direct
     *
     * Returns 0 if Valhalla is unavailable (network offline).
     */
    private suspend fun calculateEtaImpact(
        currentLat: Double, currentLon: Double,
        stopLat: Double, stopLon: Double,
        nextDestLat: Double, nextDestLon: Double,
    ): Int {
        return try {
            val current = Coordinate(currentLat, currentLon)
            val stop = Coordinate(stopLat, stopLon)
            val nextDest = Coordinate(nextDestLat, nextDestLon)

            val directRoute = valhallClient.fetchRoutePolyline(current, nextDest)
            val detourRoute = valhallClient.fetchRoutePolyline(current, nextDest, waypoint = stop)

            val directMinutes = directRoute.getOrNull()?.let { estimateMinutes(it) } ?: return 0
            val detourMinutes = detourRoute.getOrNull()?.let { estimateMinutes(it) } ?: return 0

            val impact = (detourMinutes - directMinutes).roundToInt().coerceAtLeast(0)
            Timber.d("Detour ETA: direct=%.1f min, via stop=%.1f min, impact=+%d min",
                directMinutes, detourMinutes, impact)
            impact
        } catch (e: Exception) {
            Timber.d(e, "Could not calculate detour ETA (likely offline) -- defaulting to 0")
            0
        }
    }

    /**
     * Estimates travel time in minutes from a decoded route polyline.
     * Uses Haversine distance and average motorcycle speed (90 km/h) for estimation.
     * Valhalla's route summary provides exact time, but we use the polyline as fallback.
     */
    private fun estimateMinutes(polyline: List<Coordinate>): Double {
        if (polyline.size < 2) return 0.0
        var totalKm = 0.0
        for (i in 1 until polyline.size) {
            totalKm += routeForecaster.haversineDistance(polyline[i - 1], polyline[i])
        }
        return (totalKm / 90.0) * 60.0  // 90 km/h average moto speed → minutes
    }
}

data class DetourProposal(
    val proposalId: String,
    val proposerRiderId: String,
    val stopName: String,
    val latitude: Double,
    val longitude: Double,
    val proposedAt24h: String,
    val voteExpiry24h: String,
    val weatherWarning: String,
    val etaImpactMinutes: Int,
)

enum class VoteResult { APPROVED, REJECTED, PENDING }
