package com.slick.tactical.ui.preflight

import android.annotation.SuppressLint
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.slick.tactical.data.repository.RouteRepository
import com.slick.tactical.engine.navigation.RouteStateHolder
import com.slick.tactical.engine.weather.Coordinate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
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
    /** 0.0–1.0 fraction of weather nodes fetched. Updated in real time as parallel calls complete. */
    val syncProgress: Float = 0f,
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
data class LocationFetchState(
    val isFetching: Boolean = false,
    val error: String? = null,
)

/** Kawana Waters hardcoded default — used to detect whether origin has been set by the user. */
private const val DEFAULT_ORIGIN_LAT = -26.7380

@HiltViewModel
class PreFlightViewModel @Inject constructor(
    private val routeRepository: RouteRepository,
    private val routeStateHolder: RouteStateHolder,
    private val fusedLocationClient: FusedLocationProviderClient,
) : ViewModel() {

    private val _locationFetchState = MutableStateFlow(LocationFetchState())
    val locationFetchState: StateFlow<LocationFetchState> = _locationFetchState.asStateFlow()

    private val _state = MutableStateFlow(PreFlightUiState())
    val state: StateFlow<PreFlightUiState> = _state.asStateFlow()

    init {
        autoDetectOriginIfDefault()
        restoreSyncStateIfCached()
    }

    /**
     * Checks if a previous sync is still valid (route in RouteStateHolder + nodes in Room).
     * If so, marks the UI as ready without requiring the rider to re-sync after navigating away
     * from the route page and returning.
     */
    private fun restoreSyncStateIfCached() {
        viewModelScope.launch(Dispatchers.IO) {
            val nav = routeStateHolder.state.value
            if (!nav.hasRoute) return@launch
            val nodeCount = routeRepository.getCachedNodeCount()
            if (nodeCount > 0) {
                Timber.d("PreFlight: restored sync state from cache (%d nodes)", nodeCount)
                _state.value = _state.value.copy(
                    isSyncComplete = true,
                    syncedNodeCount = nodeCount,
                    syncProgress = 1f,
                    // Restore origin/destination display names from the cached route
                    originLat = nav.origin?.lat ?: _state.value.originLat,
                    originLon = nav.origin?.lon ?: _state.value.originLon,
                    originQuery = nav.origin?.let { "My Location (%.4f, %.4f)".format(it.lat, it.lon) }
                        ?: _state.value.originQuery,
                    destinationLat = nav.destination?.lat ?: _state.value.destinationLat,
                    destinationLon = nav.destination?.lon ?: _state.value.destinationLon,
                )
            }
        }
    }

    /**
     * Fires [fetchCurrentLocation] on init if the origin is still the hardcoded Kawana default.
     * Riders always start from where they are, not from a static Queensland town.
     */
    private fun autoDetectOriginIfDefault() {
        if (_state.value.originLat == DEFAULT_ORIGIN_LAT) {
            fetchCurrentLocation()
        }
    }

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

    // ─── Current location ────────────────────────────────────────────────────

    /**
     * Fetches the device's last known GPS position and sets it as the route origin.
     *
     * Uses [FusedLocationProviderClient.lastLocation] which returns instantly from cache
     * (no GPS warm-up time). Falls back to [FusedLocationProviderClient.getCurrentLocation]
     * if lastLocation is null (device hasn't had a fix recently).
     *
     * Caller is responsible for verifying location permission before calling this.
     * If permission is not granted, the call is a no-op (GPS not accessed).
     */
    @SuppressLint("MissingPermission")
    fun fetchCurrentLocation() {
        viewModelScope.launch {
            _locationFetchState.value = LocationFetchState(isFetching = true)
            try {
                // Try cached last location first (instant, no power cost)
                var location = fusedLocationClient.lastLocation.await()

                // If null (device has no recent fix), request a fresh single fix
                if (location == null) {
                    Timber.d("Last location null -- requesting current location")
                    val request = com.google.android.gms.location.CurrentLocationRequest.Builder()
                        .setPriority(com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                        .setMaxUpdateAgeMillis(60_000L)  // Accept fix up to 60s old
                        .build()
                    location = fusedLocationClient.getCurrentLocation(request, null).await()
                }

                if (location != null) {
                    val displayName = "My Location (%.4f, %.4f)".format(location.latitude, location.longitude)
                    onOriginSelected(displayName, location.latitude, location.longitude)
                    _locationFetchState.value = LocationFetchState()
                    Timber.i("Current location set as origin: %.4f, %.4f", location.latitude, location.longitude)
                } else {
                    _locationFetchState.value = LocationFetchState(error = "No GPS fix available")
                    Timber.w("Could not get current location -- no fix available")
                }
            } catch (e: Exception) {
                Timber.e(e, "fetchCurrentLocation failed")
                _locationFetchState.value = LocationFetchState(error = "Location unavailable: ${e.localizedMessage}")
            }
        }
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
            _state.value = _state.value.copy(isSyncing = true, syncProgress = 0f, syncError = null, isSyncComplete = false)
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
                onProgress = { fraction ->
                    // Called from a background coroutine -- StateFlow update is thread-safe
                    _state.value = _state.value.copy(syncProgress = fraction)
                },
            ).fold(
                onSuccess = { nodeCount ->
                    Timber.i("Pre-flight sync complete: %d nodes", nodeCount)
                    _state.value = _state.value.copy(
                        isSyncing = false,
                        syncProgress = 1f,
                        syncedNodeCount = nodeCount,
                        isSyncComplete = nodeCount > 0,
                    )
                },
                onFailure = { error ->
                    Timber.e(error, "Pre-flight sync failed")
                    _state.value = _state.value.copy(
                        isSyncing = false,
                        syncProgress = 0f,
                        syncError = error.localizedMessage ?: "Sync failed",
                    )
                },
            )
        }
    }
}
