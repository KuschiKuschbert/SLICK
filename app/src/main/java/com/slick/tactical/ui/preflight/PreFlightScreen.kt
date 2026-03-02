package com.slick.tactical.ui.preflight

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slick.tactical.ui.theme.SlickColors

/**
 * Pre-Flight screen -- shown when the bike is stationary (kickstand down).
 *
 * Standard Material Design interaction patterns allowed here.
 * Route configuration, convoy lobby, GripMatrix forecast preview, and settings
 * are all accessible from this screen.
 *
 * In a full implementation:
 * - Route input (destination entry, Valhalla polyline generation)
 * - Pre-flight weather node preview (GripMatrix colour-coded list)
 * - Convoy lobby (QR code scan to join or create convoy)
 * - Settings (weather update strategy, battery optimization prompt)
 *
 * TODO Phase 5: Implement full pre-flight screens with route input and convoy setup.
 */
@Composable
fun PreFlightScreen(
    onStartConvoy: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SlickColors.Void)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "SLICK",
            color = SlickColors.Alert,
            fontSize = 48.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )

        Text(
            text = "Situational Location & Integrated Convoy",
            color = SlickColors.DataSecondary,
            fontSize = 14.sp,
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = "PRE-FLIGHT",
            color = SlickColors.DataPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Configure route and convoy before departure",
            color = SlickColors.DataSecondary,
            fontSize = 14.sp,
        )

        Spacer(modifier = Modifier.height(48.dp))

        // Primary action -- starts the convoy and enters In-Flight HUD
        Button(
            onClick = onStartConvoy,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = SlickColors.Alert,
                contentColor = SlickColors.Void,
            ),
        ) {
            Text(
                text = "START CONVOY",
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 2.sp,
            )
        }
    }
}
