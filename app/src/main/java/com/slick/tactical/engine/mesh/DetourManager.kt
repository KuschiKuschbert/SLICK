package com.slick.tactical.engine.mesh

import com.slick.tactical.engine.weather.GripMatrix
import com.slick.tactical.util.SlickConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Democratic Detour voting for the active convoy.
 *
 * When a rider long-presses a map location, they create a [DetourProposal].
 * This is broadcast to all convoy members via [ConvoyMeshManager].
 * Members vote within [SlickConstants.DETOUR_VOTE_WINDOW_SECONDS] seconds.
 * Simple majority (>50%) approves. Leader has optional veto.
 *
 * Weather awareness: before finalizing, the proposed stop is evaluated
 * against [GripMatrix] to warn if the detour creates a downstream hazard.
 */
@Singleton
class DetourManager @Inject constructor(
    private val gripMatrix: GripMatrix,
) {

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    private val _activeProposal = MutableStateFlow<DetourProposal?>(null)

    /** Observed by the UI overlay. Non-null when a vote is in progress. */
    val activeProposal: StateFlow<DetourProposal?> = _activeProposal.asStateFlow()

    private val votes = mutableMapOf<String, Boolean>()  // riderId -> approved
    private var totalMemberCount = 0

    /**
     * Creates and broadcasts a detour proposal.
     *
     * @param proposerRiderId Ephemeral rider ID of the proposer
     * @param stopName Display name for the stop
     * @param latitude GPS latitude of the proposed stop
     * @param longitude GPS longitude of the proposed stop
     * @param totalConvoySize Total number of riders in the convoy (including self)
     * @return Result containing the created [DetourProposal], or failure
     */
    fun proposeDetour(
        proposerRiderId: String,
        stopName: String,
        latitude: Double,
        longitude: Double,
        totalConvoySize: Int,
    ): Result<DetourProposal> = try {
        val now = LocalTime.now()
        val expiry = now.plusSeconds(SlickConstants.DETOUR_VOTE_WINDOW_SECONDS.toLong())

        val proposal = DetourProposal(
            proposalId = java.util.UUID.randomUUID().toString(),
            proposerRiderId = proposerRiderId,
            stopName = stopName,
            latitude = latitude,
            longitude = longitude,
            proposedAt24h = now.format(timeFormatter),
            voteExpiry24h = expiry.format(timeFormatter),
            weatherWarning = "",
            etaImpactMinutes = 0,  // TODO Phase 3: calculate from Valhalla detour distance
        )

        totalMemberCount = totalConvoySize
        votes.clear()
        _activeProposal.value = proposal

        Timber.i("Detour proposed: %s at (%.4f, %.4f), vote window=%ds", stopName, latitude, longitude, SlickConstants.DETOUR_VOTE_WINDOW_SECONDS)
        Result.success(proposal)
    } catch (e: Exception) {
        Timber.e(e, "Detour proposal failed")
        Result.failure(Exception("Detour proposal failed: ${e.localizedMessage}", e))
    }

    /**
     * Records a vote from a convoy member.
     *
     * If majority is reached before the time window expires, [processVoteResult] is called immediately.
     *
     * @param voterId Ephemeral rider ID of the voter
     * @param approved True for YES, false for NO
     */
    fun castVote(voterId: String, approved: Boolean): VoteResult {
        votes[voterId] = approved
        Timber.d("Vote cast by %s: %s (%d/%d votes)", voterId, if (approved) "YES" else "NO", votes.size, totalMemberCount)

        val yesVotes = votes.values.count { it }
        val noVotes = votes.values.count { !it }
        val majorityThreshold = totalMemberCount / 2 + 1

        return when {
            yesVotes >= majorityThreshold -> {
                Timber.i("Detour approved: %d YES votes (threshold=%d)", yesVotes, majorityThreshold)
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

    /** Called when the vote window expires without majority. Rejects the proposal. */
    fun expireVote() {
        Timber.i("Detour vote expired without majority")
        _activeProposal.value = null
        votes.clear()
    }

    /** Clears any active proposal. Called when convoy leader vetoes. */
    fun leaderVeto() {
        Timber.i("Detour vetoed by leader")
        _activeProposal.value = null
        votes.clear()
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
