# SLICK ProGuard Rules
# Add project specific ProGuard rules here.

# Keep all Kotlin data classes used in serialization (Supabase/Protobuf)
-keep class com.slick.tactical.data.** { *; }
-keep class com.slick.tactical.data.proto.** { *; }

# Protobuf lite
-keep class com.google.protobuf.** { *; }
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }

# Supabase / Ktor
-keep class io.github.jan.supabase.** { *; }
-keep class io.ktor.** { *; }

# Kotlin Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep class kotlinx.serialization.** { *; }
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** serializer();
}

# Nearby Connections
-keep class com.google.android.gms.nearby.** { *; }

# MapLibre
-keep class org.maplibre.** { *; }

# Room / SQLCipher
-keep class androidx.room.** { *; }
-keep class net.sqlcipher.** { *; }

# Hilt
-keep class dagger.hilt.** { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }

# Timber (strip debug logs in release)
-assumenosideeffects class timber.log.Timber {
    public static *** d(...);
    public static *** v(...);
}

# Keep crash detection / IMU classes
-keep class com.slick.tactical.engine.imu.** { *; }
-keep class com.slick.tactical.engine.crypto.** { *; }
