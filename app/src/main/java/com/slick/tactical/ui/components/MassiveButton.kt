package com.slick.tactical.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Massive glove-friendly tap target for Zone 3 interaction deck.
 *
 * Jobs Protocol enforcement:
 * - Minimum height: 64dp phone / 80dp tablet (set by caller via Modifier)
 * - Default font: 18sp ExtraBold, 14sp minimum per Jobs Protocol
 * - Always high-contrast on dark or alert background
 * - [fontSize] overrideable for tablet adaptation
 */
@Composable
fun MassiveButton(
    text: String,
    onClick: () -> Unit,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 18.sp,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
    ) {
        Text(
            text = text,
            fontSize = fontSize,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.5.sp,
        )
    }
}
