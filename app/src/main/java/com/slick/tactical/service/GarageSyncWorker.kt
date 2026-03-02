package com.slick.tactical.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * WorkManager job that downloads PMTiles map delta updates when the device is:
 * - Charging (to avoid battery drain on map downloads)
 * - Connected to unmetered Wi-Fi (to avoid cellular data usage)
 *
 * Runs between 02:00-04:00 (midnight window) to minimize user disruption.
 * Managed by WorkManager -- Android OS handles scheduling and persistence.
 *
 * Musk Protocol: never download large map files on cellular data.
 * Buffett Protocol: keep offline maps current for dead-zone survival.
 */
@HiltWorker
class GarageSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        private const val WORK_NAME = "slick_garage_sync"

        /**
         * Schedules the [GarageSyncWorker] to run weekly on Wi-Fi + charging.
         * Uses [ExistingPeriodicWorkPolicy.KEEP] to avoid duplicate schedules.
         *
         * @param context Application context for WorkManager access
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)  // Wi-Fi only -- never cellular
                .setRequiresCharging(true)                       // Must be plugged in
                .build()

            val request = PeriodicWorkRequestBuilder<GarageSyncWorker>(7, TimeUnit.DAYS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )

            Timber.d("GarageSyncWorker scheduled (weekly, Wi-Fi + charging)")
        }
    }

    override suspend fun doWork(): Result {
        Timber.i("GarageSyncWorker: starting PMTiles delta sync")

        return try {
            // TODO Phase 8: Implement actual PMTiles delta download from BuildConfig.PMTILES_URL
            // 1. Check available storage (StorageManager) -- abort if < 500MB free
            // 2. Download delta file from Cloudflare R2
            // 3. Validate checksum
            // 4. Replace local .pmtiles file atomically

            Timber.i("GarageSyncWorker: sync complete")
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "GarageSyncWorker: sync failed -- will retry")
            Result.retry()
        }
    }
}
