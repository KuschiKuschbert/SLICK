package com.slick.tactical.engine.location

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Monitors Android Activity Recognition for automatic Pre-Flight → In-Flight transitions.
 *
 * Detects:
 * - [DetectedActivity.IN_VEHICLE] ENTER → transition UI to In-Flight tactical HUD
 * - [DetectedActivity.STILL] ENTER → trigger weather sync if on-stop strategy is active
 *
 * The 30-90 second recognition delay is expected behaviour -- designed for fuel stops,
 * not red lights. Documented as "Stationary Sync" in the brand voice, not "Instant".
 *
 * Requires [android.Manifest.permission.ACTIVITY_RECOGNITION].
 */
@Singleton
class ActivityRecognitionManager @Inject constructor(
    private val context: Context,
) {

    private val _isInVehicle = MutableStateFlow(false)

    /**
     * True when Android confirms the device is in a moving vehicle.
     * Observed by [com.slick.tactical.ui.MainActivity] to trigger HUD transition.
     */
    val isInVehicle: StateFlow<Boolean> = _isInVehicle.asStateFlow()

    private val _isStill = MutableStateFlow(false)

    /**
     * True when Android confirms the device is stationary (30-90s confirmation delay).
     * Used by [com.slick.tactical.service.ConvoyForegroundService] to trigger on-stop weather sync.
     */
    val isStill: StateFlow<Boolean> = _isStill.asStateFlow()

    /**
     * Registers transition requests for IN_VEHICLE and STILL states.
     * Results are delivered via [ActivityTransitionReceiver].
     *
     * @return Result indicating successful registration or failure
     */
    fun startMonitoring(): Result<Unit> {
        return try {
            val transitions = listOf(
                ActivityTransition.Builder()
                    .setActivityType(DetectedActivity.IN_VEHICLE)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build(),
                ActivityTransition.Builder()
                    .setActivityType(DetectedActivity.IN_VEHICLE)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                    .build(),
                ActivityTransition.Builder()
                    .setActivityType(DetectedActivity.STILL)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build(),
            )

            val request = ActivityTransitionRequest(transitions)

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, ActivityTransitionReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )

            ActivityRecognition.getClient(context)
                .requestActivityTransitionUpdates(request, pendingIntent)
                .addOnSuccessListener {
                    Timber.i("ActivityRecognition monitoring started")
                }
                .addOnFailureListener { e ->
                    Timber.e(e, "ActivityRecognition registration failed")
                }

            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to start ActivityRecognition monitoring")
            Result.failure(Exception("ActivityRecognition start failed: ${e.localizedMessage}", e))
        }
    }

    /**
     * Called by [ActivityTransitionReceiver] when a transition is detected.
     * Updates [isInVehicle] and [isStill] state flows.
     */
    fun onTransitionDetected(activityType: Int, transitionType: Int) {
        when {
            activityType == DetectedActivity.IN_VEHICLE &&
                    transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER -> {
                Timber.i("ActivityRecognition: IN_VEHICLE detected -- switching to tactical HUD")
                _isInVehicle.value = true
                _isStill.value = false
                // Also update CrashDetectionManager
            }
            activityType == DetectedActivity.IN_VEHICLE &&
                    transitionType == ActivityTransition.ACTIVITY_TRANSITION_EXIT -> {
                Timber.i("ActivityRecognition: exited IN_VEHICLE")
                _isInVehicle.value = false
            }
            activityType == DetectedActivity.STILL &&
                    transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER -> {
                Timber.i("ActivityRecognition: STILL detected -- on-stop sync eligible")
                _isStill.value = true
                _isInVehicle.value = false
            }
        }
    }
}
