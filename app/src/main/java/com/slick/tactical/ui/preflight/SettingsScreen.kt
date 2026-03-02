package com.slick.tactical.ui.preflight

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
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
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
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

        Text(
            text = "Version: SLICK 0.1.0 · Supabase: sqddpmhjpzgzggbsqgpr",
            color = SlickColors.DataSecondary,
            fontSize = 10.sp,
        )
    }
}

enum class WeatherStrategy(val label: String, val description: String) {
    ONCE("Sync Once (Pre-Flight)", "Download nodes before departure. Max battery life."),
    ON_STOP("Sync on Each Stop", "Re-sync when ActivityRecognition detects STILL (fuel stops)."),
    PERIODIC("Sync Every 30 Min", "WorkManager background sync. Most current data."),
}
