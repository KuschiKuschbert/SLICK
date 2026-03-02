package com.slick.tactical.ui.preflight

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.slick.tactical.ui.theme.SlickColors

/**
 * Pre-Flight screen -- shown when the bike is stationary (kickstand down).
 *
 * The rider configures:
 * - Origin GPS coordinates (e.g., Kawana: -26.7380, 153.1230)
 * - Destination GPS coordinates (e.g., Yeppoon: -23.1300, 150.7400)
 * - Expected average speed in km/h (used for 24h ETA calculations at each node)
 * - Departure time in HH:mm (24h) -- used for Twilight Matrix and Solar Glare
 *
 * The "SYNC WEATHER" button triggers the full Valhalla → GripMatrix → Open-Meteo pipeline.
 * "START CONVOY" is enabled once weather is synced.
 *
 * Standard Material Design is allowed here -- the Tactical OLED HUD is only active In-Flight.
 */
@Composable
fun PreFlightScreen(
    onStartConvoy: () -> Unit,
    viewModel: PreFlightViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SlickColors.Void)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Header
        Text(
            text = "SLICK",
            color = SlickColors.Alert,
            fontSize = 40.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Pre-Flight Configuration",
            color = SlickColors.DataSecondary,
            fontSize = 14.sp,
        )

        Spacer(modifier = Modifier.height(8.dp))

        // ── Origin ────────────────────────────────────────────────────────────
        SectionLabel("ORIGIN")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SlickTextField(
                value = state.originLat,
                onValueChange = viewModel::onOriginLatChanged,
                label = "Lat",
                modifier = Modifier.weight(1f),
            )
            SlickTextField(
                value = state.originLon,
                onValueChange = viewModel::onOriginLonChanged,
                label = "Lon",
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = "e.g. Kawana: -26.7380, 153.1230",
            color = SlickColors.DataSecondary,
            fontSize = 12.sp,
        )

        // ── Destination ───────────────────────────────────────────────────────
        SectionLabel("DESTINATION")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SlickTextField(
                value = state.destinationLat,
                onValueChange = viewModel::onDestinationLatChanged,
                label = "Lat",
                modifier = Modifier.weight(1f),
            )
            SlickTextField(
                value = state.destinationLon,
                onValueChange = viewModel::onDestinationLonChanged,
                label = "Lon",
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = "e.g. Yeppoon: -23.1300, 150.7400",
            color = SlickColors.DataSecondary,
            fontSize = 12.sp,
        )

        // ── Ride Parameters ───────────────────────────────────────────────────
        SectionLabel("RIDE PARAMETERS")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SlickTextField(
                value = state.averageSpeedKmh,
                onValueChange = viewModel::onAverageSpeedChanged,
                label = "Avg Speed (km/h)",
                modifier = Modifier.weight(1f),
            )
            SlickTextField(
                value = state.departureTime24h,
                onValueChange = viewModel::onDepartureTimeChanged,
                label = "Depart (HH:mm)",
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ── Sync Status ───────────────────────────────────────────────────────
        when {
            state.isSyncing -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(
                        color = SlickColors.Wash,
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = "Fetching route and weather nodes...",
                        color = SlickColors.Wash,
                        fontSize = 14.sp,
                    )
                }
            }
            state.isSyncComplete -> {
                Text(
                    text = "✓ ${state.syncedNodeCount} weather nodes synced. Ready to ride.",
                    color = Color(0xFF4CAF50),  // Green -- not used as a brand colour, only for status
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            state.syncError != null -> {
                Text(
                    text = "Sync failed: ${state.syncError}",
                    color = SlickColors.Alert,
                    fontSize = 14.sp,
                )
            }
        }

        // ── Sync Button ───────────────────────────────────────────────────────
        Button(
            onClick = viewModel::syncRouteWeather,
            enabled = !state.isSyncing,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = SlickColors.Wash,
                contentColor = SlickColors.Void,
                disabledContainerColor = SlickColors.Surface,
                disabledContentColor = SlickColors.DataSecondary,
            ),
        ) {
            Text(
                text = if (state.isSyncing) "SYNCING..." else "SYNC WEATHER",
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 2.sp,
            )
        }

        // ── Start Convoy ──────────────────────────────────────────────────────
        Button(
            onClick = onStartConvoy,
            enabled = state.isSyncComplete,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = SlickColors.Alert,
                contentColor = SlickColors.Void,
                disabledContainerColor = SlickColors.Surface,
                disabledContentColor = SlickColors.DataSecondary,
            ),
        ) {
            Text(
                text = if (state.isSyncComplete) "START CONVOY →" else "SYNC WEATHER FIRST",
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.5.sp,
            )
        }

        Text(
            text = "Weather resolution: ~1km. Labels are Observed probabilities, not surface sensors.",
            color = SlickColors.DataSecondary,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = SlickColors.DataSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp,
    )
}

@Composable
private fun SlickTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = SlickColors.DataSecondary, fontSize = 12.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = SlickColors.DataPrimary,
            unfocusedTextColor = SlickColors.DataPrimary,
            focusedBorderColor = SlickColors.Alert,
            unfocusedBorderColor = SlickColors.Surface,
            cursorColor = SlickColors.Alert,
            focusedContainerColor = SlickColors.Surface,
            unfocusedContainerColor = SlickColors.Surface,
        ),
        modifier = modifier,
    )
}
