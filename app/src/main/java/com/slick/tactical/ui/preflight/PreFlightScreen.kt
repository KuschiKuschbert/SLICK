package com.slick.tactical.ui.preflight

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.slick.tactical.ui.theme.SlickColors

/**
 * Pre-Flight container screen with three tabs:
 * 1. ROUTE -- coordinate input + GripMatrix weather sync
 * 2. CONVOY -- create/join lobby + QR code
 * 3. SETTINGS -- weather strategy, battery optimization, convoy prefs
 *
 * Standard Material Design allowed here (kickstand down, bike stationary).
 * Tactical OLED HUD activates only In-Flight.
 */
@Composable
fun PreFlightScreen(
    onStartConvoy: () -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("ROUTE", "CONVOY", "SETTINGS")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SlickColors.Void),
    ) {
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = SlickColors.Surface,
            contentColor = SlickColors.Alert,
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            text = title,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selectedTab == index) SlickColors.Alert else SlickColors.DataSecondary,
                        )
                    },
                )
            }
        }

        when (selectedTab) {
            0 -> RouteConfigContent(onStartConvoy = onStartConvoy)
            1 -> ConvoyLobbyScreen()
            2 -> SettingsScreen()
        }
    }
}
