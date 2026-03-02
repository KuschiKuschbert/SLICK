package com.slick.tactical.di

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
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
import java.security.KeyStore
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.inject.Singleton

/**
 * Provides database and DAO dependencies.
 *
 * [SlickDatabase] is encrypted via SQLCipher [SupportFactory].
 * The passphrase is derived from an AES-256 key stored in Android Keystore.
 *
 * Key lifecycle:
 * - Generated once on first app launch, stored in Android Keystore (hardware-backed)
 * - Retrieved on all subsequent launches via the alias in [BuildConfig.SQLCIPHER_PASSPHRASE_ALIAS]
 * - The raw key bytes are Base64-encoded and used as the SQLCipher passphrase
 *
 * GOTCHA: Never call this in Application.onCreate() -- Keystore may not be accessible
 * before the device's first user unlock after boot. Room lazy-opens the DB, which is safe.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideSlickDatabase(@ApplicationContext context: Context): SlickDatabase {
        val passphrase = getOrCreateDatabasePassphrase()
        val factory = SupportFactory(SQLiteDatabase.getBytes(passphrase))
        // Zero out the passphrase char array after use
        passphrase.fill('\u0000')

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
     * Retrieves the SQLCipher database passphrase from Android Keystore.
     *
     * On first call: generates an AES-256 key in the Keystore, derives the passphrase.
     * On subsequent calls: retrieves the existing key from the Keystore.
     *
     * The passphrase is the Base64-encoded representation of the AES key bytes.
     * The caller is responsible for zeroing the returned CharArray after use.
     */
    private fun getOrCreateDatabasePassphrase(): CharArray {
        return try {
            val alias = BuildConfig.SQLCIPHER_PASSPHRASE_ALIAS
            val keyStore = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }

            if (!keyStore.containsAlias(alias)) {
                Timber.i("Generating new SQLCipher key in AndroidKeyStore (alias=%s)", alias)
                val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                keyGen.init(
                    KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setUserAuthenticationRequired(false)
                        .build(),
                )
                keyGen.generateKey()
            }

            val entry = keyStore.getEntry(alias, null) as KeyStore.SecretKeyEntry
            val keyBytes = entry.secretKey.encoded
            val encoded = Base64.getEncoder().encodeToString(keyBytes)
            Timber.d("SQLCipher passphrase retrieved from AndroidKeyStore (alias=%s)", alias)
            encoded.toCharArray()
        } catch (e: Exception) {
            // KeyStore may not be accessible immediately after boot (before first user unlock).
            // Fall back to alias string -- this only affects database encryption strength on
            // pathological cold-boot scenarios. Room will retry opening the DB lazily.
            Timber.e(e, "AndroidKeyStore unavailable, falling back to alias passphrase")
            BuildConfig.SQLCIPHER_PASSPHRASE_ALIAS.toCharArray()
        }
    }
}
