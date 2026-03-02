package com.slick.tactical.ui.survival

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.slick.tactical.ui.components.MassiveButton
import com.slick.tactical.ui.theme.SlickColors

/**
 * Survival Mode HUD -- activated when battery drops below 15%.
 *
 * Map is suspended. GPU rendering killed. Screen pitch black.
 * Only critical navigation data remains:
 *
 * - Massive directional arrow (next turn)
 * - Distance to next manoeuvre (Monospace)
 * - Current speed (Monospace)
 * - Battery gauge
 *
 * When power is restored (rider plugs into bike USB), transitions back to [InFlightHudScreen].
 *
 * Jobs Protocol: ruthless simplification. The rider needs one thing -- where to turn next.
 * Musk Protocol: OLED black pixels are physically off -- zero thermal load.
 */
@Composable
fun SurvivalHudScreen(
    viewModel: SurvivalViewModel = hiltViewModel(),
    onRestoreFullMode: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    // If power restored, navigate back to full HUD
    if (state.operationalMode == OperationalState.FULL_TACTICAL) {
        onRestoreFullMode()
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        // Large directional arrow
        Text(
            text = state.nextTurnArrow,
            color = SlickColors.DataPrimary,
            fontSize = 120.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )

        // Distance to next turn -- massive Monospace
        Text(
            text = "${state.nextTurnDistanceMetres} m",
            color = SlickColors.DataPrimary,
            fontSize = 56.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )

        // Current speed -- massive Monospace
        Text(
            text = "${state.speedKmh.toInt()} km/h",
            color = SlickColors.DataPrimary,
            fontSize = 48.sp,
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
                text = "SURVIVAL MODE -- ${state.batteryPercent}% BATTERY",
                color = SlickColors.Alert,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { state.batteryPercent / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = SlickColors.Alert,
                trackColor = SlickColors.Surface,
            )
        }

        // BLE heartbeat indicator (subtle -- not a button)
        Text(
            text = "BLE Heartbeat Active",
            color = SlickColors.DataSecondary,
            fontSize = 12.sp,
        )
    }
}
