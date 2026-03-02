package com.slick.tactical.engine.signal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.ServiceState
import com.slick.tactical.data.local.dao.RiderBreadcrumbDao
import com.slick.tactical.data.local.entity.RiderBreadcrumbEntity
import dagger.hilt.android.AndroidEntryPoint
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import timber.log.Timber
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * Listens for the device transitioning from dead zone back to cellular coverage.
 *
 * When [ServiceState.STATE_IN_SERVICE] is detected after being out of service:
 * 1. Grabs all unsynced [RiderBreadcrumbEntity] from the local Room DB
 * 2. Uploads them to Supabase `rider_breadcrumbs` table in a single batch
 * 3. Marks them synced and purges from Room (Purge-on-Finish policy)
 *
 * Note: ServiceState.newFromBundle() was removed in API 34. We read
 * the state int directly from the intent extras bundle instead.
 */
@AndroidEntryPoint
class SignalRecoveryReceiver : BroadcastReceiver() {

    @Inject lateinit var breadcrumbDao: RiderBreadcrumbDao
    @Inject lateinit var supabaseClient: SupabaseClient

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.intent.action.SERVICE_STATE") return

        val stateInt = intent.extras?.getInt("state", ServiceState.STATE_OUT_OF_SERVICE)
            ?: ServiceState.STATE_OUT_OF_SERVICE

        if (stateInt == ServiceState.STATE_IN_SERVICE) {
            val recoveredAt = LocalTime.now().format(timeFormatter)
            Timber.i("Signal recovered at %s -- flushing offline breadcrumbs", recoveredAt)
            flushBreadcrumbs()
        }
    }

    private fun flushBreadcrumbs() {
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            try {
                val pending = breadcrumbDao.getPendingBreadcrumbs()
                if (pending.isEmpty()) {
                    Timber.d("No pending breadcrumbs to flush")
                    return@launch
                }

                Timber.i("Flushing %d breadcrumbs to Supabase", pending.size)

                // Upload batch to Supabase rider_breadcrumbs table
                val rows = pending.map { it.toSupabaseRow() }
                supabaseClient.postgrest["rider_breadcrumbs"].upsert(rows)

                // Purge-on-Finish: mark synced then delete
                val ids = pending.map { it.id }
                breadcrumbDao.markSynced(ids)
                breadcrumbDao.deleteSynced()

                Timber.i("Breadcrumb flush complete: %d synced and purged", pending.size)
            } catch (e: Exception) {
                Timber.e(e, "Breadcrumb flush failed -- will retry on next signal recovery")
                // Do NOT mark as synced -- Room retains them for the next attempt
            }
        }
    }
}

/** Supabase REST-serialisable row matching the `rider_breadcrumbs` table schema. */
@Serializable
private data class BreadcrumbRow(
    val id: String,
    val rider_id: String,
    val convoy_id: String?,
    val latitude: Double,
    val longitude: Double,
    val speed_kmh: Double,
    val timestamp_24h: String,
    val synced: Boolean = true,
)

private fun RiderBreadcrumbEntity.toSupabaseRow() = BreadcrumbRow(
    id = id,
    rider_id = riderId,
    convoy_id = convoyId,
    latitude = latitude,
    longitude = longitude,
    speed_kmh = speedKmh,
    timestamp_24h = timestamp24h,
    synced = true,
)
