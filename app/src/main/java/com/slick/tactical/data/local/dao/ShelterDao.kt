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
     * Observe shelters for the current route corridor as a live [Flow].
     * Emits a new list whenever shelters are inserted or cleared.
     * Collected by [com.slick.tactical.ui.inflight.InFlightViewModel] to render shelter
     * markers on the Zone 2 map and surface the nearest haven to hazard nodes.
     *
     * LIMIT 100: SQLCipher 4.7.0 has an unaligned-memory-access bug (SIGBUS BUS_ADRERR) when
     * reading large result sets through CursorWindow while the .so is memory-mapped directly
     * from the APK (android:extractNativeLibs="false"). 100 shelters is sufficient for any
     * corridor — the Overpass bbox typically returns 250–300 POIs but only ~40–60 are directly
     * on-route. Priority ordering (fuel/rest_area first, then pubs/cafes, then others) ensures
     * the most useful stops appear within the limit.
     */
    @Query("""
        SELECT * FROM shelters 
        WHERE routeCorridorId = :corridorId 
        ORDER BY 
            CASE type 
                WHEN 'FUEL' THEN 0 
                WHEN 'REST_AREA' THEN 1 
                WHEN 'PUB' THEN 2 
                WHEN 'CAFE' THEN 3 
                WHEN 'HOTEL' THEN 4 
                ELSE 5 
            END ASC
        LIMIT 100
    """)
    fun observeSheltersForCorridor(corridorId: String): Flow<List<ShelterEntity>>

    /** Single-shot query used by [com.slick.tactical.engine.haven.EmergencyHavenLocator]. */
    @Query("SELECT * FROM shelters WHERE routeCorridorId = :corridorId")
    suspend fun getSheltersForCorridor(corridorId: String): List<ShelterEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertShelters(shelters: List<ShelterEntity>)

    @Query("DELETE FROM shelters WHERE routeCorridorId = :corridorId")
    suspend fun clearCorridor(corridorId: String)
}
