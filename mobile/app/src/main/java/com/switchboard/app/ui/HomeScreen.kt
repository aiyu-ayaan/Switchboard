package com.switchboard.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.switchboard.app.UiState
import com.switchboard.app.net.Display

enum class Section(val title: String, val icon: ImageVector) {
    Displays("Displays", Icons.Filled.Monitor),
    Audio("Audio", Icons.AutoMirrored.Filled.VolumeUp),
    Media("Media", Icons.Filled.MusicNote);

    fun availableIn(state: UiState): Boolean = when (this) {
        Displays -> state.canControlDisplay
        Audio -> state.canControlVolume
        Media -> state.canControlMedia
    }

    fun summaryOf(state: UiState): String = when (this) {
        Displays -> when (val count = state.host.displays.size) {
            0 -> "No controllable panels"
            1 -> state.host.displays.first().name
            else -> "$count panels"
        }
        Audio -> if (state.host.volume.muted) "Muted" else "${state.host.volume.level}%"
        Media -> state.host.media.summary
    }
}

class SectionActions(
    val onBrightness: (Display, Int) -> Unit,
    val onContrast: (Display, Int) -> Unit,
    val onVolume: (Int, Boolean) -> Unit,
    val onMedia: (String) -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: UiState,
    actions: SectionActions,
    onOpen: (Section) -> Unit,
    modifier: Modifier = Modifier
) {
    var quick by remember { mutableStateOf<Section?>(null) }
    val sections = Section.entries.filter { it.availableIn(state) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { ConnectedDeviceCard(state) }

        if (sections.isEmpty()) {
            item {
                EmptyCard(
                    title = "No controls available",
                    body = "This desktop reported no controllable hardware. Check that the " +
                        "Switchboard daemon is running with access to your display and audio devices."
                )
            }
        }

        items(sections, key = { it.name }) { section ->
            SectionRow(
                section = section,
                summary = section.summaryOf(state),
                onClick = { onOpen(section) },
                onLongClick = { quick = section }
            )
        }
    }

    quick?.let { section ->
        ModalBottomSheet(
            onDismissRequest = { quick = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = section.icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = section.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                SectionBody(section, state, actions, compact = true)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SectionRow(
    section: Section,
    summary: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    Card(
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
                onClickLabel = "Open ${section.title}",
                onLongClickLabel = "${section.title} quick controls"
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = section.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.size(32.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectedDeviceCard(state: UiState) {
    val host = state.activeHost ?: return

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(46.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Computer,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = host.hostName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Text(
                            text = "${host.host}:${host.port}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .alpha(pulseAlpha)
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                        )
                        Text(
                            text = "Connected",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SectionScreen(
    section: Section,
    state: UiState,
    actions: SectionActions,
    modifier: Modifier = Modifier
) {
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

            Section.Audio -> VolumeCard(
                level = state.host.volume.level,
                muted = state.host.volume.muted,
                onVolume = actions.onVolume
            )

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
        }
    }
}
