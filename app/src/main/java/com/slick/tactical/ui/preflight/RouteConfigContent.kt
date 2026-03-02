package com.slick.tactical.ui.preflight

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import com.slick.tactical.ui.components.PlaceSearchBar
import com.slick.tactical.ui.theme.SlickColors

/**
 * Route configuration tab.
 *
 * Origin and destination are entered via a Waze-style search bar:
 * - Type a town/suburb name → suggestions appear in a tactical dark dropdown
 * - Offline fallback: QLD corridor towns + all Australian capital cities
 * - Online: Android Geocoder (Google Places, no API key on Android)
 *
 * Ride parameters (speed, departure time) remain as text fields.
 * START CONVOY is enabled once weather nodes are synced.
 */
@Composable
fun RouteConfigContent(
    onStartConvoy: () -> Unit,
    viewModel: PreFlightViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val locationFetchState by viewModel.locationFetchState.collectAsState()
    val context = LocalContext.current

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.fetchCurrentLocation()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SlickColors.Void)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "SLICK",
            color = SlickColors.Alert,
            fontSize = 40.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Pre-Flight Route Configuration",
            color = SlickColors.DataSecondary,
            fontSize = 14.sp,
        )

        Spacer(modifier = Modifier.height(4.dp))

        // ── Origin search ────────────────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            SectionLabel("ORIGIN")
            OutlinedButton(
                onClick = {
                    val hasPermission = context.checkSelfPermission(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (hasPermission) {
                        viewModel.fetchCurrentLocation()
                    } else {
                        locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                },
                enabled = !locationFetchState.isFetching,
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                    contentColor = SlickColors.Wash,
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, SlickColors.Wash),
                modifier = Modifier.height(32.dp),
            ) {
                if (locationFetchState.isFetching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = SlickColors.Wash,
                    )
                } else {
                    Text("📍 MY LOCATION", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            }
        }
        PlaceSearchBar(
            label = "Departure town or city",
            query = state.originQuery,
            onQueryChange = viewModel::onOriginQueryChange,
            onPlaceSelected = viewModel::onOriginSelected,
            modifier = Modifier.fillMaxWidth(),
        )
        CoordinateReadout(lat = state.originLat, lon = state.originLon)
        if (locationFetchState.error != null) {
            Text(
                text = locationFetchState.error!!,
                color = SlickColors.Alert,
                fontSize = 11.sp,
            )
        }

        // ── Destination search ───────────────────────────────────────────────
        SectionLabel("DESTINATION")
        PlaceSearchBar(
            label = "Arrival town or city",
            query = state.destinationQuery,
            onQueryChange = viewModel::onDestinationQueryChange,
            onPlaceSelected = viewModel::onDestinationSelected,
            modifier = Modifier.fillMaxWidth(),
        )
        CoordinateReadout(lat = state.destinationLat, lon = state.destinationLon)

        // ── Ride parameters ──────────────────────────────────────────────────
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
                keyboardType = KeyboardType.Number,
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ── Sync status ──────────────────────────────────────────────────────
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

        // ── Actions ──────────────────────────────────────────────────────────
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
            "Weather resolution: ~1 km. Forecasts are observed probabilities, not road surface sensors.",
            color = SlickColors.DataSecondary,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )

        Spacer(modifier = Modifier.height(8.dp))
    }
}

/** Small coordinate readout shown below each search bar after a place is selected. */
@Composable
private fun CoordinateReadout(lat: Double, lon: Double) {
    Text(
        text = "%.4f, %.4f".format(lat, lon),
        color = SlickColors.DataSecondary,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
    )
}

@Composable
internal fun SectionLabel(text: String) {
    Text(
        text = text,
        color = SlickColors.DataSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp,
    )
}

@Composable
internal fun SlickTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Decimal,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = SlickColors.DataSecondary, fontSize = 12.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
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
