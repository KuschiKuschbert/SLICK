package com.slick.tactical.ui.preflight

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.Image
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.slick.tactical.ui.theme.SlickColors
import com.slick.tactical.util.rememberQrCode

/**
 * Convoy Lobby screen -- Create or Join a convoy.
 *
 * Create Convoy tab:
 * - Generates a 6-character code (e.g. "SLICK7")
 * - Shows a QR code of the code for other riders to scan
 * - Starts Nearby Connections advertising (Lead role)
 *
 * Join Convoy tab:
 * - Text field for manual code entry
 * - Camera-based QR scanner using CameraX + MLKit
 * - Starts Nearby Connections discovery (Pack role)
 */
@Composable
fun ConvoyLobbyScreen(
    viewModel: ConvoyLobbyViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("CREATE CONVOY", "JOIN CONVOY")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SlickColors.Void)
            .padding(16.dp),
    ) {
        Text(
            text = "CONVOY LINK",
            color = SlickColors.Alert,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            modifier = Modifier.padding(bottom = 16.dp),
        )

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
                            title,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selectedTab == index) SlickColors.Alert else SlickColors.DataSecondary,
                        )
                    },
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (selectedTab) {
            0 -> CreateConvoyTab(state = state, onCreate = viewModel::createConvoy, onLeave = viewModel::leaveConvoy)
            1 -> JoinConvoyTab(
                state = state,
                onJoinCodeChanged = viewModel::onJoinCodeChanged,
                onJoin = { viewModel.joinConvoy() },
                onQrScanned = viewModel::onQrCodeScanned,
                onLeave = viewModel::leaveConvoy,
            )
        }

        // Error display
        state.error?.let { error ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = error,
                color = SlickColors.Alert,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun CreateConvoyTab(
    state: ConvoyLobbyUiState,
    onCreate: () -> Unit,
    onLeave: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!state.isAdvertising) {
            Text(
                text = "You will be the Lead rider.\nOthers join by scanning your QR code or entering your convoy code.",
                color = SlickColors.DataSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
            Button(
                onClick = onCreate,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SlickColors.Alert,
                    contentColor = SlickColors.Void,
                ),
            ) {
                Text("CREATE CONVOY", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
            }
        } else {
            // Show QR code + convoy code
            Text(
                text = "CONVOY CODE",
                color = SlickColors.DataSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )

            // Large monospace convoy code
            Text(
                text = state.convoyCode,
                color = SlickColors.Alert,
                fontSize = 48.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 8.sp,
            )

            // QR code: ZXing generates a bitmap, displayed as an ImageBitmap.
            // White background is mandatory -- QR scanners need module contrast.
            val qrBitmap = rememberQrCode(data = state.convoyCode, sizePx = 512)
            if (qrBitmap != null) {
                Image(
                    bitmap = qrBitmap,
                    contentDescription = "Convoy QR Code — scan to join ${state.convoyCode}",
                    modifier = Modifier
                        .size(200.dp)
                        .border(2.dp, SlickColors.DataSecondary),
                )
            } else {
                // Should never happen with a valid 6-char code, but guard gracefully
                Text(
                    text = "QR generation failed — share code verbally",
                    color = SlickColors.DataSecondary,
                    fontSize = 12.sp,
                )
            }

            Text(
                text = "Advertising as Lead · ${state.connectedCount} riders joined",
                color = SlickColors.Wash,
                fontSize = 13.sp,
            )

            OutlinedButton(
                onClick = onLeave,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = SlickColors.DataSecondary),
            ) {
                Text("END CONVOY")
            }
        }
    }
}

@Composable
private fun JoinConvoyTab(
    state: ConvoyLobbyUiState,
    onJoinCodeChanged: (String) -> Unit,
    onJoin: () -> Unit,
    onQrScanned: (String) -> Unit,
    onLeave: () -> Unit,
) {
    val context = LocalContext.current
    var showCamera by remember { mutableStateOf(false) }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
        if (granted) showCamera = true
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!state.isDiscovering) {
            Text(
                text = "Enter the convoy code from the Lead rider, or scan their QR code.",
                color = SlickColors.DataSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )

            OutlinedTextField(
                value = state.joinCode,
                onValueChange = onJoinCodeChanged,
                label = { Text("Convoy Code", color = SlickColors.DataSecondary) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    keyboardType = KeyboardType.Ascii,
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = SlickColors.Alert,
                    unfocusedTextColor = SlickColors.Alert,
                    focusedBorderColor = SlickColors.Alert,
                    unfocusedBorderColor = SlickColors.Surface,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onJoin,
                    enabled = state.joinCode.length >= 4,
                    modifier = Modifier.weight(1f).height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SlickColors.Alert,
                        contentColor = SlickColors.Void,
                    ),
                ) {
                    Text("JOIN", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                }
                Button(
                    onClick = {
                        if (hasCameraPermission) showCamera = true
                        else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    },
                    modifier = Modifier.weight(1f).height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SlickColors.Surface,
                        contentColor = SlickColors.DataPrimary,
                    ),
                ) {
                    Text("SCAN QR", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }

            if (showCamera && hasCameraPermission) {
                QrScannerView(
                    onCodeScanned = { code ->
                        showCamera = false
                        onQrScanned(code)
                    },
                    modifier = Modifier.fillMaxWidth().height(260.dp),
                )
            }
        } else {
            Text(
                text = "Searching for convoy ${state.joinCode}...",
                color = SlickColors.Wash,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Ensure you are within ~50m of the Lead rider.",
                color = SlickColors.DataSecondary,
                fontSize = 13.sp,
            )
            OutlinedButton(
                onClick = onLeave,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = SlickColors.DataSecondary),
            ) {
                Text("CANCEL")
            }
        }
    }
}

/**
 * CameraX viewfinder with MLKit QR barcode scanning.
 * Calls [onCodeScanned] once when a valid QR code is detected.
 */
@Composable
private fun QrScannerView(
    onCodeScanned: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var scanned by remember { mutableStateOf(false) }

    Box(modifier = modifier.background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val barcodeScanner = BarcodeScanning.getClient()
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { imageProxy ->
                        if (!scanned) {
                            val mediaImage = imageProxy.image
                            if (mediaImage != null) {
                                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                                barcodeScanner.process(image)
                                    .addOnSuccessListener { barcodes ->
                                        val qr = barcodes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }
                                        val rawValue = qr?.rawValue
                                        if (!rawValue.isNullOrBlank() && !scanned) {
                                            scanned = true
                                            onCodeScanned(rawValue)
                                        }
                                    }
                                    .addOnCompleteListener { imageProxy.close() }
                            } else {
                                imageProxy.close()
                            }
                        } else {
                            imageProxy.close()
                        }
                    }
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis,
                    )
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            text = "Point at QR code",
            color = Color.White,
            fontSize = 14.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp),
        )
    }
}
