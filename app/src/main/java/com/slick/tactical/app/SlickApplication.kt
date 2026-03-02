package com.slick.tactical.app

import android.app.Application
import com.slick.tactical.BuildConfig
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

/**
 * Application entry point. Initializes Hilt DI and Timber logging.
 *
 * Timber is only planted for debug builds -- release builds have no logging
 * to prevent PII exposure via logcat.
 */
@HiltAndroidApp
class SlickApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.ENABLE_DEBUG_LOGGING) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.d("SLICK initialized. Tactical mode ready.")
    }
}
