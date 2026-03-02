package com.slick.tactical.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.slick.tactical.data.local.dao.RiderBreadcrumbDao
import com.slick.tactical.data.local.dao.RouteDao
import com.slick.tactical.data.local.dao.ShelterDao
import com.slick.tactical.data.local.dao.WeatherNodeDao
import com.slick.tactical.data.local.entity.RiderBreadcrumbEntity
import com.slick.tactical.data.local.entity.RouteEntity
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
 *
 * Version history:
 * - v1: WeatherNodeEntity, RiderBreadcrumbEntity, ShelterEntity
 * - v2: Added RouteEntity (route_cache table) for route persistence across process death
 */
@Database(
    entities = [
        WeatherNodeEntity::class,
        RiderBreadcrumbEntity::class,
        ShelterEntity::class,
        RouteEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class SlickDatabase : RoomDatabase() {
    abstract fun weatherNodeDao(): WeatherNodeDao
    abstract fun breadcrumbDao(): RiderBreadcrumbDao
    abstract fun shelterDao(): ShelterDao
    abstract fun routeDao(): RouteDao

    companion object {
        /**
         * v1 → v2: Add route_cache table for persisting the active route polyline and maneuvers.
         * This allows the In-Flight HUD to restore after process death without re-syncing weather.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `route_cache` (
                        `id` TEXT NOT NULL,
                        `polylineJson` TEXT NOT NULL,
                        `maneuversJson` TEXT NOT NULL,
                        `originLat` REAL NOT NULL,
                        `originLon` REAL NOT NULL,
                        `destinationLat` REAL NOT NULL,
                        `destinationLon` REAL NOT NULL,
                        `totalDistanceKm` REAL NOT NULL,
                        `corridorId` TEXT NOT NULL,
                        `savedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
            }
        }
    }
}
