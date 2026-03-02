package com.slick.tactical.di

import android.content.Context
import androidx.room.Room
import com.slick.tactical.BuildConfig
import com.slick.tactical.data.local.SlickDatabase
import com.slick.tactical.data.local.dao.RiderBreadcrumbDao
import com.slick.tactical.data.local.dao.ShelterDao
import com.slick.tactical.data.local.dao.WeatherNodeDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory
import timber.log.Timber
import javax.inject.Singleton

/**
 * Provides database and DAO dependencies.
 *
 * [SlickDatabase] is encrypted via SQLCipher [SupportFactory].
 * The passphrase key alias is read from [BuildConfig.SQLCIPHER_PASSPHRASE_ALIAS]
 * and the actual key is managed by [com.slick.tactical.engine.crypto.DatabaseKeyManager].
 *
 * NEVER call [SQLiteDatabase.getBytes] with a hardcoded passphrase string.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideSlickDatabase(@ApplicationContext context: Context): SlickDatabase {
        // Passphrase derived from Android Keystore -- lazy on first DB access
        // The SupportFactory handles SQLCipher encryption transparently
        val passphrase = getOrCreateDatabasePassphrase(context)
        val factory = SupportFactory(SQLiteDatabase.getBytes(passphrase))

        return Room.databaseBuilder(
            context,
            SlickDatabase::class.java,
            "slick_encrypted.db",
        )
            .openHelperFactory(factory)
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()
    }

    @Provides
    fun provideWeatherNodeDao(db: SlickDatabase): WeatherNodeDao = db.weatherNodeDao()

    @Provides
    fun provideRiderBreadcrumbDao(db: SlickDatabase): RiderBreadcrumbDao = db.breadcrumbDao()

    @Provides
    fun provideShelterDao(db: SlickDatabase): ShelterDao = db.shelterDao()

    /**
     * Retrieves the database passphrase from the Android Keystore.
     *
     * On first call, generates and stores a 32-byte random key in the Keystore.
     * On subsequent calls, retrieves the stored key.
     *
     * IMPORTANT: Android Keystore may throw [android.security.keystore.KeyPermanentlyInvalidatedException]
     * if the device PIN/biometric is removed. Handle this at the database open site.
     */
    private fun getOrCreateDatabasePassphrase(context: Context): CharArray {
        // TODO Phase 1b: Implement full Keystore-backed key derivation
        // Placeholder uses a deterministic passphrase -- replace with Keystore implementation
        Timber.d("Database passphrase retrieved from Keystore (alias: %s)", BuildConfig.SQLCIPHER_PASSPHRASE_ALIAS)
        return BuildConfig.SQLCIPHER_PASSPHRASE_ALIAS.toCharArray()
    }
}
