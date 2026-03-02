package com.slick.tactical.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.slick.tactical.data.local.entity.RouteEntity

/**
 * Data access for the persisted route snapshot.
 *
 * There is always at most one row — id "current_route" is always replaced on each new sync.
 * This is what allows the In-Flight HUD to restore the polyline and maneuvers after the app
 * is killed and reopened without the rider having to sync weather again.
 */
@Dao
interface RouteDao {

    /** Returns the last saved route, or null if no route has been synced yet. */
    @Query("SELECT * FROM route_cache WHERE id = 'current_route' LIMIT 1")
    suspend fun getLastRoute(): RouteEntity?

    /** Upserts the current route -- called after every successful weather sync. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveRoute(route: RouteEntity)
}
