package com.slick.tactical.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.slick.tactical.data.local.entity.RiderBreadcrumbEntity

/** Data access for offline GPS breadcrumbs. */
@Dao
interface RiderBreadcrumbDao {

    /** Records a new GPS breadcrumb during a dead zone. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBreadcrumb(breadcrumb: RiderBreadcrumbEntity)

    /** Returns all unsynced breadcrumbs -- flushed to Supabase on signal recovery. */
    @Query("SELECT * FROM rider_breadcrumbs WHERE synced = 0 ORDER BY timestamp24h ASC")
    suspend fun getPendingBreadcrumbs(): List<RiderBreadcrumbEntity>

    /** Marks all provided IDs as synced after successful Supabase upload. */
    @Query("UPDATE rider_breadcrumbs SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    /** Purges all synced breadcrumbs -- called after successful flush. Ramsay Protocol: clean up. */
    @Query("DELETE FROM rider_breadcrumbs WHERE synced = 1")
    suspend fun deleteSynced()

    /** Returns count of pending breadcrumbs -- used for logging. */
    @Query("SELECT COUNT(*) FROM rider_breadcrumbs WHERE synced = 0")
    suspend fun getPendingCount(): Int
}
