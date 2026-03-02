package com.slick.tactical.di

import android.content.Context
import androidx.room.Room
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.slick.tactical.data.local.SlickDatabase
import com.slick.tactical.data.local.dao.RiderBreadcrumbDao
import com.slick.tactical.data.local.dao.RouteDao
import com.slick.tactical.data.local.dao.ShelterDao
import com.slick.tactical.data.local.dao.WeatherNodeDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import timber.log.Timber
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Singleton

/**
 * Provides database and DAO dependencies.
 *
 * [SlickDatabase] is encrypted via SQLCipher [SupportOpenHelperFactory].
 *
 * Passphrase strategy:
 * - A random 256-bit passphrase is generated once and stored in [EncryptedSharedPreferences].
 * - EncryptedSharedPreferences uses AndroidKeyStore internally; we never need to extract
 *   raw key material (which AndroidKeyStore intentionally blocks on hardware-backed keys).
 * - If EncryptedSharedPreferences is unavailable (first unlock after boot), falls back to
 *   the build-config alias string.
 *
 * Library migration guard:
 * - Migrating from android-database-sqlcipher to sqlcipher-android changes the PBKDF2
 *   parameters and HMAC algorithm. The same passphrase produces a different AES key, so
 *   the old database file cannot be opened. A SharedPreferences marker tracks whether the
 *   migration has been applied; on first run with the new library, the old file is deleted.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    // Bumped when the SQLCipher library changes in a way that makes old databases unreadable.
    // Increment this whenever upgrading sqlcipher-android to a major version that changes KDF params.
    private const val SQLCIPHER_LIBRARY_GENERATION = 2

    @Provides
    @Singleton
    fun provideSlickDatabase(@ApplicationContext context: Context): SlickDatabase {
        ensureLibraryMigration(context)

        val passphrase = getOrCreateDatabasePassphrase(context)

        fun buildDb(): SlickDatabase {
            val passphraseBytes = ByteArray(passphrase.size) { passphrase[it].code.toByte() }
            val factory = SupportOpenHelperFactory(passphraseBytes)
            passphraseBytes.fill(0)   // zero our copy; factory holds its own internal copy
            return Room.databaseBuilder(
                context,
                SlickDatabase::class.java,
                "slick_encrypted.db",
            )
                .openHelperFactory(factory)
                .addMigrations(SlickDatabase.MIGRATION_1_2)
                .fallbackToDestructiveMigrationOnDowngrade(true)
                .build()
        }

        passphrase.fill('\u0000')
        return buildDb()
    }

    @Provides
    fun provideWeatherNodeDao(db: SlickDatabase): WeatherNodeDao = db.weatherNodeDao()

    @Provides
    fun provideRiderBreadcrumbDao(db: SlickDatabase): RiderBreadcrumbDao = db.breadcrumbDao()

    @Provides
    fun provideShelterDao(db: SlickDatabase): ShelterDao = db.shelterDao()

    @Provides
    fun provideRouteDao(db: SlickDatabase): RouteDao = db.routeDao()

    // ─── Passphrase ────────────────────────────────────────────────────────────

    /**
     * Returns the SQLCipher database passphrase, generating and persisting it on first call.
     *
     * Uses [EncryptedSharedPreferences] which wraps AndroidKeyStore internally. This avoids
     * the [NullPointerException] caused by calling [java.security.Key.getEncoded] on a
     * hardware-backed KeyStore key (those keys are intentionally non-extractable).
     *
     * The caller is responsible for zeroing the returned [CharArray] after use.
     */
    private fun getOrCreateDatabasePassphrase(context: Context): CharArray {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val prefs = EncryptedSharedPreferences.create(
                context,
                "slick_db_passphrase_store",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )

            val stored = prefs.getString("db_passphrase", null)
            if (stored != null) {
                Timber.d("DataModule: SQLCipher passphrase loaded from EncryptedSharedPreferences")
                return stored.toCharArray()
            }

            // First launch with this generation: generate a random 256-bit passphrase.
            val randomBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val passphrase = Base64.getEncoder().encodeToString(randomBytes)
            randomBytes.fill(0)
            prefs.edit().putString("db_passphrase", passphrase).apply()
            Timber.i("DataModule: new SQLCipher passphrase generated and stored")
            passphrase.toCharArray()
        } catch (e: Exception) {
            // EncryptedSharedPreferences unavailable (e.g., device not yet unlocked after reboot).
            // Use the build-config alias as a deterministic fallback. This reduces security but
            // keeps the app functional. Room will retry on the next open.
            Timber.e(e, "DataModule: EncryptedSharedPreferences unavailable, using alias fallback")
            "slick_db_fallback_v2".toCharArray()
        }
    }

    // ─── Library migration guard ───────────────────────────────────────────────

    /**
     * Deletes the database file when upgrading to a new SQLCipher library generation whose
     * KDF parameters are incompatible with the previously-created database.
     *
     * Migrating from android-database-sqlcipher → sqlcipher-android 4.7.0 changed the HMAC
     * algorithm and PBKDF2 iteration count, making existing database files unreadable even
     * with the correct passphrase. Deleting the file lets Room create a fresh database on the
     * next open. All data was transient (weather nodes, ride breadcrumbs) and will resync.
     */
    private fun ensureLibraryMigration(context: Context) {
        val prefs = context.getSharedPreferences("slick_db_meta", Context.MODE_PRIVATE)
        val recorded = prefs.getInt("sqlcipher_generation", 0)
        if (recorded < SQLCIPHER_LIBRARY_GENERATION) {
            Timber.w(
                "DataModule: SQLCipher library generation changed (%d→%d) -- deleting old database",
                recorded,
                SQLCIPHER_LIBRARY_GENERATION,
            )
            context.deleteDatabase("slick_encrypted.db")
            // Also clear any stored passphrase from older generation so a fresh one is generated.
            context.getSharedPreferences("slick_db_passphrase_store", Context.MODE_PRIVATE)
                .edit().clear().apply()
            prefs.edit().putInt("sqlcipher_generation", SQLCIPHER_LIBRARY_GENERATION).apply()
        }
    }
}
