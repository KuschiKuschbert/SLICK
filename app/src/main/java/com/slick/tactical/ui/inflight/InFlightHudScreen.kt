package com.slick.tactical.ui.inflight

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
 * In-Flight HUD -- the tactical command center for 110 km/h riding.
 *
 * Layout: Three cognitive zones (Jobs Protocol -- no clutter, massive targets):
 *
 * Zone 1 (top 20%): Peripheral Header
 *   Monospace telemetry: next turn arrow + distance, current speed, 24h ETA
 *
 * Zone 2 (middle 50%): Tactical Focus
 *   MapLibre render with GripMatrix gradient polyline, crosswind vectors, convoy badges
 *   Rider icon anchored at 55% from top (shows more road ahead)
 *
 * Zone 3 (bottom 30%): Interaction Deck
 *   Two massive glove-friendly buttons: MARK HAZARD and AUDIO MUTE
 *   Modal Democratic Detour overlay when a vote is active
 *
 * No deep menus. No small targets. Interaction is minimized while riding.
 */
@Composable
fun InFlightHudScreen(
    viewModel: InFlightViewModel = hiltViewModel(),
    onSurvivalMode: () -> Unit,
) {
    val uiState by viewModel.state.collectAsState()

    // If system transitions to SURVIVAL_MODE, navigate away
    if (uiState.operationalMode == OperationalState.SURVIVAL_MODE) {
        onSurvivalMode()
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SlickColors.Void),
    ) {
        // ── Zone 1: Peripheral Header (top 20%) ──────────────────────────────
        Zone1Header(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.20f),
            nextTurnDistanceM = uiState.nextTurnDistanceMetres,
            nextTurnArrow = uiState.nextTurnArrow,
            speedKmh = uiState.speedKmh,
            eta24h = uiState.eta24h,
        )

        // ── Zone 2: Tactical Focus (middle 50%) ──────────────────────────────
        Zone2MapWrapper(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.50f),
            viewModel = viewModel,
        )

        // ── Zone 3: Interaction Deck (bottom 30%) ────────────────────────────
        Zone3InteractionDeck(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.30f)
                .padding(8.dp),
            onMarkHazard = viewModel::onMarkHazard,
            onToggleMute = viewModel::onToggleMute,
            isMuted = uiState.isAudioMuted,
        )
    }
}

/** Zone 1: Pure telemetry header. Read with a split-second downward glance. */
@Composable
fun Zone1Header(
    modifier: Modifier = Modifier,
    nextTurnDistanceM: Int,
    nextTurnArrow: String,
    speedKmh: Double,
    eta24h: String,
) {
    Row(
        modifier = modifier
            .background(SlickColors.Void)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Next turn: arrow + distance
        Text(
            text = "$nextTurnArrow ${nextTurnDistanceM}m",
            color = SlickColors.DataPrimary,
            fontSize = 28.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )

        // Current speed -- largest, most critical
        Text(
            text = "${speedKmh.toInt()} km/h",
            color = SlickColors.DataPrimary,
            fontSize = 32.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )

        // 24h ETA -- strict format, no AM/PM
        Text(
            text = "ETA $eta24h",
            color = SlickColors.DataSecondary,
            fontSize = 20.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/** Zone 2: Real MapLibre render -- see Zone2Map.kt for full implementation */
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

/** Zone 3: Two massive glove-friendly tap targets. Nothing else. */
@Composable
fun Zone3InteractionDeck(
    modifier: Modifier = Modifier,
    onMarkHazard: () -> Unit,
    onToggleMute: () -> Unit,
    isMuted: Boolean,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // MARK HAZARD -- Alert orange, full weight
        MassiveButton(
            text = "MARK HAZARD",
            onClick = onMarkHazard,
            containerColor = SlickColors.Alert,
            contentColor = SlickColors.Void,
            modifier = Modifier
                .weight(1f)
                .height(64.dp),
        )

        // AUDIO MUTE -- Dark surface when active, grey when muted
        MassiveButton(
            text = if (isMuted) "UNMUTE" else "AUDIO MUTE",
            onClick = onToggleMute,
            containerColor = if (isMuted) SlickColors.DataSecondary else SlickColors.Surface,
            contentColor = SlickColors.DataPrimary,
            modifier = Modifier
                .weight(1f)
                .height(64.dp),
        )
    }
}
