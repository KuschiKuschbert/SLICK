package com.slick.tactical.ui.inflight

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.slick.tactical.service.OperationalState
import com.slick.tactical.ui.components.MassiveButton
import com.slick.tactical.ui.theme.SlickColors

/**
 * In-Flight HUD -- tactical command centre for 110 km/h riding.
 *
 * Three cognitive zones, screen-size adaptive:
 *
 * Zone 1 (top 20% / phone, 15% / tablet): Peripheral Header
 *   Monospace telemetry: next turn + distance, current speed, 24h ETA.
 *   Padded below the status bar / camera cutout via [WindowInsets.statusBars].
 *
 * Zone 2 (middle 50% / phone, 60% / tablet): Tactical Focus
 *   MapLibre render. Rider icon anchored at 55% from top.
 *   No inset padding -- map fills all the way to the screen edge for max situational awareness.
 *
 * Zone 3 (bottom 30% / phone, 25% / tablet): Interaction Deck
 *   Two massive glove-friendly buttons.
 *   Padded above the navigation bar via [WindowInsets.navigationBars].
 *
 * Tablet adaptation: [BoxWithConstraints] detects screen width > 600 dp and widens Zone 1/3.
 */
@Composable
fun InFlightHudScreen(
    viewModel: InFlightViewModel = hiltViewModel(),
    onSurvivalMode: () -> Unit,
    onBackToPreFlight: () -> Unit = {},
) {
    val uiState by viewModel.state.collectAsState()

    if (uiState.operationalMode == OperationalState.SURVIVAL_MODE) {
        onSurvivalMode()
        return
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(SlickColors.Void),
    ) {
        val isTablet = maxWidth >= 600.dp

        // Zone weight distribution adapts to screen size
        val zone1Weight = if (isTablet) 0.15f else 0.20f
        val zone2Weight = if (isTablet) 0.60f else 0.50f
        val zone3Weight = if (isTablet) 0.25f else 0.30f

        Column(modifier = Modifier.fillMaxSize()) {
            // Zone 1: Peripheral Header — padded below status bar / notch
            Zone1Header(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(zone1Weight)
                    .windowInsetsPadding(WindowInsets.statusBars),
                nextTurnDistanceM = uiState.nextTurnDistanceMetres,
                nextTurnArrow = uiState.nextTurnArrow,
                speedKmh = uiState.speedKmh,
                eta24h = uiState.eta24h,
                isTablet = isTablet,
            )

            // Zone 2: Map — intentionally edge-to-edge (no inset padding)
            Zone2MapWrapper(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(zone2Weight),
                viewModel = viewModel,
            )

            // Zone 3: Interaction Deck — padded above navigation bar
            Zone3InteractionDeck(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(zone3Weight)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                onMarkHazard = viewModel::onMarkHazard,
                onToggleMute = viewModel::onToggleMute,
                isMuted = uiState.isAudioMuted,
                isTablet = isTablet,
            )
        }
    }
}

/** Zone 1: Pure telemetry — designed to be read with a 0.5s glance at speed. */
@Composable
fun Zone1Header(
    modifier: Modifier = Modifier,
    nextTurnDistanceM: Int,
    nextTurnArrow: String,
    speedKmh: Double,
    eta24h: String,
    isTablet: Boolean = false,
) {
    val baseFontSize = if (isTablet) 34 else 28
    val speedFontSize = if (isTablet) 40 else 32
    val etaFontSize = if (isTablet) 26 else 20

    Row(
        modifier = modifier
            .background(SlickColors.Void)
            .padding(horizontal = if (isTablet) 32.dp else 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Next turn: directional arrow + distance
        Text(
            text = "$nextTurnArrow  ${nextTurnDistanceM}m",
            color = SlickColors.DataPrimary,
            fontSize = baseFontSize.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )

        // Speed: largest element — most critical at speed
        Text(
            text = "${speedKmh.toInt()} km/h",
            color = SlickColors.DataPrimary,
            fontSize = speedFontSize.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )

        // ETA: strict 24h format, no AM/PM
        Text(
            text = "ETA $eta24h",
            color = SlickColors.DataSecondary,
            fontSize = etaFontSize.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/** Zone 2: MapLibre render — delegates to Zone2Map.kt. */
@Composable
fun Zone2MapWrapper(
    modifier: Modifier = Modifier,
    viewModel: InFlightViewModel,
) {
    val state by viewModel.state.collectAsState()
    Zone2Map(
        modifier = modifier,
        weatherNodes = state.weatherNodes,
        riderLat = state.riderLat,
        riderLon = state.riderLon,
        riderBearing = state.riderBearingDeg,
        convoyRiders = state.convoyRiders,
    )
}

/** Zone 3: Two massive glove-friendly tap targets — nothing else. */
@Composable
fun Zone3InteractionDeck(
    modifier: Modifier = Modifier,
    onMarkHazard: () -> Unit,
    onToggleMute: () -> Unit,
    isMuted: Boolean,
    isTablet: Boolean = false,
) {
    val buttonHeight = if (isTablet) 80.dp else 64.dp
    val fontSize = if (isTablet) 18.sp else 14.sp

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MassiveButton(
            text = "MARK HAZARD",
            onClick = onMarkHazard,
            containerColor = SlickColors.Alert,
            contentColor = SlickColors.Void,
            modifier = Modifier
                .weight(1f)
                .height(buttonHeight),
            fontSize = fontSize,
        )

        MassiveButton(
            text = if (isMuted) "UNMUTE" else "AUDIO MUTE",
            onClick = onToggleMute,
            containerColor = if (isMuted) SlickColors.DataSecondary else SlickColors.Surface,
            contentColor = SlickColors.DataPrimary,
            modifier = Modifier
                .weight(1f)
                .height(buttonHeight),
            fontSize = fontSize,
        )
    }
}
