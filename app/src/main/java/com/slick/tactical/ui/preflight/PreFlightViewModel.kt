package com.slick.tactical.ui.preflight

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slick.tactical.data.repository.RouteRepository
import com.slick.tactical.engine.weather.Coordinate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class PreFlightUiState(
    // Place search fields -- displayed name in the search bar
    val originQuery: String = "Kawana Waters, QLD",
    val destinationQuery: String = "Yeppoon, QLD",

    // Resolved coordinates (set when user selects a suggestion or types raw coords)
    val originLat: Double = -26.7380,
    val originLon: Double = 153.1230,
    val destinationLat: Double = -23.1297,
    val destinationLon: Double = 150.7417,

    val averageSpeedKmh: String = "100",
    val departureTime24h: String = "08:00",

    val isSyncing: Boolean = false,
    val syncedNodeCount: Int = 0,
    val syncError: String? = null,
    val isSyncComplete: Boolean = false,
)

/**
 * ViewModel for the Pre-Flight configuration screen.
 *
 * Place search: [onOriginQueryChange] / [onDestinationQueryChange] update the text field.
 * [onOriginSelected] / [onDestinationSelected] are called when the user picks a suggestion,
 * resolving the display name and coordinates simultaneously.
 *
 * Weather sync pipeline: Valhalla routing → Haversine node slicing → Open-Meteo → Room DB
 */
@HiltViewModel
class PreFlightViewModel @Inject constructor(
    private val routeRepository: RouteRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PreFlightUiState())
    val state: StateFlow<PreFlightUiState> = _state.asStateFlow()

    // ─── Origin search ──────────────────────────────────────────────────────

    fun onOriginQueryChange(query: String) {
        _state.value = _state.value.copy(originQuery = query, syncError = null, isSyncComplete = false)
    }

    fun onOriginSelected(displayName: String, lat: Double, lon: Double) {
        Timber.d("Origin selected: %s (%.4f, %.4f)", displayName, lat, lon)
        _state.value = _state.value.copy(
            originQuery = displayName,
            originLat = lat,
            originLon = lon,
            syncError = null,
            isSyncComplete = false,
        )
    }

    // ─── Destination search ─────────────────────────────────────────────────

    fun onDestinationQueryChange(query: String) {
        _state.value = _state.value.copy(destinationQuery = query, syncError = null, isSyncComplete = false)
    }

    fun onDestinationSelected(displayName: String, lat: Double, lon: Double) {
        Timber.d("Destination selected: %s (%.4f, %.4f)", displayName, lat, lon)
        _state.value = _state.value.copy(
            destinationQuery = displayName,
            destinationLat = lat,
            destinationLon = lon,
            syncError = null,
            isSyncComplete = false,
        )
    }

    // ─── Ride parameters ────────────────────────────────────────────────────

    fun onAverageSpeedChanged(value: String) {
        _state.value = _state.value.copy(averageSpeedKmh = value)
    }

    fun onDepartureTimeChanged(value: String) {
        _state.value = _state.value.copy(departureTime24h = value)
    }

    // ─── Weather sync pipeline ──────────────────────────────────────────────

    /**
     * Triggers the full pre-flight pipeline:
     * Valhalla routing → Haversine node slicing → Open-Meteo weather → Room DB
     */
    fun syncRouteWeather() {
        val current = _state.value
        val speed = current.averageSpeedKmh.toDoubleOrNull() ?: run {
            _state.value = _state.value.copy(syncError = "Invalid speed: must be a number in km/h")
            return
        }

        val origin = Coordinate(current.originLat, current.originLon)
        val destination = Coordinate(current.destinationLat, current.destinationLon)

        viewModelScope.launch {
            _state.value = _state.value.copy(isSyncing = true, syncError = null, isSyncComplete = false)
            Timber.i(
                "Pre-flight sync: %s → %s at %.0f km/h departing %s",
                current.originQuery,
                current.destinationQuery,
                speed,
                current.departureTime24h,
            )

            routeRepository.fetchRouteAndSync(
                origin = origin,
                destination = destination,
                averageSpeedKmh = speed,
                departureTime24h = current.departureTime24h,
            ).fold(
                onSuccess = { nodeCount ->
                    Timber.i("Pre-flight sync complete: %d nodes", nodeCount)
                    _state.value = _state.value.copy(
                        isSyncing = false,
                        syncedNodeCount = nodeCount,
                        isSyncComplete = nodeCount > 0,
                    )
                },
                onFailure = { error ->
                    Timber.e(error, "Pre-flight sync failed")
                    _state.value = _state.value.copy(
                        isSyncing = false,
                        syncError = error.localizedMessage ?: "Sync failed",
                    )
                },
            )
        }
    }
}
