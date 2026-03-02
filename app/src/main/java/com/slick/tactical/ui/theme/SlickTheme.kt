package com.slick.tactical.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// ─── Tactical OLED Color Tokens ──────────────────────────────────────────────
// These are HARDCODED. Never use Material You dynamic colors in-flight.
// Dynamic colors from wallpaper can produce illegible pastels in direct Queensland sun.

object SlickColors {
    /** True OLED black. Pixels physically off -- reduces thermal load and battery drain. */
    val Void = Color(0xFF000000)

    /** Near-black surface for cards and overlays. */
    val Surface = Color(0xFF121212)

    /** Deep orange. Critical hazards, SOS countdown, Zone 3 tap targets. */
    val Alert = Color(0xFFFF5722)

    /** Cyan. GripMatrix rain gradient, wind vectors. Cuts through dark backgrounds. */
    val Wash = Color(0xFF00E5FF)

    /** Pure white. Primary telemetry text: speed, ETA, distance. */
    val DataPrimary = Color(0xFFFFFFFF)

    /** Grey. Secondary labels, unit suffixes. */
    val DataSecondary = Color(0xFF9E9E9E)

    /** Dark orange. Zone 3 secondary actions. */
    val AlertDark = Color(0xFFBF360C)

    /** Green. "YES" votes in Democratic Detour. */
    val VoteYes = Color(0xFF2E7D32)

    /** Dark grey. "NO" votes in Democratic Detour. */
    val VoteNo = Color(0xFF424242)
}

// ─── Tactical OLED Dark Scheme (In-Flight) ───────────────────────────────────

private val TacticalDarkScheme = darkColorScheme(
    primary = SlickColors.Alert,
    onPrimary = SlickColors.Void,
    primaryContainer = SlickColors.AlertDark,
    onPrimaryContainer = SlickColors.DataPrimary,
    secondary = SlickColors.Wash,
    onSecondary = SlickColors.Void,
    background = SlickColors.Void,
    onBackground = SlickColors.DataPrimary,
    surface = SlickColors.Surface,
    onSurface = SlickColors.DataPrimary,
    surfaceVariant = SlickColors.Surface,
    onSurfaceVariant = SlickColors.DataSecondary,
    error = SlickColors.Alert,
    onError = SlickColors.Void,
)

// ─── Direct Sun Mode (Lux Sensor Auto-Inversion) ─────────────────────────────
// When Sensor.TYPE_LIGHT hits max lux (phone in direct Australian sun),
// hard-invert to white background / black text to maintain legibility.

private val DirectSunLightScheme = lightColorScheme(
    primary = Color(0xFFD84315),   // Dark orange on white
    onPrimary = Color.White,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color(0xFFF5F5F5),
    onSurface = Color.Black,
    secondary = Color(0xFF006064), // Dark cyan
    onSecondary = Color.White,
    error = Color(0xFFD84315),
    onError = Color.White,
)

// ─── Typography ───────────────────────────────────────────────────────────────
// Monospace for ALL telemetry numbers. Prevents horizontal jitter at speed.
// SansSerif digits have variable widths ('1' narrower than '8') -- illegible when vibrating.

private val TacticalTypography = Typography(
    // Zone 1: Speed, ETA -- large Monospace
    displayLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 36.sp,
    ),
    // Zone 1: Turn distance, secondary telemetry
    displayMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 28.sp,
    ),
    // Zone 3 button labels -- SansSerif OK for button text
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 18.sp,
        letterSpacing = 1.5.sp,
    ),
    // Node labels, convoy badges
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
    ),
    // Secondary labels -- MINIMUM 14sp on HUD (Jobs Protocol)
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
    ),
)

// ─── Theme Composable ─────────────────────────────────────────────────────────

/**
 * The SLICK tactical theme.
 *
 * Applies the Tactical OLED dark scheme by default.
 * Switches to [DirectSunLightScheme] when [isDirectSunMode] is true
 * (driven by Sensor.TYPE_LIGHT lux saturation in the calling screen).
 *
 * Status bar and navigation bar are forced to [SlickColors.Void] to
 * blend seamlessly with the OLED background.
 */
@Composable
fun SlickTheme(
    isDirectSunMode: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (isDirectSunMode) DirectSunLightScheme else TacticalDarkScheme
    val statusBarColor = if (isDirectSunMode) Color.White else SlickColors.Void

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            runCatching {
                val window = (view.context as Activity).window
                window.statusBarColor = statusBarColor.toArgb()
                window.navigationBarColor = SlickColors.Void.toArgb()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = isDirectSunMode
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = TacticalTypography,
        content = content,
    )
}
