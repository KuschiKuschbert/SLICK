package com.slick.tactical.engine.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityTransitionResult
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

/**
 * Receives Activity Recognition transition events and forwards them to
 * [ActivityRecognitionManager].
 *
 * Registered as a receiver in AndroidManifest.xml.
 * Triggered by Google Play Services when a transition is detected.
 */
@AndroidEntryPoint
class ActivityTransitionReceiver : BroadcastReceiver() {

    @Inject
    lateinit var activityRecognitionManager: ActivityRecognitionManager

    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) {
            Timber.d("ActivityTransitionReceiver: no result in intent")
            return
        }

        try {
            val result = ActivityTransitionResult.extractResult(intent) ?: return
            for (event in result.transitionEvents) {
                Timber.d(
                    "ActivityTransition: type=%d transition=%d",
                    event.activityType,
                    event.transitionType,
                )
                activityRecognitionManager.onTransitionDetected(
                    activityType = event.activityType,
                    transitionType = event.transitionType,
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to process ActivityTransition events")
        }
    }
}
