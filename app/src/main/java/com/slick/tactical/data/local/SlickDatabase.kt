package com.slick.tactical.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.slick.tactical.data.local.dao.RiderBreadcrumbDao
import com.slick.tactical.data.local.dao.ShelterDao
import com.slick.tactical.data.local.dao.WeatherNodeDao
import com.slick.tactical.data.local.entity.RiderBreadcrumbEntity
import com.slick.tactical.data.local.entity.ShelterEntity
import com.slick.tactical.data.local.entity.WeatherNodeEntity

/**
 * SLICK encrypted local database (SQLCipher AES-256).
 *
 * This is the single source of truth for all ride data.
 * The UI layer only reads from this database via DAOs.
 * Network clients write to this database after successful API calls.
 *
 * Encryption is applied at the [SupportFactory] level in [com.slick.tactical.di.DataModule].
 * Schema version must be incremented with a migration for every structural change.
 */
@Database(
    entities = [
        WeatherNodeEntity::class,
        RiderBreadcrumbEntity::class,
        ShelterEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class SlickDatabase : RoomDatabase() {
    abstract fun weatherNodeDao(): WeatherNodeDao
    abstract fun breadcrumbDao(): RiderBreadcrumbDao
    abstract fun shelterDao(): ShelterDao
}
