package com.slick.tactical.engine.mesh

import com.slick.tactical.util.SlickConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the >4 Protocol: dynamic broadcast throttling and MapLibre clustering
 * for convoys exceeding [SlickConstants.CONVOY_CLUSTERING_THRESHOLD] riders.
 *
 * When active:
 * - PACK riders: 0.2Hz (one broadcast per 5 seconds)
 * - LEADER / SWEEP: always 1Hz
 * - MapLibre GeoJSON clustering activates simultaneously
 *
 * The UI observes [isThrottlingActive] to enable/disable MapLibre cluster source options.
 */
@Singleton
class ConvoyOptimizationManager @Inject constructor() {

    private val _isThrottlingActive = MutableStateFlow(false)

    /** True when convoy exceeds threshold and >4 Protocol is active. */
    val isThrottlingActive: StateFlow<Boolean> = _isThrottlingActive.asStateFlow()

    private var currentConvoySize = 0
    private var cycleCount = 0L

    /**
     * Updates the active convoy size and re-evaluates throttling.
     * Call whenever a rider connects or disconnects.
     *
     * @param connectedCount Number of actively connected riders (excluding self)
     */
    fun updateConvoySize(connectedCount: Int) {
        currentConvoySize = connectedCount + 1  // +1 for self
        val wasThrottling = _isThrottlingActive.value
        _isThrottlingActive.value = currentConvoySize > SlickConstants.CONVOY_CLUSTERING_THRESHOLD

        if (wasThrottling != _isThrottlingActive.value) {
            Timber.i(
                ">4 Protocol %s: convoy size=%d (threshold=%d)",
                if (_isThrottlingActive.value) "ACTIVATED" else "DEACTIVATED",
                currentConvoySize,
                SlickConstants.CONVOY_CLUSTERING_THRESHOLD,
            )
        }
    }

    /**
     * Determines if the given [role] should transmit in this cycle.
     *
     * For FULL rate (1Hz), always returns true.
     * For throttled PACK riders (0.2Hz = every 5th cycle), returns true only on cycle 0, 5, 10...
     *
     * @param role [ConvoyRole] of this rider
     * @return true if this rider should transmit in the current 1Hz cycle
     */
    fun shouldTransmitThisCycle(role: ConvoyRole): Boolean {
        cycleCount++
        return when {
            !_isThrottlingActive.value -> true  // Not throttled -- always transmit
            role == ConvoyRole.LEADER || role == ConvoyRole.SWEEP -> true  // Always 1Hz
            role == ConvoyRole.PACK -> cycleCount % 5 == 0L  // 0.2Hz for pack
            else -> false
        }
    }
}
