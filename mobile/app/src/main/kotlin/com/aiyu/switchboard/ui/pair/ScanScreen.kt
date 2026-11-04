package com.aiyu.switchboard.ui.pair

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.aiyu.switchboard.data.PairOffer
import com.aiyu.switchboard.data.parsePairUri
import com.aiyu.switchboard.ui.components.ShapeIcon
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ScanScreen(onBack: () -> Unit, onCode: () -> Unit, onFound: (PairOffer) -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var wrong by remember { mutableStateOf(false) }
    var cameraFailed by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted && !cameraFailed) {
            Camera(onFailed = { cameraFailed = true }, onCode = { raw ->
                val offer = parsePairUri(raw)
                if (offer != null) onFound(offer) else wrong = true
                offer != null
            })
            Viewfinder()
        } else if (granted) {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Camera unavailable", style = MaterialTheme.typography.headlineMediumEmphasized, color = MaterialTheme.colorScheme.onSurface)
                Text("This device has no usable camera. Enter the code from your computer instead.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        } else {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                val cs = MaterialTheme.colorScheme
                ShapeIcon(Icons.Rounded.PhotoCamera, MaterialShapes.Cookie9Sided, cs.primaryContainer, cs.onPrimaryContainer, size = 120.dp, iconSize = 48.dp)
                Spacer(Modifier.height(24.dp))
                Text("Camera access", style = MaterialTheme.typography.headlineMediumEmphasized, color = cs.onSurface)
                Text("Switchboard uses the camera only to read the pairing code on your computer's screen.", textAlign = TextAlign.Center, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(24.dp))
                Button(onClick = { ask.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
            }
        }
        Row(Modifier.statusBarsPadding().padding(12.dp)) {
            FilledTonalIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
        }
        Surface(
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp).fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (wrong) "That isn't a Switchboard code" else "Point at the QR code", style = MaterialTheme.typography.titleLargeEmphasized)
                Text("On your computer: Switchboard → Pair a phone", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                FilledTonalButton(onClick = onCode) {
                    Icon(Icons.Rounded.Keyboard, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Enter code instead")
                }
            }
        }
    }
}

/** Dimmed screen with a clear rounded window and a sweeping line. */
@Composable
private fun Viewfinder() {
    val accent = MaterialTheme.colorScheme.primary
    val t = rememberInfiniteTransition(label = "scan")
    val sweep by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "sweep")
    Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val side = size.minDimension * 0.7f
        val tl = Offset((size.width - side) / 2, (size.height - side) / 2.4f)
        val r = CornerRadius(48.dp.toPx())
        drawRect(Color.Black.copy(alpha = 0.55f))
        drawRoundRect(Color.Transparent, tl, Size(side, side), r, blendMode = BlendMode.Clear)
        drawRoundRect(accent, tl, Size(side, side), r, style = Stroke(6.dp.toPx()))
        val y = tl.y + 24.dp.toPx() + (side - 48.dp.toPx()) * sweep
        drawLine(accent.copy(alpha = 0.9f), Offset(tl.x + 28.dp.toPx(), y), Offset(tl.x + side - 28.dp.toPx(), y), strokeWidth = 4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

/** CameraX preview plus ML Kit; [onCode] returns true to stop scanning. */
@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
@Composable
private fun Camera(onFailed: () -> Unit, onCode: (String) -> Boolean) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember { BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()) }
    var done by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose {
            scanner.close()
            executor.shutdown()
        }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { c ->
            val view = PreviewView(c).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
            val future = ProcessCameraProvider.getInstance(c)
            future.addListener({
                val provider = try { future.get() } catch (_: Exception) { onFailed(); return@addListener }
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { proxy ->
                    val img = proxy.image
                    if (img == null || done) {
                        proxy.close()
                        return@setAnalyzer
                    }
                    scanner.process(InputImage.fromMediaImage(img, proxy.imageInfo.rotationDegrees))
                        .addOnSuccessListener { codes ->
                            val raw = codes.firstNotNullOfOrNull { it.rawValue }
                            if (raw != null && !done) {
                                ContextCompat.getMainExecutor(ctx).execute { if (!done && onCode(raw)) done = true }
                            }
                        }
                        .addOnCompleteListener { proxy.close() }
                }
                // Emulators, tablets and Chromebooks may have no back camera.
                val lens = listOf(CameraSelector.DEFAULT_BACK_CAMERA, CameraSelector.DEFAULT_FRONT_CAMERA).firstOrNull { provider.hasCamera(it) }
                if (lens == null) { onFailed(); return@addListener }
                try {
                    provider.unbindAll()
                    provider.bindToLifecycle(owner, lens, preview, analysis)
                } catch (_: Exception) { onFailed() }
            }, ContextCompat.getMainExecutor(c))
            view
        },
    )
}
