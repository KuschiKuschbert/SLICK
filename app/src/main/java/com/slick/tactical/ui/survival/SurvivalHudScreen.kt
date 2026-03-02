package com.slick.tactical.ui.survival

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.slick.tactical.service.OperationalState
import com.slick.tactical.ui.theme.SlickColors

/**
 * Survival Mode HUD -- activated when battery < 15%.
 *
 * OLED black background. Map suspended. GPU rendering killed.
 * Only critical navigation data remains.
 *
 * Tablet adaptation: font sizes scale up on wide screens via [BoxWithConstraints].
 * All content padded within [WindowInsets.safeDrawing] to avoid notches and rounded corners.
 */
@Composable
fun SurvivalHudScreen(
    viewModel: SurvivalViewModel = hiltViewModel(),
    onRestoreFullMode: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    if (state.operationalMode == OperationalState.FULL_TACTICAL) {
        onRestoreFullMode()
        return
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val isTablet = maxWidth >= 600.dp

        // Scale up all fonts on tablets for even better legibility
        val arrowFontSize = if (isTablet) 160 else 120
        val distanceFontSize = if (isTablet) 72 else 56
        val speedFontSize = if (isTablet) 64 else 48
        val labelFontSize = if (isTablet) 18 else 14
        val smallFontSize = if (isTablet) 16 else 12

        Column(
            modifier = Modifier
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            // Directional arrow: the single most important piece of information
            Text(
                text = state.nextTurnArrow,
                color = SlickColors.DataPrimary,
                fontSize = arrowFontSize.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )

            // Distance to next manoeuvre
            Text(
                text = "${state.nextTurnDistanceMetres} m",
                color = SlickColors.DataPrimary,
                fontSize = distanceFontSize.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )

            // Current speed
            Text(
                text = "${state.speedKmh.toInt()} km/h",
                color = SlickColors.DataPrimary,
                fontSize = speedFontSize.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )

            // Battery gauge
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "SURVIVAL MODE  —  ${state.batteryPercent}% BATTERY",
                    color = SlickColors.Alert,
                    fontSize = labelFontSize.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { state.batteryPercent / 100f },
                    modifier = Modifier.fillMaxWidth(if (isTablet) 0.6f else 1f),
                    color = SlickColors.Alert,
                    trackColor = SlickColors.Surface,
                )
            }

            Text(
                text = "BLE Heartbeat Active",
                color = SlickColors.DataSecondary,
                fontSize = smallFontSize.sp,
            )
        }
    }
}
