package com.switchboard.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.switchboard.app.UiState
import com.switchboard.app.net.Display

/**
 * A group of host controls. Sections are what the home screen lists, what the
 * long-press sheet shows a shortcut to, and what a detail screen opens onto,
 * so there is exactly one place to add the next capability.
 */
enum class Section(val title: String, val icon: ImageVector) {
    Displays("Displays", Icons.Filled.Monitor),
    Audio("Audio", Icons.AutoMirrored.Filled.VolumeUp),
    Media("Media", Icons.Filled.MusicNote);

    fun availableIn(state: UiState): Boolean = when (this) {
        Displays -> state.canControlDisplay
        Audio -> state.canControlVolume
        Media -> state.canControlMedia
    }

    /** The line under the section name: its current state, at a glance. */
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

/** Every callback a section body needs, passed as one object to keep the
 *  signatures of the four composables that forward them readable. */
class SectionActions(
    val onBrightness: (Display, Int) -> Unit,
    val onContrast: (Display, Int) -> Unit,
    val onVolume: (Int, Boolean) -> Unit,
    val onMedia: (String) -> Unit
)

/**
 * The landing screen: the desktop this phone is driving, then one row per
 * group of controls.
 *
 * A tap opens the group's own screen. A press and hold opens the same controls
 * in a sheet over this one, which is the shorter path for the thing people
 * actually do most: nudge the volume, skip a track, and put the phone down.
 */
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
        verticalArrangement = Arrangement.spacedBy(12.dp)
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
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
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
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    // The buzz is what tells someone the hold registered before
                    // the sheet has drawn anything.
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
                onClickLabel = "Open ${section.title}",
                onLongClickLabel = "${section.title} quick controls"
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = section.icon,
                contentDescription = null, // the title beside it carries the meaning
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ConnectedDeviceCard(state: UiState) {
    val host = state.activeHost ?: return
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Computer,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = host.hostName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${host.host}:${host.port}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** One section on its own screen, with room for the detail the row omits. */
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
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SectionBody(section, state, actions, compact = false) }
    }
}

/**
 * The controls for one section.
 *
 * Shared verbatim by the detail screen and the quick sheet: [compact] trims the
 * readouts that only earn their space on a full screen, but never the controls
 * themselves, so the shortcut is never a lesser version of the real thing.
 */
@Composable
private fun SectionBody(
    section: Section,
    state: UiState,
    actions: SectionActions,
    compact: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
