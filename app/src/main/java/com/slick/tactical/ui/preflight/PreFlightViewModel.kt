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
    val originLat: String = "-26.7380",   // Default: Kawana
    val originLon: String = "153.1230",
    val destinationLat: String = "-23.1300",  // Default: Yeppoon
    val destinationLon: String = "150.7400",
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
 * Manages route input and triggers the full weather sync pipeline:
 * Valhalla polyline → Haversine node slicing → Open-Meteo weather → Room DB
 *
 * The convoy can only be started once [isSyncComplete] is true (weather loaded).
 */
@HiltViewModel
class PreFlightViewModel @Inject constructor(
    private val routeRepository: RouteRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PreFlightUiState())
    val state: StateFlow<PreFlightUiState> = _state.asStateFlow()

    fun onOriginLatChanged(value: String) {
        _state.value = _state.value.copy(originLat = value, syncError = null, isSyncComplete = false)
    }

    fun onOriginLonChanged(value: String) {
        _state.value = _state.value.copy(originLon = value, syncError = null, isSyncComplete = false)
    }

    fun onDestinationLatChanged(value: String) {
        _state.value = _state.value.copy(destinationLat = value, syncError = null, isSyncComplete = false)
    }

    fun onDestinationLonChanged(value: String) {
        _state.value = _state.value.copy(destinationLon = value, syncError = null, isSyncComplete = false)
    }

    fun onAverageSpeedChanged(value: String) {
        _state.value = _state.value.copy(averageSpeedKmh = value)
    }

    fun onDepartureTimeChanged(value: String) {
        _state.value = _state.value.copy(departureTime24h = value)
    }

    /**
     * Triggers the full pre-flight pipeline:
     * Valhalla routing → Haversine node slicing → Open-Meteo weather sync → Room DB
     *
     * Populates [state.syncedNodeCount] on success or [state.syncError] on failure.
     */
    fun syncRouteWeather() {
        val current = _state.value
        val origin = parseCoordinate(current.originLat, current.originLon)
            ?: run {
                _state.value = _state.value.copy(syncError = "Invalid origin coordinates")
                return
            }
        val destination = parseCoordinate(current.destinationLat, current.destinationLon)
            ?: run {
                _state.value = _state.value.copy(syncError = "Invalid destination coordinates")
                return
            }
        val speed = current.averageSpeedKmh.toDoubleOrNull()
            ?: run {
                _state.value = _state.value.copy(syncError = "Invalid speed: must be a number in km/h")
                return
            }

        viewModelScope.launch {
            _state.value = _state.value.copy(isSyncing = true, syncError = null, isSyncComplete = false)
            Timber.i("Pre-flight sync: (%.4f,%.4f) → (%.4f,%.4f) at %.0f km/h departing %s",
                origin.lat, origin.lon, destination.lat, destination.lon,
                speed, current.departureTime24h)

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

    private fun parseCoordinate(latStr: String, lonStr: String): Coordinate? {
        val lat = latStr.toDoubleOrNull() ?: return null
        val lon = lonStr.toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return Coordinate(lat, lon)
    }
}
