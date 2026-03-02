package com.slick.tactical.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single GPS breadcrumb recorded while the rider is offline.
 *
 * Breadcrumbs accumulate in the local database during dead zones.
 * When signal is recovered, [com.slick.tactical.engine.signal.SignalRecoveryReceiver]
 * flushes the batch to Supabase and clears synced entries.
 *
 * @property id UUID generated locally, used as Supabase primary key after sync
 * @property riderId Ephemeral UUID for this convoy session (not the user's real ID)
 * @property convoyId The active convoy session ID (null if riding solo)
 * @property latitude GPS latitude
 * @property longitude GPS longitude
 * @property speedKmh Speed in km/h at time of recording
 * @property timestamp24h Recording time in HH:mm:ss (24h)
 * @property synced True after successful upload to Supabase
 */
@Entity(tableName = "rider_breadcrumbs")
data class RiderBreadcrumbEntity(
    @PrimaryKey val id: String,
    val riderId: String,
    val convoyId: String?,
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Double,
    val timestamp24h: String,
    val synced: Boolean = false,
)
