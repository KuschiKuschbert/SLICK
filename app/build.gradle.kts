plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.protobuf)
    alias(libs.plugins.secrets)
}

android {
    namespace = "com.slick.tactical"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.slick.tactical"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Required for JUnit 5 on Android
        testInstrumentationRunnerArguments["runnerBuilder"] =
            "de.mannodermaus.junit5.AndroidJUnit5Builder"
    }

    buildTypes {
        // ── Production release ────────────────────────────────────────────────
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            buildConfigField("boolean", "ENABLE_DEBUG_LOGGING", "false")
            buildConfigField("boolean", "ENABLE_CRASH_REPORTING", "true")
        }

        // ── Staging / internal testing ────────────────────────────────────────
        // Like release (minified) but with verbose Timber logs AND crash reporting.
        // Use for TestFlight-equivalent builds given to test riders.
        create("staging") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            buildConfigField("boolean", "ENABLE_DEBUG_LOGGING", "true")
            buildConfigField("boolean", "ENABLE_CRASH_REPORTING", "true")
            versionNameSuffix = "-staging"
        }

        // ── Local debug ───────────────────────────────────────────────────────
        debug {
            isDebuggable = true
            buildConfigField("boolean", "ENABLE_DEBUG_LOGGING", "true")
            buildConfigField("boolean", "ENABLE_CRASH_REPORTING", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // JUnit 5 support
    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }

    // JUnit 5 JARs all contain META-INF/LICENSE.md -- exclude duplicates so
    // the androidTest instrumentation APK can be assembled by Android Studio.
    packaging {
        resources {
            excludes += listOf(
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/NOTICE.md",
                "META-INF/NOTICE",
                "META-INF/LICENSE",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/*.kotlin_module",
            )
        }
    }
}

// Protobuf configuration
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") { option("lite") }
                create("kotlin") { option("lite") }
            }
        }
    }
}

// Secrets Gradle Plugin -- reads local.properties and injects into BuildConfig
secrets {
    propertiesFileName = "local.properties"
    defaultPropertiesFileName = "local.defaults.properties"
    ignoreList.add("sdk.dir")
    // OPEN_METEO_API_KEY is empty on the free tier -- exclude from BuildConfig to avoid empty value compile errors
    ignoreList.add("OPEN_METEO_API_KEY")
    // MAP_STYLE_URL may be the asset:// scheme which the plugin strips -- exclude it
    ignoreList.add("MAP_STYLE_URL")
    // SENTRY_DSN may be empty in development -- exclude to avoid compile errors
    ignoreList.add("SENTRY_DSN")
}

dependencies {
    // Compose BOM -- manages all Compose versions
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    debugImplementation(libs.compose.ui.tooling)

    // Orbit MVI
    implementation(libs.orbit.core)
    implementation(libs.orbit.compose)

    // Hilt DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Room + SQLCipher
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.sqlcipher.android)
    implementation(libs.sqlite.ktx)

    // MapLibre Native
    implementation(libs.maplibre.android.sdk)

    // Supabase
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.realtime)
    implementation(libs.supabase.auth)

    // Ktor (HTTP client -- shared engine with Supabase)
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.logging)

    // Google Nearby Connections (P2P mesh)
    implementation(libs.play.services.nearby)

    // Location + Activity Recognition
    implementation(libs.play.services.location)

    // Protobuf
    implementation(libs.protobuf.kotlin.lite)

    // Kotlin Serialization
    implementation(libs.kotlinx.serialization.json)

    // Android Keystore + Security
    implementation(libs.security.crypto)

    // WorkManager (GarageSyncWorker for PMTiles updates)
    implementation(libs.work.runtime.ktx)

    // Logging + Crash Reporting
    implementation(libs.timber)
    implementation(libs.sentry.android)

    // QR code generation (ZXing core -- pure Java, no KMP, works everywhere)
    implementation(libs.zxing.core)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // Testing -- JUnit 5
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.junit.jupiter.params)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.orbit.test)

    // qrose removed: KMP library incompatible with pure Android project. QR uses ZXing core.

    // CameraX + MLKit for Join Convoy QR scanning
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mlkit.barcode)

    // DataStore Preferences (settings persistence)
    implementation(libs.datastore.preferences)

    // Android Instrumented Tests
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
