package com.slick.tactical.engine.location

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton GPS state carrier that bridges the Engine Room ([ConvoyForegroundService])
 * with the Expendable Layer ([InFlightViewModel]).
 *
 * The foreground service writes here on every GPS update.
 * Any ViewModel collecting [location] gets live GPS without coupling to the service lifecycle.
 *
 * Architecture note: this is the approved channel for GPS data to cross the
 * Engine Room / Expendable Layer boundary. ViewModels must never start their own
 * location requests -- only observe this holder.
 */
@Singleton
class GpsStateHolder @Inject constructor() {

    private val _location = MutableStateFlow(GpsState())
    val location: StateFlow<GpsState> = _location.asStateFlow()

    /** Updated by [ConvoyForegroundService] on every GPS batch delivery. */
    fun update(lat: Double, lon: Double, speedKmh: Double, bearingDeg: Float) {
        _location.value = GpsState(lat = lat, lon = lon, speedKmh = speedKmh, bearingDeg = bearingDeg)
    }
}

/**
 * Immutable snapshot of the latest GPS fix.
 *
 * @param lat WGS-84 latitude in decimal degrees
 * @param lon WGS-84 longitude in decimal degrees
 * @param speedKmh Ground speed in km/h (from GPS Doppler, accurate at highway speeds)
 * @param bearingDeg True heading in degrees (0 = North, 90 = East, 180 = South, 270 = West)
 * @param hasFix True once a real GPS fix has been received (lat/lon are not defaults)
 */
data class GpsState(
    val lat: Double = -26.7380,   // Default: Kawana Waters, QLD
    val lon: Double = 153.1230,
    val speedKmh: Double = 0.0,
    val bearingDeg: Float = 0f,
    val hasFix: Boolean = false,
)
