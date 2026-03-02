package com.slick.tactical.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.slick.tactical.data.local.entity.ShelterEntity

/** Data access for emergency haven POIs cached from Overpass API. */
@Dao
interface ShelterDao {

    /** Returns all shelters for the current route corridor, ordered by type priority. */
    @Query("SELECT * FROM shelters WHERE routeCorridorId = :corridorId")
    suspend fun getSheltersForCorridor(corridorId: String): List<ShelterEntity>

    /** Upserts POIs from Overpass API -- called during pre-flight sync. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertShelters(shelters: List<ShelterEntity>)

    /** Clears shelters for a corridor before inserting fresh data. */
    @Query("DELETE FROM shelters WHERE routeCorridorId = :corridorId")
    suspend fun clearCorridor(corridorId: String)
}
