package com.slick.tactical.app

import android.app.Application
import com.slick.tactical.BuildConfig
import dagger.hilt.android.HiltAndroidApp
import io.sentry.android.core.SentryAndroid
import timber.log.Timber

/**
 * Application entry point. Initialises Hilt DI, Sentry crash reporting, and Timber logging.
 *
 * Logging strategy by build type:
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  Build      │ Timber            │ Sentry      │ Min level to Sentry │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │  debug      │ DebugTree (all)   │ disabled    │ n/a                 │
 * │  staging    │ DebugTree (all)   │ enabled     │ WARN+               │
 * │  release    │ SlickCrashTree    │ enabled     │ WARN+               │
 * └─────────────────────────────────────────────────────────────────────┘
 *
 * Security guarantees:
 * - DEBUG / INFO logs never leave the device (Timber.d, Timber.i are no-ops in release)
 * - All messages routed to Sentry are GPS-sanitised by [SlickCrashTree] before transmission
 * - Sentry is initialised with PII collection disabled (no IP addresses, no device names)
 */
@HiltAndroidApp
class SlickApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        initialiseSentry()
        initialiseTimber()

        Timber.i("SLICK initialised. Build=%s, crashReporting=%s",
            BuildConfig.BUILD_TYPE, BuildConfig.ENABLE_CRASH_REPORTING)
    }

    private fun initialiseSentry() {
        if (!BuildConfig.ENABLE_CRASH_REPORTING) return

        // SENTRY_DSN comes from local.properties via the Secrets Gradle Plugin.
        // It will be an empty string when not set (dev default).
        // Guard here so the SDK is never initialised without a valid DSN.
        val dsn = BuildConfig.SENTRY_DSN
        if (dsn.isBlank()) {
            Timber.i("Sentry disabled -- SENTRY_DSN not set in local.properties")
            return
        }

        SentryAndroid.init(this) { options ->
            options.dsn = dsn
            options.isEnabled = true
            options.environment = BuildConfig.BUILD_TYPE   // "release", "staging"
            options.release = "slick@${BuildConfig.VERSION_NAME}"

            // PII OFF: never collect IP addresses or device names
            options.isSendDefaultPii = false

            // ANR detection: catch Application Not Responding events
            options.isAnrEnabled = true
            options.anrTimeoutIntervalMillis = 5_000L

            // Performance: minimal overhead -- only capture errors, no transactions
            options.tracesSampleRate = 0.0

            // Session tracking: know how many crashes vs total sessions
            options.isEnableAutoSessionTracking = true

            Timber.d("Sentry initialised for %s build", BuildConfig.BUILD_TYPE)
        }
    }

    private fun initialiseTimber() {
        if (BuildConfig.ENABLE_DEBUG_LOGGING) {
            // Debug/Staging: full verbose logging with file and line number
            Timber.plant(Timber.DebugTree())
        }

        if (BuildConfig.ENABLE_CRASH_REPORTING) {
            // Staging/Release: route WARN+ to Sentry with PII scrubbing
            Timber.plant(SlickCrashTree())
        }
    }
}
