package com.slick.tactical.ui.preflight

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.slick.tactical.data.remote.ShelterType
import com.slick.tactical.ui.theme.SlickColors

/**
 * Settings screen -- weather update strategy, battery optimization, convoy prefs.
 *
 * Weather strategy choices:
 * - ONCE: download at pre-flight, no further background updates (max battery life)
 * - ON_STOP: sync on each fuel stop detected by ActivityRecognition
 * - PERIODIC: WorkManager syncs every 30 min while riding (most current data)
 *
 * Battery optimization bypass: required for ConvoyForegroundService to survive
 * Samsung/Xiaomi/Oppo OEM task killers.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val powerManager = context.getSystemService(PowerManager::class.java)
    val packageName = context.packageName
    val isIgnoringBatteryOptimizations = powerManager.isIgnoringBatteryOptimizations(packageName)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SlickColors.Void)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = "SETTINGS",
            color = SlickColors.Alert,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
        )

        // ── Weather Update Strategy ───────────────────────────────────────────
        SectionLabel("WEATHER UPDATE STRATEGY")
        Text(
            text = "Controls how often GripMatrix nodes are refreshed during a ride.",
            color = SlickColors.DataSecondary,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )

        WeatherStrategy.values().forEach { strategy ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = state.weatherStrategy == strategy,
                        onClick = { viewModel.onWeatherStrategyChanged(strategy) },
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RadioButton(
                    selected = state.weatherStrategy == strategy,
                    onClick = { viewModel.onWeatherStrategyChanged(strategy) },
                    colors = RadioButtonDefaults.colors(selectedColor = SlickColors.Alert),
                )
                Column {
                    Text(strategy.label, color = SlickColors.DataPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(strategy.description, color = SlickColors.DataSecondary, fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ── Battery Optimization ──────────────────────────────────────────────
        SectionLabel("BATTERY OPTIMIZATION")
        Text(
            text = if (isIgnoringBatteryOptimizations) {
                "✓ SLICK has unrestricted battery access. Convoy link and crash detection will survive screen-off."
            } else {
                "OEM battery optimizers (Samsung, Xiaomi, Oppo) may kill the convoy link when the screen turns off. Grant unrestricted access to prevent this."
            },
            color = if (isIgnoringBatteryOptimizations) SlickColors.Wash else SlickColors.DataSecondary,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )

        if (!isIgnoringBatteryOptimizations) {
            Button(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        },
                    )
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SlickColors.Alert,
                    contentColor = SlickColors.Void,
                ),
            ) {
                Text("GRANT UNRESTRICTED BATTERY ACCESS", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ── Convoy Preferences ────────────────────────────────────────────────
        SectionLabel("CONVOY PREFERENCES")

        // Default speed setting
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("Default cruise speed", color = SlickColors.DataPrimary, fontSize = 14.sp)
                Text("Used for weather node ETA calculations", color = SlickColors.DataSecondary, fontSize = 12.sp)
            }
            Text(
                text = "${state.defaultSpeedKmh.toInt()} km/h",
                color = SlickColors.Alert,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(80, 100, 110, 120).forEach { speed ->
                Button(
                    onClick = { viewModel.onDefaultSpeedChanged(speed.toDouble()) },
                    modifier = Modifier.weight(1f).height(44.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.defaultSpeedKmh.toInt() == speed) SlickColors.Alert else SlickColors.Surface,
                        contentColor = if (state.defaultSpeedKmh.toInt() == speed) SlickColors.Void else SlickColors.DataSecondary,
                    ),
                ) {
                    Text("$speed", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ── Map Marker Filters ────────────────────────────────────────────────
        SectionLabel("MAP MARKERS")
        Text(
            text = "Select which POI types appear on the in-flight map. Disabled types are hidden from the map and predictive stops.",
            color = SlickColors.DataSecondary,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
        Spacer(modifier = Modifier.height(8.dp))
        PoiFilterGrid(
            enabledPoiTypes = state.enabledPoiTypes,
            onToggle = viewModel::onTogglePoiType,
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Version: SLICK 0.1.0 · Supabase: sqddpmhjpzgzggbsqgpr",
            color = SlickColors.DataSecondary,
            fontSize = 10.sp,
        )
    }
}

private data class PoiTypeConfig(
    val type: String,
    val label: String,
    val icon: String,
    val dotColor: Color,
)

private val POI_TYPE_CONFIGS = listOf(
    PoiTypeConfig(ShelterType.FUEL,        "Fuel",         "⛽", Color(0xFFFF9800)),
    PoiTypeConfig(ShelterType.REST_AREA,   "Rest Area",    "🛑", Color(0xFFFF9800)),
    PoiTypeConfig(ShelterType.PUB,         "Pub / Bar",    "🍺", Color(0xFF9C27B0)),
    PoiTypeConfig(ShelterType.CAFE,        "Cafe / Food",  "☕", Color(0xFF9C27B0)),
    PoiTypeConfig(ShelterType.HOTEL,       "Hotel / Motel","🏨", Color(0xFF9C27B0)),
    PoiTypeConfig(ShelterType.CONVENIENCE, "Convenience",  "🛒", Color(0xFF9C27B0)),
    PoiTypeConfig(ShelterType.SHELTER,     "Shelter",      "🏠", Color(0xFF9E9E9E)),
    PoiTypeConfig(ShelterType.TOILET,      "Toilet",       "🚻", Color(0xFF9E9E9E)),
    PoiTypeConfig(ShelterType.WATER,       "Water",        "💧", Color(0xFF9E9E9E)),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PoiFilterGrid(
    enabledPoiTypes: Set<String>,
    onToggle: (String) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        POI_TYPE_CONFIGS.forEach { config ->
            val enabled = config.type in enabledPoiTypes
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (enabled) SlickColors.Surface else SlickColors.Void)
                    .border(
                        width = 1.dp,
                        color = if (enabled) config.dotColor else SlickColors.DataSecondary.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(8.dp),
                    )
                    .clickable { onToggle(config.type) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (enabled) config.dotColor else SlickColors.DataSecondary.copy(alpha = 0.3f)),
                )
                Text(
                    text = "${config.icon} ${config.label}",
                    color = if (enabled) SlickColors.DataPrimary else SlickColors.DataSecondary.copy(alpha = 0.5f),
                    fontSize = 13.sp,
                    fontWeight = if (enabled) FontWeight.Medium else FontWeight.Normal,
                )
            }
        }
    }
}

enum class WeatherStrategy(val label: String, val description: String) {
    ONCE("Sync Once (Pre-Flight)", "Download nodes before departure. Max battery life."),
    ON_STOP("Sync on Each Stop", "Re-sync when ActivityRecognition detects STILL (fuel stops)."),
    PERIODIC("Sync Every 30 Min", "WorkManager background sync. Most current data."),
}
