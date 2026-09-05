package com.switchboard.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.switchboard.app.camera.CameraController
import com.switchboard.app.net.CameraFacing
import com.switchboard.app.net.CameraQuality
import com.switchboard.app.net.CameraSettings
import com.switchboard.app.net.CameraWhiteBalance

/**
 * Drives this device's camera as a webcam for the paired desktop.
 *
 * Every control here is also reachable from the desktop; the two edit the same
 * settings block and each sees the other's changes, because the phone reports
 * its state back after every apply. That is why nothing on this screen keeps
 * its own copy of a value — the controller's flow is the single source, and a
 * slider dragged on the desktop moves here too.
 */
@Composable
fun CameraScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val controller = remember { CameraController.get(context) }
    val owner = LocalLifecycleOwner.current

    val state by controller.state.collectAsState()
    val settings by controller.settings.collectAsState()
    val requested by controller.requested.collectAsState()
    val pending by controller.pendingFromDesktop.collectAsState()

    var granted by remember { mutableStateOf(controller.hasPermission) }
    val askPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { allowed ->
        granted = allowed
        // The desktop may already be waiting; granting is the last thing it
        // needed, so the stream starts without a second tap.
        if (allowed && requested) controller.request()
    }

    // Capture is bound to this screen's lifecycle, so leaving releases the
    // camera and returning resumes a stream the desktop is still waiting for.
    DisposableEffect(owner, granted) {
        if (granted) controller.attach(owner)
        onDispose { controller.detach(owner) }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        StatusCard(
            streaming = state.streaming,
            resolution = if (state.width > 0) "${state.width}x${state.height}" else "",
            error = state.error,
            pending = pending,
            granted = granted,
            onGrant = { askPermission.launch(Manifest.permission.CAMERA) },
            onStart = { controller.request() },
            onStop = { controller.stop() }
        )

        if (granted) {
            CaptureCard(settings, state.hasFrontCamera, state.hasTorch) { controller.update(it) }
            FramingCard(settings) { controller.update(it) }
            if (state.hasManualFocus || state.hasManualExposure) {
                ImageCard(state.hasManualFocus, state.hasManualExposure,
                    state.minExposure, state.maxExposure, settings) { controller.update(it) }
            }
            WhiteBalanceCard(settings) { controller.update(it) }
        }
    }
}

@Composable
private fun StatusCard(
    streaming: Boolean,
    resolution: String,
    error: String,
    pending: Boolean,
    granted: Boolean,
    onGrant: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    SectionCard {
        Text(
            if (streaming) "Streaming to the desktop" else "Camera idle",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        if (resolution.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(resolution, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        AnimatedVisibility(visible = pending) {
            Column {
                Spacer(Modifier.height(8.dp))
                Text(
                    "The desktop is waiting for this camera. Streaming runs only " +
                        "while this screen is open.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        AnimatedVisibility(visible = error.isNotEmpty() && !pending) {
            Column {
                Spacer(Modifier.height(8.dp))
                Text(error, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
        }

        Spacer(Modifier.height(14.dp))
        when {
            !granted -> Button(onClick = onGrant) { Text("Allow camera access") }
            streaming -> OutlinedButton(onClick = onStop) { Text("Stop streaming") }
            else -> Button(onClick = onStart) { Text("Start streaming") }
        }
    }
}

@Composable
private fun CaptureCard(
    settings: CameraSettings,
    hasFrontCamera: Boolean,
    hasTorch: Boolean,
    onChange: (CameraSettings) -> Unit
) {
    SectionCard {
        CardTitle("Capture")

        // Resolution and JPEG quality move together: they trade against the
        // same thing, and separating them invites nonsense combinations.
        ChipRow(
            options = listOf(
                CameraQuality.FULL to "Full",
                CameraQuality.BALANCED to "720p",
                CameraQuality.LOW to "480p"
            ),
            selected = settings.quality,
            onSelect = { onChange(settings.copy(quality = it)) }
        )

        Spacer(Modifier.height(12.dp))
        LabelledSlider(
            label = "Frame rate",
            readout = "${settings.fps} fps",
            value = settings.fps.toFloat(),
            range = 5f..60f,
            steps = 10
        ) { onChange(settings.copy(fps = it.toInt())) }

        if (hasFrontCamera) {
            Spacer(Modifier.height(8.dp))
            IconToggleRow(
                icon = Icons.Filled.Cameraswitch,
                label = "Front camera",
                checked = settings.facing == CameraFacing.FRONT
            ) {
                onChange(
                    settings.copy(
                        facing = if (it) CameraFacing.FRONT else CameraFacing.BACK,
                        // A front camera that is not mirrored reads as wrong to
                        // the person in front of it, so it follows the lens.
                        mirror = it
                    )
                )
            }
        }
        if (hasTorch) {
            IconToggleRow(Icons.Filled.FlashOn, "Torch", settings.torch) {
                onChange(settings.copy(torch = it))
            }
        }
    }
}

@Composable
private fun FramingCard(settings: CameraSettings, onChange: (CameraSettings) -> Unit) {
    SectionCard {
        CardTitle("Framing")

        LabelledSlider(
            label = "Zoom",
            readout = "${(settings.zoom * 100).toInt()}%",
            value = settings.zoom.toFloat(),
            range = 0f..1f
        ) { onChange(settings.copy(zoom = it.toDouble())) }

        Spacer(Modifier.height(10.dp))
        Text("Orientation", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(6.dp))
        ChipRow(
            options = listOf(0 to "0°", 90 to "90°", 180 to "180°", 270 to "270°"),
            selected = settings.rotation,
            onSelect = { onChange(settings.copy(rotation = it)) }
        )

        Spacer(Modifier.height(8.dp))
        IconToggleRow(Icons.Filled.Rotate90DegreesCw, "Mirror", settings.mirror) {
            onChange(settings.copy(mirror = it))
        }
        SwitchRow("Auto framing", settings.autoFraming) {
            onChange(settings.copy(autoFraming = it))
        }
    }
}

@Composable
private fun ImageCard(
    hasManualFocus: Boolean,
    hasManualExposure: Boolean,
    minExposure: Int,
    maxExposure: Int,
    settings: CameraSettings,
    onChange: (CameraSettings) -> Unit
) {
    SectionCard {
        CardTitle("Image")

        if (hasManualFocus) {
            SwitchRow("Auto focus", settings.autoFocus) {
                onChange(settings.copy(autoFocus = it))
            }
            AnimatedVisibility(visible = !settings.autoFocus) {
                LabelledSlider(
                    label = "Focus",
                    readout = if (settings.focusDistance > 0.95) "Infinity" else "Near",
                    value = settings.focusDistance.toFloat(),
                    range = 0f..1f
                ) { onChange(settings.copy(focusDistance = it.toDouble())) }
            }
        }

        if (hasManualExposure && maxExposure > minExposure) {
            SwitchRow("Auto exposure", settings.autoExposure) {
                onChange(settings.copy(autoExposure = it))
            }
            AnimatedVisibility(visible = !settings.autoExposure) {
                LabelledSlider(
                    label = "Exposure",
                    readout = "${settings.exposure} EV",
                    value = settings.exposure.toFloat(),
                    range = minExposure.toFloat()..maxExposure.toFloat(),
                    steps = (maxExposure - minExposure - 1).coerceAtLeast(0)
                ) { onChange(settings.copy(exposure = it.toInt())) }
            }
        }
    }
}

@Composable
private fun WhiteBalanceCard(settings: CameraSettings, onChange: (CameraSettings) -> Unit) {
    SectionCard {
        CardTitle("White balance")
        ChipRow(
            options = CameraWhiteBalance.all.map { it to it.replaceFirstChar(Char::uppercase) },
            selected = settings.whiteBalance,
            onSelect = { onChange(settings.copy(whiteBalance = it)) }
        )
    }
}

// ---- Small shared pieces ----

@Composable
private fun CardTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun <T> ChipRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label) }
            )
        }
    }
}

@Composable
private fun LabelledSlider(
    label: String,
    readout: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onChange: (Float) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(
                readout,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun IconToggleRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
