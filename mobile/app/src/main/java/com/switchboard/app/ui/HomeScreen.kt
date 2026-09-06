package com.switchboard.app.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.switchboard.app.UiState
import com.switchboard.app.net.Display
import com.switchboard.app.net.ShellGesture
import com.switchboard.app.net.TransferStatus
import com.switchboard.app.transfer.RateUnit
import kotlin.math.roundToInt

enum class Section(val title: String, val icon: ImageVector) {
    Displays("Displays", Icons.Filled.Monitor),
    Audio("Audio & Media", Icons.AutoMirrored.Filled.VolumeUp),
    Media("Media", Icons.Filled.MusicNote),
    Deck("Stream Deck Neo", Icons.Filled.GridView),
    Touchpad("Touchpad", Icons.Filled.Mouse),
    Camera("Camera", Icons.Filled.PhotoCamera),
    Files("Files", Icons.Filled.Folder);

    fun availableIn(state: UiState): Boolean = when (this) {
        Displays -> state.canControlDisplay
        Audio -> state.canControlVolume || state.canControlMedia
        // When media is active/playing or integrated into Audio, hide separate Media row if Audio is available
        Media -> state.canControlMedia && !state.canControlVolume
        Deck -> true
        Touchpad -> state.canDriveInput
        // Always available: the camera is this phone's, so nothing about the
        // desktop's capabilities decides whether it can be offered.
        Camera -> true
        Files -> true
    }

    fun summaryOf(state: UiState): String = when (this) {
        Displays -> when (val count = state.host.displays.size) {
            0 -> "No controllable panels"
            1 -> state.host.displays.first().name
            else -> "$count panels"
        }
        Audio -> {
            val volStr = if (state.host.volume.muted) "Muted" else "${state.host.volume.level}%"
            if (state.host.media.active && state.host.media.title.isNotEmpty()) {
                "$volStr • ${state.host.media.summary}"
            } else {
                volStr
            }
        }
        Media -> state.host.media.summary
        Deck -> "8-key macro deck with infobar & paging"
        Touchpad -> "Drive the pointer and shell gestures"
        Camera -> "Use this phone as a webcam"
        Files -> when (val running = state.transfers.count { !TransferStatus.isTerminal(it.status) }) {
            0 -> "Send and receive files"
            1 -> "1 transfer in progress"
            else -> "$running transfers in progress"
        }
    }
}

class SectionActions(
    val onBrightness: (Display, Int) -> Unit,
    val onContrast: (Display, Int) -> Unit,
    val onVolume: (Int, Boolean) -> Unit,
    val onMixerSession: (sessionId: String, level: Int, muted: Boolean) -> Unit,
    val onAudioOutput: (deviceId: String) -> Unit,
    val onMedia: (String) -> Unit,
    val onSendFile: (Uri) -> Unit,
    val onSendFiles: (List<Uri>) -> Unit = { uris -> uris.forEach(onSendFile) },
    val onSendFolder: (Uri) -> Unit = {},
    val touchpad: TouchpadActions,
    val onTransferControl: (String, String) -> Unit,
    val rateUnit: RateUnit,
    val onLockSystem: () -> Unit = {},
    val onDeckAction: (Int, com.switchboard.app.net.DeckAction, String?) -> Unit = { _, _, _ -> },
    val onSaveDeckConfig: (com.switchboard.app.net.DeckConfig) -> Unit = {}
)

/**
 * Expressive Utility Control Deck for Switchboard.
 *
 * Replaces the passive multi-screen drill-down launcher with an interactive,
 * high-speed dashboard: inline media playback & master volume slider, multi-display
 * brightness selector with quick presets, and tactile bento action tiles.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: UiState,
    actions: SectionActions,
    onOpen: (Section) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
) {
    // 1-tap Send Files launcher for the Bento tile
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (!uris.isNullOrEmpty()) actions.onSendFiles(uris)
    }
    val notifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        picker.launch(arrayOf("*/*"))
    }
    val onTriggerSendFile: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            picker.launch(arrayOf("*/*"))
        }
    }

    val hasAnyControls = state.canControlDisplay || state.canControlVolume ||
        state.canControlMedia || state.canDriveInput

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (!hasAnyControls && state.host.displays.isEmpty()) {
            item {
                EmptyCard(
                    title = "No controls available",
                    body = "This desktop reported no controllable hardware. Check that the " +
                        "Switchboard daemon is running with access to your display and audio devices."
                )
            }
        } else {
            // 1. Hero Connection Status Banner
            item {
                HostHeroCard(state = state, onLock = actions.onLockSystem)
            }

            // 2. Active Now Playing & Audio Controls Pod (0-Click Media + Master Volume)
            if (state.canControlVolume || state.canControlMedia) {
                item {
                    ActiveAudioPod(
                        state = state,
                        actions = actions,
                        onOpen = { onOpen(Section.Audio) }
                    )
                }
            }

            // 3. Multi-Monitor Displays Pod (0-Click Brightness & Multi-Display Selector)
            if (state.canControlDisplay && state.host.displays.isNotEmpty()) {
                item {
                    DisplaysControlPod(
                        displays = state.host.displays,
                        onBrightness = actions.onBrightness,
                        onOpen = { onOpen(Section.Displays) }
                    )
                }
            }

            // 4. Bento Utility Grid (Touchpad, Files, Camera, Mixer)
            item {
                BentoUtilityGrid(
                    state = state,
                    actions = actions,
                    onOpen = onOpen,
                    onSendFile = onTriggerSendFile
                )
            }

            item {
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/**
 * Tonal hero banner indicating real-time host connectivity and key hardware summary.
 */
@Composable
private fun HostHeroCard(
    state: UiState,
    onLock: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .graphicsLayer { alpha = pulseAlpha }
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = state.activeHost?.hostName ?: "Switchboard Desktop",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Connected",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (state.host.displays.isNotEmpty()) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${state.host.displays.size} panels",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (state.canControlVolume) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (state.host.volume.muted) "Muted" else "${state.host.volume.level}% vol",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (state.host.volume.muted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (state.canLockSystem && onLock != null) {
                val isLocked = state.host.locked
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = CircleShape,
                    color = if (isLocked) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                    modifier = Modifier
                        .size(38.dp)
                        .bouncyClickable(onClick = onLock)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                            contentDescription = if (isLocked) "Host workstation is locked" else "Lock workstation",
                            tint = if (isLocked) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            },
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Unified live audio control pod: artwork, transport, animated equalizer,
 * and inline master volume slider.
 */
@Composable
private fun ActiveAudioPod(
    state: UiState,
    actions: SectionActions,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val media = state.host.media
    val hasMedia = state.canControlMedia && (media.active || media.title.isNotEmpty())

    SectionCard(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(34.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (hasMedia) Icons.Filled.MusicNote else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (hasMedia) "Now Playing" else "Audio Controls",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (!hasMedia && state.canRouteOutput) {
                    val defaultOut = state.host.outputs.firstOrNull { it.default }?.name
                    if (!defaultOut.isNullOrEmpty()) {
                        Text(
                            text = defaultOut,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .bouncyClickable(onClick = onOpen)
                    .padding(vertical = 4.dp, horizontal = 4.dp)
            ) {
                Text(
                    text = "Mixer",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(2.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Open audio mixer and devices",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        if (hasMedia) {
            Spacer(Modifier.height(14.dp))
            NowPlaying(
                media = media,
                artwork = state.artwork,
                artSize = 64.dp
            )
            Spacer(Modifier.height(12.dp))
            TransportRow(
                playing = media.isPlaying,
                onMedia = actions.onMedia
            )
        }

        if (state.canControlVolume) {
            Spacer(Modifier.height(14.dp))
            InlineVolumeSlider(
                level = state.host.volume.level,
                muted = state.host.volume.muted,
                onVolume = actions.onVolume
            )
        }
    }
}

/**
 * High-tactility inline volume slider with quick mute container and percentage readout.
 */
@Composable
private fun InlineVolumeSlider(
    level: Int,
    muted: Boolean,
    onVolume: (Int, Boolean) -> Unit
) {
    var dragging by remember { mutableFloatStateOf(Float.NaN) }
    val shown = if (dragging.isNaN()) level.toFloat() else dragging
    val haptics = LocalHapticFeedback.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Surface(
            shape = CircleShape,
            color = if (muted) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier
                .size(42.dp)
                .bouncyClickable {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onVolume(level, !muted)
                }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = if (muted) "Unmute master volume" else "Mute master volume",
                    tint = if (muted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
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
                .semantics { contentDescription = "Master volume slider" }
        )
        Spacer(Modifier.width(10.dp))
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (muted) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.width(52.dp)
        ) {
            Text(
                text = if (muted) "MUTED" else "${shown.roundToInt()}%",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = if (muted) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                maxLines = 1
            )
        }
    }
}

/**
 * Multi-display control pod with instant panel selector tabs, inline brightness,
 * and 1-tap quick preset pills (25%, 50%, 75%, 100%).
 */
@Composable
private fun DisplaysControlPod(
    displays: List<Display>,
    onBrightness: (Display, Int) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedIndex by remember(displays.size) { mutableIntStateOf(0) }
    val safeIndex = selectedIndex.coerceIn(0, (displays.size - 1).coerceAtLeast(0))
    val display = displays.getOrNull(safeIndex) ?: return
    val haptics = LocalHapticFeedback.current

    SectionCard(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(34.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (display.internal) Icons.Filled.Laptop else Icons.Filled.Monitor,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Displays",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (displays.size == 1) display.name else "${displays.size} panels connected",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .bouncyClickable(onClick = onOpen)
                    .padding(vertical = 4.dp, horizontal = 4.dp)
            ) {
                Text(
                    text = "All Panels",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(2.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Open all display controls",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        if (displays.size > 1) {
            Spacer(Modifier.height(12.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                itemsIndexed(displays) { index, disp ->
                    val isSelected = index == safeIndex
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)) else null,
                        modifier = Modifier.bouncyClickable {
                            selectedIndex = index
                        }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = if (disp.internal) Icons.Filled.Laptop else Icons.Filled.Monitor,
                                contentDescription = null,
                                tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = disp.name,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        LevelRow(
            icon = Icons.Filled.LightMode,
            label = "${display.name} brightness",
            value = display.brightness,
            min = display.minBrightness,
            max = display.maxBrightness,
            onChange = { onBrightness(display, it) }
        )

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val presets = listOf(25 to "25%", 50 to "50%", 75 to "75%", 100 to "100%")
            presets.forEach { (pct, label) ->
                val targetVal = ((pct / 100f) * (display.maxBrightness - display.minBrightness) + display.minBrightness).roundToInt()
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .bouncyClickable {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onBrightness(display, targetVal)
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

/**
 * Responsive 2-column bento grid for high-frequency tools: Touchpad, File drop,
 * Webcam mode, and App Audio Mixer.
 */
@Composable
private fun BentoUtilityGrid(
    state: UiState,
    actions: SectionActions,
    onOpen: (Section) -> Unit,
    onSendFile: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Row 1: Touchpad & Files
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Touchpad Tile
            BentoTile(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.Mouse,
                title = "Touchpad",
                subtitle = "Drive pointer & gestures",
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                onClick = { onOpen(Section.Touchpad) }
            ) {
                Spacer(Modifier.height(10.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .weight(1f)
                            .height(30.dp)
                            .bouncyClickable {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                actions.touchpad.onGesture(ShellGesture.SHOW_DESKTOP)
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "Desktop",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1
                            )
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .weight(1f)
                            .height(30.dp)
                            .bouncyClickable {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                actions.touchpad.onGesture(ShellGesture.TASK_VIEW)
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "Tasks",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1
                            )
                        }
                    }
                    if (state.canLockSystem) {
                        val isLocked = state.host.locked
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isLocked) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(30.dp)
                                .bouncyClickable {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    actions.onLockSystem()
                                }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                                    contentDescription = null,
                                    tint = if (isLocked) {
                                        MaterialTheme.colorScheme.onErrorContainer
                                    } else {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    },
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = if (isLocked) "Locked" else "Lock",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isLocked) {
                                        MaterialTheme.colorScheme.onErrorContainer
                                    } else {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    },
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            // Files Tile
            val activeTransfers = state.transfers.filterNot { TransferStatus.isTerminal(it.status) }
            BentoTile(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.Folder,
                title = "Send Files",
                subtitle = if (activeTransfers.isNotEmpty()) {
                    "${activeTransfers.size} transferring"
                } else {
                    "Drop to desktop"
                },
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                onClick = { onOpen(Section.Files) }
            ) {
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                        .bouncyClickable(onClick = onSendFile)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CloudUpload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Choose",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }

        // Row 2: Camera & App Mixer
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Camera Tile
            BentoTile(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.PhotoCamera,
                title = "Webcam",
                subtitle = "Stream to PC",
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                onClick = { onOpen(Section.Camera) }
            )

            // App Mixer Tile
            val sessionCount = state.host.mixer.size
            BentoTile(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.GraphicEq,
                title = "App Mixer",
                subtitle = when (sessionCount) {
                    0 -> "No apps active"
                    1 -> "1 active app"
                    else -> "$sessionCount active apps"
                },
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                onClick = { onOpen(Section.Audio) }
            )
        }

        // Row 3: Elgato Stream Deck Neo Console Tile (Full Width)
        StreamDeckNeoTile(
            state = state,
            onClick = { onOpen(Section.Deck) }
        )
    }
}

/**
 * Tactical hardware console tile for Elgato Stream Deck Neo.
 * Displays hardware preview with mini LCD key squircles and 1-tap launcher.
 */
@Composable
private fun StreamDeckNeoTile(
    state: UiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    val pages = state.deckConfig.pages
    val pageCount = if (pages.isNotEmpty()) pages.size else 1
    val activePage = pages.getOrNull(state.deckConfig.activePage) ?: pages.firstOrNull()
    val pageName = activePage?.name ?: "Main"

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = modifier
            .fillMaxWidth()
            .bouncyClickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.GridView,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Stream Deck Neo",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = "8-KEY CONSOLE",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "$pageName ($pageCount ${if (pageCount == 1) "page" else "pages"}) • Tap for landscape console",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Open Stream Deck Neo",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(Modifier.height(14.dp))

            // Mini hardware preview: 4 mini squircles + launch button
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Mini key previews
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val previewKeys = activePage?.keys ?: emptyList()
                        for (i in 0 until 4) {
                            val key = previewKeys.find { it.index == i }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                                modifier = Modifier.size(32.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = when (key?.icon) {
                                            "home" -> Icons.Filled.Home
                                            "notes" -> Icons.Filled.Description
                                            "folder" -> Icons.Filled.Folder
                                            "calendar" -> Icons.Filled.CalendarToday
                                            "youtube" -> Icons.Filled.PlayArrow
                                            "settings" -> Icons.Filled.Settings
                                            else -> Icons.Filled.Code
                                        },
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Launch button pill
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.bouncyClickable {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onClick()
                        }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ScreenRotation,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Open Deck",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Expressive tactile bento card container.
 */
@Composable
private fun BentoTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    onClick: () -> Unit,
    content: (@Composable () -> Unit)? = null
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = modifier.bouncyClickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(34.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (content != null) {
                content()
            }
        }
    }
}

/**
 * Detail sub-screen container, keeping the full dedicated screen layouts
 * intact for users drilling down into individual sections.
 */
@Composable
fun SectionScreen(
    section: Section,
    state: UiState,
    actions: SectionActions,
    modifier: Modifier = Modifier
) {
    // The touchpad fills the screen and must not scroll under the finger, so
    // it bypasses the list every other section renders in.
    if (section == Section.Touchpad) {
        TouchpadScreen(actions.touchpad, modifier)
        return
    }
    if (section == Section.Deck) {
        DeckScreen(state, actions.onDeckAction, actions.onSaveDeckConfig, modifier)
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionBody(section, state, actions, compact = false) }
    }
}

@Composable
private fun SectionBody(
    section: Section,
    state: UiState,
    actions: SectionActions,
    compact: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        when (section) {
            Section.Displays -> {
                if (state.host.displays.isEmpty()) {
                    EmptyCard(
                        title = "No controllable displays",
                        body = "Enable DDC/CI in the monitor's on-screen menu. Some docks and " +
                            "KVM switches do not pass the control channel through."
                    )
                }
                state.host.displays.forEach { display ->
                    DisplayCard(
                        display = display,
                        detailed = !compact,
                        onBrightness = { actions.onBrightness(display, it) },
                        onContrast = { actions.onContrast(display, it) }
                    )
                }
            }

            Section.Audio -> {
                if (state.canControlMedia && (state.host.media.active || state.host.media.title.isNotEmpty())) {
                    SectionCard {
                        NowPlaying(
                            media = state.host.media,
                            artwork = state.artwork,
                            artSize = if (compact) 64.dp else 88.dp
                        )
                        Spacer(Modifier.height(16.dp))
                        TransportRow(
                            playing = state.host.media.isPlaying,
                            onMedia = actions.onMedia
                        )
                    }
                }

                if (state.canControlVolume) {
                    VolumeCard(
                        level = state.host.volume.level,
                        muted = state.host.volume.muted,
                        onVolume = actions.onVolume
                    )
                }

                if (state.canRouteOutput) {
                    OutputCard(
                        outputs = state.host.outputs,
                        onSelect = actions.onAudioOutput
                    )
                }

                if (state.canControlMixer) {
                    MixerCard(
                        sessions = state.host.mixer,
                        onSessionVolume = actions.onMixerSession
                    )
                }

                if (state.canControlMedia && !state.host.media.active && state.host.media.title.isEmpty()) {
                    SectionCard {
                        NowPlaying(
                            media = state.host.media,
                            artwork = state.artwork,
                            artSize = if (compact) 64.dp else 88.dp
                        )
                        Spacer(Modifier.height(16.dp))
                        TransportRow(
                            playing = state.host.media.isPlaying,
                            onMedia = actions.onMedia
                        )
                    }
                }
            }

            Section.Media -> SectionCard {
                NowPlaying(
                    media = state.host.media,
                    artwork = state.artwork,
                    artSize = if (compact) 64.dp else 88.dp
                )
                Spacer(Modifier.height(16.dp))
                TransportRow(
                    playing = state.host.media.isPlaying,
                    onMedia = actions.onMedia
                )
            }

            // Reachable only from the quick-controls sheet: the full screen
            // short-circuits above.
            Section.Touchpad -> EmptyCard(
                title = "Touchpad",
                body = "Open the Touchpad section for the pointer, gestures and buttons."
            )

            Section.Deck -> EmptyCard(
                title = "Stream Deck Neo",
                body = "Open the Stream Deck Neo section for the 8-key macro surface, dynamic infobar and paging controls."
            )

            Section.Camera -> CameraScreen()

            Section.Files -> FilesBody(
                transfers = state.transfers,
                rateUnit = actions.rateUnit,
                onSendFiles = actions.onSendFiles,
                onSendFolder = actions.onSendFolder,
                onControl = actions.onTransferControl
            )
        }
    }
}
