package com.slick.tactical.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * Single shared DataStore instance for SLICK user preferences.
 * Declared once here so all ViewModels share the same underlying file and avoid
 * the DataStore multiple-instance-per-file warning.
 */
val Context.slickSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "slick_settings")

/**
 * Central constants for SLICK. No magic numbers anywhere in the codebase.
 * All values documented with units and rationale.
 */
object SlickConstants {

    // ─── Route / Weather ──────────────────────────────────────────────────────

    /** Distance between GripMatrix weather nodes, in kilometres. */
    const val NODE_INTERVAL_KM = 10.0

    /** Earth radius for Haversine formula, in kilometres. */
    const val EARTH_RADIUS_KM = 6371.0

    /** Maximum detour distance to an emergency haven (fuel/shelter), in kilometres. */
    const val MAX_DETOUR_RADIUS_KM = 15.0

    /** Twilight window around sunrise/sunset for macropod hazard elevation, in minutes. */
    const val TWILIGHT_WINDOW_MINUTES = 30L

    /** Sun elevation below which solar glare risk is active, in degrees. */
    const val SOLAR_GLARE_ELEVATION_THRESHOLD = 15.0

    /** Angular difference from route bearing at which solar glare is maximum, in degrees. */
    const val SOLAR_GLARE_BEARING_DELTA = 20.0

    /** Elevation gain above which topographic fuel drain is applied, in metres over 20km. */
    const val TOPOGRAPHIC_DRAIN_ELEVATION_THRESHOLD = 400.0

    /** Fuel range reduction factor for sustained climbs (15%). */
    const val TOPOGRAPHIC_FUEL_DRAIN_FACTOR = 0.85

    /** Minimum residual rainfall to classify as "wet surface", in mm. */
    const val ASPHALT_MEMORY_MIN_RAINFALL_MM = 0.2

    // ─── Battery / Thermal ────────────────────────────────────────────────────

    /** Battery level below which Survival Mode activates (%). */
    const val SURVIVAL_MODE_BATTERY_THRESHOLD = 15

    /** CPU temperature above which THROTTLED mode activates, in Celsius. */
    const val THERMAL_THROTTLE_CELSIUS = 40.0

    /** CPU temperature above which CRITICAL_LOW mode activates, in Celsius. */
    const val THERMAL_CRITICAL_CELSIUS = 45.0

    // ─── Convoy / P2P ─────────────────────────────────────────────────────────

    /** Convoy size above which >4 Protocol activates (clustering + throttling). */
    const val CONVOY_CLUSTERING_THRESHOLD = 4

    /** GeoJSON cluster radius for convoy badges in MapLibre, in pixels. */
    const val CONVOY_CLUSTER_RADIUS = 50

    /** Lead/Sweep rider broadcast interval (1Hz), in milliseconds. */
    const val LEAD_BROADCAST_INTERVAL_MS = 1_000L

    /** Pack rider broadcast interval in throttled mode (0.2Hz), in milliseconds. */
    const val PACK_BROADCAST_INTERVAL_MS = 5_000L

    /** BLE heartbeat interval in SURVIVAL_MODE, in milliseconds. */
    const val BLE_HEARTBEAT_INTERVAL_MS = 10_000L

    /** Maximum time to project a Ghost Pin before removing from map, in milliseconds. */
    const val GHOST_PIN_MAX_PROJECTION_MS = 5 * 60 * 1_000L  // 5 minutes

    // ─── GPS ──────────────────────────────────────────────────────────────────

    /** GPS polling interval in FULL_TACTICAL mode, in milliseconds. */
    const val GPS_FULL_INTERVAL_MS = 1_000L

    /** GPS hardware batch max wait in FULL_TACTICAL mode, in milliseconds. */
    const val GPS_BATCH_FULL_MS = 5_000L

    /** GPS polling interval in SURVIVAL_MODE, in milliseconds. */
    const val GPS_SURVIVAL_INTERVAL_MS = 30_000L

    /** Minimum displacement to trigger a GPS update, in metres. */
    const val GPS_MIN_DISPLACEMENT_METRES = 10f

    // ─── Safety / SOS ─────────────────────────────────────────────────────────

    /** SOS countdown duration after crash detection, in seconds. */
    const val SOS_COUNTDOWN_SECONDS = 60

    /** Deceleration threshold for crash detection: 100km/h to 0 in this many milliseconds. */
    const val CRASH_DECELERATION_WINDOW_MS = 1_500L

    // ─── UI ───────────────────────────────────────────────────────────────────

    /** Minimum font size anywhere on the In-Flight HUD, in sp. */
    const val HUD_MIN_FONT_SP = 14

    /** Zone 1 telemetry font size (speed, ETA), in sp. */
    const val HUD_TELEMETRY_FONT_SP = 32

    /** Zone 3 button minimum height for glove-friendly interaction, in dp. */
    const val ZONE3_BUTTON_HEIGHT_DP = 64

    /** Detour voting window before auto-dismiss, in seconds. */
    const val DETOUR_VOTE_WINDOW_SECONDS = 30

    // ─── Crypto ───────────────────────────────────────────────────────────────

    /** GCM authentication tag length, in bits. */
    const val AES_GCM_TAG_LENGTH_BITS = 128

    /** AES-GCM IV length, in bytes. */
    const val AES_GCM_IV_LENGTH_BYTES = 12
}
