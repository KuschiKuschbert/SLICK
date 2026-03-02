package com.slick.tactical.service

import android.content.Context
import android.os.StatFs
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.slick.tactical.BuildConfig
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * WorkManager job that downloads and updates the local PMTiles map file.
 *
 * Constraints (never download unless both are met):
 * - Device is charging (Musk Protocol: never drain battery for map updates)
 * - Connected to unmetered Wi-Fi (never use cellular data for large downloads)
 *
 * Download strategy:
 * 1. Check available storage -- abort if < [MIN_FREE_STORAGE_MB] MB free
 * 2. Check remote ETag/Last-Modified to skip download if unchanged
 * 3. Download to a temp file, validate size, then atomically replace the live file
 * 4. On failure: leave existing file intact, retry next cycle
 *
 * File location: [Context.filesDir]/slick-corridor.pmtiles
 * This path is also read by [com.slick.tactical.ui.inflight.Zone2Map.resolveMapStyle].
 */
@HiltWorker
class GarageSyncWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val httpClient: HttpClient,
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val WORK_NAME = "slick_garage_sync"
        private const val MIN_FREE_STORAGE_MB = 500L
        private const val PMTILES_FILENAME = "slick-corridor.pmtiles"
        private const val PMTILES_TEMP_FILENAME = "slick-corridor.pmtiles.tmp"
        private const val ETAG_PREFS = "garage_sync_prefs"
        private const val PREF_LAST_ETAG = "last_etag"

        /**
         * Schedules [GarageSyncWorker] to run weekly on Wi-Fi + charging.
         * Uses [ExistingPeriodicWorkPolicy.KEEP] to avoid duplicate schedules.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresCharging(true)
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

        /** Cancels any pending or running sync jobs. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Timber.d("GarageSyncWorker cancelled")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Timber.i("GarageSyncWorker: starting PMTiles sync check")

        val pmtilesUrl = BuildConfig.PMTILES_URL
        if (pmtilesUrl.isBlank() || pmtilesUrl.contains("YOUR_BUCKET")) {
            Timber.w("GarageSyncWorker: PMTILES_URL not configured -- skipping sync")
            return@withContext Result.success()
        }

        // Step 1: Check available storage
        val freeStorageMb = getFreeStorageMb()
        if (freeStorageMb < MIN_FREE_STORAGE_MB) {
            Timber.w("GarageSyncWorker: insufficient storage (%.0f MB free, need %d MB) -- aborting",
                freeStorageMb.toDouble(), MIN_FREE_STORAGE_MB)
            return@withContext Result.failure()
        }
        Timber.d("GarageSyncWorker: %.0f MB free storage available", freeStorageMb.toDouble())

        // Step 2: Check remote ETag to avoid redundant downloads
        val lastEtag = getLastEtag()
        val remoteEtag = fetchRemoteEtag(pmtilesUrl)

        if (remoteEtag != null && remoteEtag == lastEtag) {
            Timber.i("GarageSyncWorker: PMTiles unchanged (ETag=%s) -- skipping download", remoteEtag)
            return@withContext Result.success()
        }

        // Step 3: Download to temp file
        val liveFile = File(appContext.filesDir, PMTILES_FILENAME)
        val tempFile = File(appContext.filesDir, PMTILES_TEMP_FILENAME)

        val downloadSuccess = downloadToTempFile(pmtilesUrl, tempFile)
        if (!downloadSuccess) {
            tempFile.delete()
            return@withContext Result.retry()
        }

        // Step 4: Validate temp file size (must be > 1 MB to be a valid PMTiles file)
        if (tempFile.length() < 1_048_576) {
            Timber.e("GarageSyncWorker: downloaded file too small (%d bytes) -- aborting", tempFile.length())
            tempFile.delete()
            return@withContext Result.retry()
        }

        // Step 5: Atomic replace -- rename temp to live
        val replaced = tempFile.renameTo(liveFile)
        if (!replaced) {
            // On some devices renameTo fails across filesystems -- fallback to copy+delete
            tempFile.copyTo(liveFile, overwrite = true)
            tempFile.delete()
        }

        // Step 6: Save ETag for next check
        if (remoteEtag != null) saveEtag(remoteEtag)

        Timber.i("GarageSyncWorker: PMTiles updated successfully (%.1f MB, ETag=%s)",
            liveFile.length() / 1_048_576.0, remoteEtag)
        Result.success()
    }

    private fun getFreeStorageMb(): Long {
        val stat = StatFs(appContext.filesDir.path)
        return (stat.availableBlocksLong * stat.blockSizeLong) / 1_048_576
    }

    private suspend fun fetchRemoteEtag(url: String): String? = try {
        val response = httpClient.head(url)
        response.headers["ETag"]?.trim('"')
    } catch (e: Exception) {
        Timber.d(e, "GarageSyncWorker: could not fetch ETag for %s", url)
        null
    }

    private suspend fun downloadToTempFile(url: String, tempFile: File): Boolean {
        return try {
        Timber.i("GarageSyncWorker: downloading PMTiles from %s", url)
        val response = httpClient.get(url)

        if (!response.status.isSuccess()) {
            Timber.e("GarageSyncWorker: HTTP %d from %s", response.status.value, url)
            return false
        }

        val channel = response.bodyAsChannel()
        FileOutputStream(tempFile).use { out ->
            channel.toInputStream().use { input ->
                val buffer = ByteArray(8192)
                var totalBytes = 0L
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    out.write(buffer, 0, bytesRead)
                    totalBytes += bytesRead
                    if (totalBytes % 10_485_760 == 0L) {  // Log every 10 MB
                        Timber.d("GarageSyncWorker: downloaded %.0f MB...", totalBytes / 1_048_576.0)
                    }
                }
                Timber.d("GarageSyncWorker: download complete (%.1f MB)", totalBytes / 1_048_576.0)
            }
        }
        true
        } catch (e: Exception) {
            Timber.e(e, "GarageSyncWorker: download failed")
            false
        }
    }

    private fun getLastEtag(): String? {
        val prefs = appContext.getSharedPreferences(ETAG_PREFS, Context.MODE_PRIVATE)
        return prefs.getString(PREF_LAST_ETAG, null)
    }

    private fun saveEtag(etag: String) {
        appContext.getSharedPreferences(ETAG_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_LAST_ETAG, etag)
            .apply()
    }
}
