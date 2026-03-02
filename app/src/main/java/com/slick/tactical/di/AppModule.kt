package com.slick.tactical.di

import android.content.Context
import com.google.android.gms.location.LocationServices
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import com.slick.tactical.BuildConfig
import javax.inject.Singleton

/**
 * Application-scoped singleton dependencies.
 *
 * Provides: Supabase client, Ktor HTTP client, FusedLocationProviderClient.
 * All network clients share the same Ktor engine to minimize APK size and memory.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSupabaseClient(): SupabaseClient = createSupabaseClient(
        supabaseUrl = BuildConfig.SUPABASE_URL,
        supabaseKey = BuildConfig.SUPABASE_ANON_KEY,
    ) {
        install(Postgrest)
        install(Realtime)
        install(Auth)
    }

    @Provides
    @Singleton
    fun provideKtorHttpClient(): HttpClient = HttpClient(Android) {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                },
            )
        }
        if (BuildConfig.ENABLE_DEBUG_LOGGING) {
            install(Logging) {
                level = LogLevel.HEADERS  // BODY logging risks PII -- headers only
            }
        }
    }

    @Provides
    @Singleton
    fun provideFusedLocationClient(@ApplicationContext context: Context) =
        LocationServices.getFusedLocationProviderClient(context)
}
