package com.switchboard.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.switchboard.app.UiState
import com.switchboard.app.net.Display
import kotlin.math.roundToInt

/**
 * The control surface: one card per display, then audio and transport.
 *
 * Layout follows the desktop panel it mirrors, so the two read as one product:
 * an external monitor shows brightness and contrast, a built-in panel shows
 * brightness alone because it has no DDC/CI contrast channel.
 */
@Composable
fun ControlScreen(
    state: UiState,
    onBrightness: (Display, Int) -> Unit,
    onContrast: (Display, Int) -> Unit,
    onVolume: (Int, Boolean) -> Unit,
    onMedia: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (state.host.displays.isEmpty()) {
            item {
                EmptyCard(
                    title = "No controllable displays",
                    body = "Enable DDC/CI in the monitor's on-screen menu. Some docks and KVM " +
                        "switches do not pass the control channel through."
                )
            }
        }

        items(state.host.displays, key = { it.id }) { display ->
            DisplayCard(
                display = display,
                onBrightness = { onBrightness(display, it) },
                onContrast = { onContrast(display, it) }
            )
        }

        if (state.canControlVolume) {
            item {
                VolumeCard(
                    level = state.host.volume.level,
                    muted = state.host.volume.muted,
                    onVolume = onVolume
                )
            }
        }

        if (state.canControlMedia) {
            item { MediaCard(onMedia = onMedia) }
        }
    }
}

@Composable
private fun DisplayCard(
    display: Display,
    onBrightness: (Int) -> Unit,
    onContrast: (Int) -> Unit
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (display.internal) Icons.Filled.Laptop else Icons.Filled.Monitor,
                contentDescription = null, // the name beside it carries the meaning
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = display.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (display.internal) "Internal" else "DDC/CI",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(12.dp))

        LevelRow(
            icon = Icons.Filled.LightMode,
            label = "${display.name} brightness",
            value = display.brightness,
            min = display.minBrightness,
            max = display.maxBrightness,
            onChange = onBrightness
        )

        if (display.hasContrast) {
            Spacer(Modifier.height(4.dp))
            LevelRow(
                icon = Icons.Filled.Contrast,
                label = "${display.name} contrast",
                value = display.contrast,
                min = display.minContrast,
                max = display.maxContrast,
                onChange = onContrast
            )
        }

        Spacer(Modifier.height(8.dp))
        // The panel's real capability range, which is not always 0-100.
        Text(
            text = buildString {
                append("Brightness ${display.brightness} / ${display.maxBrightness}")
                if (display.hasContrast) {
                    append("    Contrast ${display.contrast} / ${display.maxContrast}")
                }
            },
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * A labelled slider.
 *
 * The value shown tracks the drag locally and only publishes on release plus at
 * a coarse cadence: each write travels over I2C to the panel, so a raw
 * per-pixel stream would queue up behind the bus.
 */
@Composable
private fun LevelRow(
    icon: ImageVector,
    label: String,
    value: Int,
    min: Int,
    max: Int,
    onChange: (Int) -> Unit
) {
    var dragging by remember { mutableFloatStateOf(Float.NaN) }
    val shown = if (dragging.isNaN()) value.toFloat() else dragging
    val percent = if (max > min) ((shown - min) / (max - min) * 100).roundToInt() else 0

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Slider(
            value = shown,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                if (!dragging.isNaN()) {
                    onChange(dragging.roundToInt())
                    dragging = Float.NaN
                }
            },
            valueRange = min.toFloat()..max.toFloat(),
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = label }
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "$percent%",
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(44.dp)
        )
    }
}

@Composable
private fun VolumeCard(level: Int, muted: Boolean, onVolume: (Int, Boolean) -> Unit) {
    var dragging by remember { mutableFloatStateOf(Float.NaN) }
    val shown = if (dragging.isNaN()) level.toFloat() else dragging

    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Master volume",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            // State is spelled out as well as coloured.
            Text(
                text = if (muted) "Muted" else "$level%",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { onVolume(level, !muted) },
                modifier = Modifier.size(48.dp) // 48dp touch target
            ) {
                Icon(
                    imageVector = if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = if (muted) "Unmute" else "Mute",
                    tint = if (muted) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            Slider(
                value = shown,
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    if (!dragging.isNaN()) {
                        onVolume(dragging.roundToInt(), muted)
                        dragging = Float.NaN
                    }
                },
                valueRange = 0f..100f,
                enabled = !muted,
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "Master volume" }
            )
            Spacer(Modifier.width(12.dp))
        }
    }
}

@Composable
private fun MediaCard(onMedia: (String) -> Unit) {
    SectionCard {
        Text(
            text = "Media",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(12.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            TransportButton(Icons.Filled.SkipPrevious, "Previous track") { onMedia("prev") }
            FilledTonalIconButton(
                onClick = { onMedia("toggle") },
                modifier = Modifier.size(56.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Play or pause")
            }
            TransportButton(Icons.Filled.SkipNext, "Next track") { onMedia("next") }
            TransportButton(Icons.Filled.Stop, "Stop") { onMedia("stop") }
        }
    }
}

@Composable
private fun TransportButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(icon, contentDescription = label)
    }
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun EmptyCard(title: String, body: String) {
    SectionCard {
        Box(Modifier.fillMaxWidth()) {
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
