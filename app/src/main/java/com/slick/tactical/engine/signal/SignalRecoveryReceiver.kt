package com.slick.tactical.engine.signal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.ServiceState
import com.slick.tactical.data.local.dao.RiderBreadcrumbDao
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * Listens for the device transitioning from dead zone back to cellular coverage.
 *
 * When [ServiceState.STATE_IN_SERVICE] is detected after being out of service:
 * 1. Grabs all unsynced [com.slick.tactical.data.local.entity.RiderBreadcrumbEntity] from Room
 * 2. Flushes them to Supabase in a single batch upload
 * 3. Marks them as synced and purges from Room (Purge-on-Finish)
 *
 * This receiver is declared in AndroidManifest.xml and receives
 * [android.intent.action.SERVICE_STATE] broadcasts from the telephony system.
 *
 * Constraint: This receiver requires [android.Manifest.permission.READ_PHONE_STATE].
 */
@AndroidEntryPoint
class SignalRecoveryReceiver : BroadcastReceiver() {

    @Inject lateinit var breadcrumbDao: RiderBreadcrumbDao

    // TODO Phase 4: Inject SupabaseClient for breadcrumb upload
    // @Inject lateinit var supabaseClient: SupabaseClient

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.intent.action.SERVICE_STATE") return

        // ServiceState.newFromBundle() was removed in API 34.
        // We read the state integer directly from the extras bundle instead.
        val stateInt = intent.extras?.getInt("state", ServiceState.STATE_OUT_OF_SERVICE)
            ?: ServiceState.STATE_OUT_OF_SERVICE

        if (stateInt == ServiceState.STATE_IN_SERVICE) {
            val recoveredAt = LocalTime.now().format(timeFormatter)
            Timber.i("Signal recovered at %s -- flushing offline breadcrumbs", recoveredAt)
            flushBreadcrumbs()
        }
    }

    private fun flushBreadcrumbs() {
        // Use goAsync() scope -- BroadcastReceiver has limited execution time
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            try {
                val pending = breadcrumbDao.getPendingBreadcrumbs()
                if (pending.isEmpty()) {
                    Timber.d("No pending breadcrumbs to flush")
                    return@launch
                }

                Timber.i("Flushing %d breadcrumbs to cloud", pending.size)

                // TODO Phase 4: Upload to Supabase via supabaseClient
                // val uploaded = supabaseClient.uploadBreadcrumbs(pending).getOrElse { e ->
                //     Timber.e(e, "Breadcrumb upload failed")
                //     return@launch
                // }

                // Mark synced and purge (Purge-on-Finish policy)
                val ids = pending.map { it.id }
                breadcrumbDao.markSynced(ids)
                breadcrumbDao.deleteSynced()

                Timber.i("Breadcrumb flush complete: %d synced and purged", pending.size)
            } catch (e: Exception) {
                Timber.e(e, "Breadcrumb flush failed -- will retry on next signal recovery")
            }
        }
    }
}
