package com.slick.tactical.engine.location

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import com.slick.tactical.service.OperationalState
import com.slick.tactical.util.SlickConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides hardware-batched GPS location updates as a [Flow].
 *
 * Uses [FusedLocationProviderClient] with [LocationRequest.Builder.setMaxUpdateDelayMillis]
 * to batch GPS readings in the hardware chip, reducing CPU wake-locks significantly
 * compared to 1Hz continuous polling.
 *
 * Batch interval adjusts based on [OperationalState]:
 * - [OperationalState.FULL_TACTICAL]: 5-second hardware batch (effective ~1Hz to UI)
 * - [OperationalState.SURVIVAL_MODE]: 30-second hardware batch (minimal CPU wakes)
 */
@Singleton
class BatchedLocationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fusedLocationClient: FusedLocationProviderClient,
) {

    /**
     * Emits GPS location updates batched by the device's GPS hardware chip.
     *
     * Caller must hold [android.Manifest.permission.ACCESS_FINE_LOCATION].
     * Calling code must check permission before collecting this Flow.
     *
     * @param operationalState Current [OperationalState] determines batch interval
     * @return Flow of [android.location.Location] updates
     */
    @SuppressLint("MissingPermission")
    fun locationFlow(operationalState: OperationalState): Flow<android.location.Location> =
        callbackFlow {
            val batchIntervalMs = when (operationalState) {
                OperationalState.FULL_TACTICAL -> SlickConstants.GPS_BATCH_FULL_MS
                OperationalState.SURVIVAL_MODE -> SlickConstants.GPS_SURVIVAL_INTERVAL_MS
            }

            val locationRequest = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                batchIntervalMs,
            )
                .setMinUpdateDistanceMeters(SlickConstants.GPS_MIN_DISPLACEMENT_METRES)
                .setMaxUpdateDelayMillis(batchIntervalMs)  // Hardware batching
                .build()

            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.locations.forEach { location ->
                        Timber.d(
                            "GPS batch: %d locations, latest speed=%.1f km/h",
                            result.locations.size,
                            (location.speed * 3.6),  // m/s to km/h
                        )
                        trySend(location)
                    }
                }
            }

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                callback,
                Looper.getMainLooper(),
            ).addOnFailureListener { e ->
                Timber.e(e, "Failed to request location updates")
                close(Exception("Location updates failed: ${e.localizedMessage}", e))
            }

            awaitClose {
                Timber.d("Removing GPS location updates")
                fusedLocationClient.removeLocationUpdates(callback)
            }
        }
}
