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
 * Route configuration tab -- origin/destination coordinates, speed, departure time.
 *
 * Triggers the full Valhalla → GripMatrix → Open-Meteo weather sync pipeline.
 * START CONVOY is enabled once weather nodes are synced.
 */
@Composable
fun RouteConfigContent(
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
        Text(
            text = "SLICK",
            color = SlickColors.Alert,
            fontSize = 40.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )
        Text("Pre-Flight Route Configuration", color = SlickColors.DataSecondary, fontSize = 14.sp)

        Spacer(modifier = Modifier.height(8.dp))

        SectionLabel("ORIGIN")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SlickTextField(state.originLat, viewModel::onOriginLatChanged, "Lat", Modifier.weight(1f))
            SlickTextField(state.originLon, viewModel::onOriginLonChanged, "Lon", Modifier.weight(1f))
        }
        Text("e.g. Kawana: -26.7380, 153.1230", color = SlickColors.DataSecondary, fontSize = 12.sp)

        SectionLabel("DESTINATION")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SlickTextField(state.destinationLat, viewModel::onDestinationLatChanged, "Lat", Modifier.weight(1f))
            SlickTextField(state.destinationLon, viewModel::onDestinationLonChanged, "Lon", Modifier.weight(1f))
        }
        Text("e.g. Yeppoon: -23.1300, 150.7400", color = SlickColors.DataSecondary, fontSize = 12.sp)

        SectionLabel("RIDE PARAMETERS")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SlickTextField(state.averageSpeedKmh, viewModel::onAverageSpeedChanged, "Avg Speed (km/h)", Modifier.weight(1f))
            SlickTextField(state.departureTime24h, viewModel::onDepartureTimeChanged, "Depart (HH:mm)", Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(8.dp))

        when {
            state.isSyncing -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(color = SlickColors.Wash, strokeWidth = 2.dp)
                Text("Fetching route and weather nodes...", color = SlickColors.Wash, fontSize = 14.sp)
            }
            state.isSyncComplete -> Text(
                "✓ ${state.syncedNodeCount} weather nodes synced. Ready to ride.",
                color = Color(0xFF4CAF50),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            state.syncError != null -> Text(
                "Sync failed: ${state.syncError}",
                color = SlickColors.Alert,
                fontSize = 14.sp,
            )
        }

        Button(
            onClick = viewModel::syncRouteWeather,
            enabled = !state.isSyncing,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = SlickColors.Wash,
                contentColor = SlickColors.Void,
                disabledContainerColor = SlickColors.Surface,
                disabledContentColor = SlickColors.DataSecondary,
            ),
        ) {
            Text(
                if (state.isSyncing) "SYNCING..." else "SYNC WEATHER",
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 2.sp,
            )
        }

        Button(
            onClick = onStartConvoy,
            enabled = state.isSyncComplete,
            modifier = Modifier.fillMaxWidth().height(64.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = SlickColors.Alert,
                contentColor = SlickColors.Void,
                disabledContainerColor = SlickColors.Surface,
                disabledContentColor = SlickColors.DataSecondary,
            ),
        ) {
            Text(
                if (state.isSyncComplete) "START CONVOY →" else "SYNC WEATHER FIRST",
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.5.sp,
            )
        }

        Text(
            "Weather resolution: ~1km. Labels are Observed probabilities, not surface sensors.",
            color = SlickColors.DataSecondary,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(text, color = SlickColors.DataSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
}

@Composable
internal fun SlickTextField(
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
