package com.switchboard.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.switchboard.app.net.AudioSession
import com.switchboard.app.net.Display
import com.switchboard.app.net.MediaState
import kotlin.math.roundToInt

/**
 * Expressive Section Card with Material 3 Expressive corner radius (24.dp)
 * and elevation borders.
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(Modifier.padding(18.dp), content = content)
    }
}

@Composable
fun EmptyCard(title: String, body: String) {
    SectionCard {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Expressive slider row with capsule track styling, animated level badge,
 * and tactile feedback.
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
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.size(36.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
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
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            ),
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = label }
        )
        Spacer(Modifier.width(12.dp))
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.width(48.dp)
        ) {
            Text(
                text = "$percent%",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                maxLines = 1
            )
        }
    }
}

/**
 * Display control card with expressive header and brightness/contrast sliders.
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
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (display.internal) Icons.Filled.Laptop else Icons.Filled.Monitor,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = display.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Text(
                    text = if (display.internal) "Internal" else "DDC/CI",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        LevelRow(
            icon = Icons.Filled.LightMode,
            label = "${display.name} brightness",
            value = display.brightness,
            min = display.minBrightness,
            max = display.maxBrightness,
            onChange = onBrightness
        )

        if (display.hasContrast) {
            Spacer(Modifier.height(8.dp))
            LevelRow(
                icon = Icons.Filled.Contrast,
                label = "${display.name} contrast",
                value = display.contrast,
                min = display.minContrast,
                max = display.maxContrast,
                onChange = onContrast
            )
        }

        if (detailed) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = buildString {
                    append("Brightness: ${display.brightness} / ${display.maxBrightness}")
                    if (display.hasContrast) {
                        append("   •   Contrast: ${display.contrast} / ${display.maxContrast}")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Expressive master volume card with prominent mute container and slider.
 */
@Composable
fun VolumeCard(level: Int, muted: Boolean, onVolume: (Int, Boolean) -> Unit) {
    var dragging by remember { mutableFloatStateOf(Float.NaN) }
    val shown = if (dragging.isNaN()) level.toFloat() else dragging

    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Master volume",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (muted) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    text = if (muted) "MUTED" else "$level%",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (muted) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = CircleShape,
                color = if (muted) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.bouncyClickable { onVolume(level, !muted) }
            ) {
                Box(
                    modifier = Modifier.size(44.dp),
                    contentAlignment = Alignment.Center
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
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

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
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                ),
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "Master volume" }
            )
        }
    }
}

/**
 * Animated 3-bar vertical equalizer pulse indicating live audio playback.
 */
@Composable
private fun AnimatedEqualizer(
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "equalizer")
    val bar1 by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(420, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar1"
    )
    val bar2 by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(340, delayMillis = 80, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar2"
    )
    val bar3 by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(520, delayMillis = 40, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar3"
    )

    Row(
        modifier = modifier.height(18.dp),
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        listOf(bar1, bar2, bar3).forEach { heightFraction ->
            val actualFraction = if (isPlaying) heightFraction else 0.25f
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight(actualFraction)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

/**
 * Expressive Now Playing card with artwork spring scale and animated equalizer.
 */
@Composable
fun NowPlaying(
    media: MediaState,
    artwork: ImageBitmap?,
    artSize: Dp = 72.dp
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Artwork(artwork, artSize, isPlaying = media.isPlaying)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (media.active && media.title.isNotEmpty()) media.title else "Nothing playing",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (media.active && media.isPlaying) {
                    AnimatedEqualizer(isPlaying = true)
                }
            }
            if (media.artist.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = media.artist,
                    style = MaterialTheme.typography.bodyMedium,
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
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Text(
                        text = media.source,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun Artwork(artwork: ImageBitmap?, size: Dp, isPlaying: Boolean = false) {
    val shape = RoundedCornerShape(16.dp)
    val scale = remember { Animatable(1f) }

    LaunchedEffect(isPlaying) {
        scale.animateTo(
            targetValue = if (isPlaying) 1.03f else 1.0f,
            animationSpec = ExpressiveMotion.Bouncy
        )
    }

    if (artwork != null) {
        Image(
            bitmap = artwork,
            contentDescription = "Album art",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                }
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        )
        return
    }

    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.size(size)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size / 2.5f)
            )
        }
    }
}

/**
 * Transport controls with 56.dp central Play/Pause morphing button and spring scale buttons.
 */
@Composable
fun TransportRow(playing: Boolean, onMedia: (String) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        TransportButton(
            icon = Icons.Filled.SkipPrevious,
            label = "Previous track"
        ) { onMedia("prev") }

        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(56.dp)
                .bouncyClickable { onMedia("toggle") }
        ) {
            Box(contentAlignment = Alignment.Center) {
                AnimatedContent(
                    targetState = playing,
                    transitionSpec = {
                        (scaleIn(ExpressiveMotion.Bouncy) + fadeIn(tween(180)))
                            .togetherWith(scaleOut(ExpressiveMotion.Snappy) + fadeOut(tween(180)))
                    },
                    label = "playPauseMorph"
                ) { isPlaying ->
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }
        }

        TransportButton(
            icon = Icons.Filled.SkipNext,
            label = "Next track"
        ) { onMedia("next") }

        TransportButton(
            icon = Icons.Filled.Stop,
            label = "Stop"
        ) { onMedia("stop") }
    }
}

@Composable
private fun TransportButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .size(46.dp)
            .bouncyClickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/**
 * Per-application mixer card listing every OS audio session.
 *
 * Sessions that are present but not actively playing are dimmed rather than
 * hidden: a paused player would vanish mid-gesture if we dropped it.
 */
@Composable
fun MixerCard(
    sessions: List<AudioSession>,
    onSessionVolume: (sessionId: String, level: Int, muted: Boolean) -> Unit
) {
    SectionCard {
        Text(
            text = "Applications",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )

        if (sessions.isEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Nothing is using the audio mixer. Start playback and it will appear here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@SectionCard
        }

        sessions.forEach { session ->
            Spacer(Modifier.height(14.dp))
            SessionRow(session = session, onSessionVolume = onSessionVolume)
        }
    }
}

@Composable
private fun SessionRow(
    session: AudioSession,
    onSessionVolume: (sessionId: String, level: Int, muted: Boolean) -> Unit
) {
    // The whole row is dimmed when a session is present but not playing —
    // alpha 0.5 signals "quiet" without removing the control entirely.
    val rowAlpha = if (session.active) 1f else 0.5f

    Column(modifier = Modifier.graphicsLayer { alpha = rowAlpha }) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = session.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            // Mute toggle mirrors VolumeCard but is sized to sit inline.
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (session.muted)
                    MaterialTheme.colorScheme.errorContainer
                else
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.bouncyClickable {
                    onSessionVolume(session.id, session.level, !session.muted)
                }
            ) {
                Icon(
                    imageVector = if (session.muted)
                        Icons.AutoMirrored.Filled.VolumeOff
                    else
                        Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = if (session.muted)
                        "Unmute ${session.name}"
                    else
                        "Mute ${session.name}",
                    tint = if (session.muted)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(6.dp)
                        .size(18.dp)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        LevelRow(
            icon = if (session.muted)
                Icons.AutoMirrored.Filled.VolumeOff
            else
                Icons.AutoMirrored.Filled.VolumeUp,
            label = "${session.name} volume",
            value = session.level,
            min = 0,
            max = 100,
            enabled = !session.muted,
            onChange = { onSessionVolume(session.id, it, session.muted) }
        )
    }
}
