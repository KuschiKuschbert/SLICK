package com.slick.tactical.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.slick.tactical.data.local.entity.ShelterEntity
import kotlinx.coroutines.flow.Flow

/** Data access for take-cover and refuel POIs cached from Overpass API. */
@Dao
interface ShelterDao {

    /**
     * Observe all shelters for the current route corridor as a live [Flow].
     * Emits a new list whenever shelters are inserted or cleared.
     * Collected by [com.slick.tactical.ui.inflight.InFlightViewModel] to render shelter
     * markers on the Zone 2 map and surface the nearest haven to hazard nodes.
     */
    @Query("SELECT * FROM shelters WHERE routeCorridorId = :corridorId ORDER BY type ASC")
    fun observeSheltersForCorridor(corridorId: String): Flow<List<ShelterEntity>>

    /** Single-shot query used by [com.slick.tactical.engine.haven.EmergencyHavenLocator]. */
    @Query("SELECT * FROM shelters WHERE routeCorridorId = :corridorId")
    suspend fun getSheltersForCorridor(corridorId: String): List<ShelterEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertShelters(shelters: List<ShelterEntity>)

    @Query("DELETE FROM shelters WHERE routeCorridorId = :corridorId")
    suspend fun clearCorridor(corridorId: String)
}
