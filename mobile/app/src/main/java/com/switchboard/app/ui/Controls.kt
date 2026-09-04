package com.switchboard.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.switchboard.app.net.Display
import com.switchboard.app.net.MediaState
import kotlin.math.roundToInt

/**
 * The controls each section is built from.
 *
 * Every one of these appears twice: full size on the section's own screen, and
 * again inside the long-press quick settings sheet. Defining them once is what
 * keeps the shortcut and the screen behind it from drifting apart.
 */

@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
fun EmptyCard(title: String, body: String) {
    SectionCard {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * A labelled slider.
 *
 * The value shown tracks the drag locally and only publishes on release: each
 * write travels over I2C to the panel, so a raw per-pixel stream would queue up
 * behind the bus.
 */
@Composable
fun LevelRow(
    icon: ImageVector,
    label: String,
    value: Int,
    min: Int,
    max: Int,
    enabled: Boolean = true,
    onChange: (Int) -> Unit
) {
    var dragging by remember { mutableFloatStateOf(Float.NaN) }
    val shown = if (dragging.isNaN()) value.toFloat() else dragging
    val percent = if (max > min) ((shown - min) / (max - min) * 100).roundToInt() else 0

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null, // the label beside it carries the meaning
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
            enabled = enabled,
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

/**
 * One panel. An external monitor shows brightness and contrast; a built-in
 * panel shows brightness alone, having no DDC/CI contrast channel.
 */
@Composable
fun DisplayCard(
    display: Display,
    detailed: Boolean = true,
    onBrightness: (Int) -> Unit,
    onContrast: (Int) -> Unit
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (display.internal) Icons.Filled.Laptop else Icons.Filled.Monitor,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = display.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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

        // The panel's real capability range, which is not always 0-100. Only
        // worth the space on the section's own screen.
        if (detailed) {
            Spacer(Modifier.height(8.dp))
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
}

@Composable
fun VolumeCard(level: Int, muted: Boolean, onVolume: (Int, Boolean) -> Unit) {
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
                    imageVector = if (muted) {
                        Icons.AutoMirrored.Filled.VolumeOff
                    } else {
                        Icons.AutoMirrored.Filled.VolumeUp
                    },
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

/** Cover art, title, artist and the app the sound is coming from. */
@Composable
fun NowPlaying(
    media: MediaState,
    artwork: ImageBitmap?,
    artSize: Dp = 72.dp
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Artwork(artwork, artSize)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = if (media.active && media.title.isNotEmpty()) media.title else "Nothing playing",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (media.artist.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = media.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (media.album.isNotEmpty() && media.album != media.title) {
                Text(
                    text = media.album,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (media.source.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = media.source,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun Artwork(artwork: ImageBitmap?, size: Dp) {
    val shape = RoundedCornerShape(10.dp)
    if (artwork != null) {
        Image(
            bitmap = artwork,
            contentDescription = "Album art",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .clip(shape)
        )
        return
    }
    // Holding the same footprint keeps the row from jumping when art arrives.
    Row(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(size / 3)
        )
    }
}

/**
 * Transport buttons. The centre button shows the state the host reports, so a
 * track paused from the desktop reads as paused here.
 */
@Composable
fun TransportRow(playing: Boolean, onMedia: (String) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
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
            Icon(
                imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play"
            )
        }
        TransportButton(Icons.Filled.SkipNext, "Next track") { onMedia("next") }
        TransportButton(Icons.Filled.Stop, "Stop") { onMedia("stop") }
    }
}

@Composable
private fun TransportButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(icon, contentDescription = label)
    }
}
