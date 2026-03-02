package com.slick.tactical.util

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import timber.log.Timber

/**
 * Generates a QR code [ImageBitmap] from a text [data] string.
 *
 * Uses ZXing (com.google.zxing:core) -- pure Java, no KMP dependency, works on all Android
 * API levels ≥ 26. The output is a square white-background black-module bitmap suitable
 * for direct use in a Compose [Image] composable.
 *
 * Tactical OLED note: [backgroundColor] defaults to white so that the QR code is visible
 * on the black screen. QR scanners require sufficient contrast between modules and background;
 * do NOT invert to black background regardless of OLED mode.
 *
 * @param data The string to encode (e.g., a 6-char convoy code)
 * @param sizePx Output bitmap dimension in pixels (default: 512 × 512)
 * @param foregroundColor Module (dark) colour in ARGB format (default: black)
 * @param backgroundColor Background colour in ARGB format (default: white)
 * @return [ImageBitmap] ready for Compose, or null if encoding fails
 */
fun generateQrCode(
    data: String,
    sizePx: Int = 512,
    foregroundColor: Int = Color.BLACK,
    backgroundColor: Int = Color.WHITE,
): ImageBitmap? {
    if (data.isBlank()) {
        Timber.w("QrCodeGenerator: empty data string -- skipping QR generation")
        return null
    }
    return try {
        val hints = mapOf(
            EncodeHintType.MARGIN to 2,       // Quiet zone: 2 module widths (minimal but valid)
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(data, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)

        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) foregroundColor else backgroundColor)
            }
        }
        bitmap.asImageBitmap()
    } catch (e: Exception) {
        Timber.e(e, "QrCodeGenerator: failed to encode QR for data='%s'", data.take(4))
        null
    }
}

/**
 * Composable wrapper that [remember]s the QR [ImageBitmap] and recomputes only
 * when [data] changes. Suitable for use directly in the Convoy Lobby screen.
 *
 * @param data The string to encode
 * @param sizePx Bitmap size in pixels
 * @return [ImageBitmap] or null if encoding fails
 */
@Composable
fun rememberQrCode(data: String, sizePx: Int = 512): ImageBitmap? = remember(data, sizePx) {
    generateQrCode(data, sizePx)
}
