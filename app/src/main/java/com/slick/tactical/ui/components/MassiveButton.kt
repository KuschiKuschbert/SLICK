package com.slick.tactical.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Massive glove-friendly tap target for Zone 3 interaction deck.
 *
 * Jobs Protocol enforcement:
 * - Minimum height: 64dp (set by caller via Modifier)
 * - Font: 18sp ExtraBold, letter-spaced (legible through heavy leather gloves)
 * - Color: always high-contrast on dark or alert background
 *
 * The caller is responsible for setting `.height(64.dp)` and `.weight(1f)`
 * to ensure 50% screen width and adequate hit area.
 */
@Composable
fun MassiveButton(
    text: String,
    onClick: () -> Unit,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
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
            fontSize = 18.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.5.sp,
        )
    }
}
