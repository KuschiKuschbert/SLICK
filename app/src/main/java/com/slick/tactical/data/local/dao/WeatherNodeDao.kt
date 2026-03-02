package com.slick.tactical.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.slick.tactical.data.local.entity.WeatherNodeEntity
import kotlinx.coroutines.flow.Flow

/** Data access for [WeatherNodeEntity]. The UI observes [observeAllNodes] via Flow. */
@Dao
interface WeatherNodeDao {

    /** Emits the full node list whenever any node changes. UI collects this to update the map. */
    @Query("SELECT * FROM weather_nodes ORDER BY estimatedArrival24h ASC")
    fun observeAllNodes(): Flow<List<WeatherNodeEntity>>

    /** Returns nodes ordered by arrival time. Used by RouteForecaster for gradient computation. */
    @Query("SELECT * FROM weather_nodes ORDER BY estimatedArrival24h ASC")
    suspend fun getAllNodes(): List<WeatherNodeEntity>

    /** Upserts nodes -- replaces stale data from the previous sync. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNodes(nodes: List<WeatherNodeEntity>)

    /** Returns the number of persisted weather nodes. Used to restore sync state on PreFlight re-open. */
    @Query("SELECT COUNT(*) FROM weather_nodes")
    suspend fun getNodeCount(): Int

    /** Clears all nodes for the current route -- called before inserting a new route's data. */
    @Query("DELETE FROM weather_nodes")
    suspend fun clearAllNodes()
}
