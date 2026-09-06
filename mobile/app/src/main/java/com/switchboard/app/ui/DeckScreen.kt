package com.switchboard.app.ui

import android.app.Activity
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.switchboard.app.ConnectionStatus
import com.switchboard.app.UiState
import com.switchboard.app.net.DeckAction
import com.switchboard.app.net.DeckConfig
import com.switchboard.app.net.DeckInfobar
import com.switchboard.app.net.DeckKey
import com.switchboard.app.net.DeckPage
import com.switchboard.app.net.InstalledApp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Icon choice definition
private data class DeckIconItem(val id: String, val label: String, val icon: ImageVector)

private val DECK_ICONS = listOf(
    DeckIconItem("home", "Home", Icons.Filled.Home),
    DeckIconItem("notes", "Notes", Icons.Filled.Description),
    DeckIconItem("folder", "Files", Icons.Filled.Folder),
    DeckIconItem("calendar", "Calendar", Icons.Filled.CalendarToday),
    DeckIconItem("google", "Web", Icons.Filled.Language),
    DeckIconItem("youtube", "YouTube", Icons.Filled.PlayArrow),
    DeckIconItem("spotify", "Music", Icons.Filled.MusicNote),
    DeckIconItem("terminal", "Terminal", Icons.Filled.Terminal),
    DeckIconItem("play_pause", "Play/Pause", Icons.Filled.PlayArrow),
    DeckIconItem("skip_next", "Next", Icons.Filled.SkipNext),
    DeckIconItem("skip_prev", "Previous", Icons.Filled.SkipPrevious),
    DeckIconItem("volume_up", "Volume +", Icons.AutoMirrored.Filled.VolumeUp),
    DeckIconItem("volume_mute", "Mute", Icons.AutoMirrored.Filled.VolumeOff),
    DeckIconItem("lock", "Lock", Icons.Filled.Lock),
    DeckIconItem("camera", "Screenshot", Icons.Filled.PhotoCamera),
    DeckIconItem("code", "Code", Icons.Filled.Code),
    DeckIconItem("monitor", "Display", Icons.Filled.Monitor),
    DeckIconItem("settings", "Settings", Icons.Filled.Settings),
    DeckIconItem("work", "Work", Icons.Filled.Work)
)

private val TILE_COLOR_PRESETS = listOf(
    "#16161E", "#1A1B26", "#21222D", "#24283B",
    "#1F2335", "#1E1E2E", "#2D1F2D", "#1B2D26"
)

private val ICON_COLOR_PRESETS = listOf(
    "#7AA2F7", "#9ECE6A", "#BB9AF7", "#F7768E",
    "#2AC3DE", "#E0AF68", "#C0CAF5", "#FFFFFF"
)

private fun resolveIcon(iconId: String): ImageVector {
    return DECK_ICONS.find { it.id == iconId }?.icon ?: Icons.Filled.Code
}

private fun findBestMatchingDeckIcon(name: String): String? {
    val lower = name.lowercase(Locale.ROOT)
    return when {
        lower.contains("code") || lower.contains("visual studio") || lower.contains("git") || lower.contains("idea") -> "code"
        lower.contains("term") || lower.contains("cmd") || lower.contains("powershell") || lower.contains("bash") -> "terminal"
        lower.contains("chrome") || lower.contains("edge") || lower.contains("firefox") || lower.contains("browser") || lower.contains("brave") -> "google"
        lower.contains("spotify") || lower.contains("music") || lower.contains("itunes") -> "spotify"
        lower.contains("note") || lower.contains("word") || lower.contains("doc") || lower.contains("text") -> "notes"
        lower.contains("file") || lower.contains("explorer") || lower.contains("folder") -> "folder"
        lower.contains("snip") || lower.contains("camera") || lower.contains("screen") -> "camera"
        lower.contains("setting") || lower.contains("control") || lower.contains("task") -> "settings"
        lower.contains("calc") || lower.contains("calendar") -> "calendar"
        lower.contains("youtube") || lower.contains("video") || lower.contains("vlc") -> "youtube"
        lower.contains("discord") || lower.contains("slack") || lower.contains("mail") -> "work"
        else -> null
    }
}

private fun parseHexColor(hex: String?, fallback: Color): Color {
    if (hex.isNullOrBlank()) return fallback
    return runCatching {
        val clean = hex.trim().removePrefix("#")
        val colorInt = clean.toLong(16)
        if (clean.length == 6) {
            Color((0xFF000000 or colorInt).toInt())
        } else if (clean.length == 8) {
            Color(colorInt.toInt())
        } else {
            fallback
        }
    }.getOrDefault(fallback)
}

/**
 * Modern Switchboard Deck Screen.
 *
 * Sleek, responsive touch command surface matching the Desktop Deck UI.
 * Features:
 * - Tokyo Night Material 3 visual language
 * - Full portrait and landscape responsiveness
 * - Header with connectivity status and page management
 * - 2x4 squircle key command grid with customizable colors, icons, badges, and haptic feedback
 * - Dynamic infobar pill matching desktop modes (clock, media, page, text)
 * - Modal bottom sheets for key and infobar customization
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeckScreen(
    state: UiState,
    onAction: (keyIndex: Int, action: DeckAction, pageId: String?) -> Unit,
    onSaveConfig: (DeckConfig) -> Unit,
    onRefreshApps: () -> Unit = {},
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val view = LocalView.current
    val config = state.deckConfig

    // Keep screen awake while Deck is in active use
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view.keepScreenOn = true
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            view.keepScreenOn = false
        }
    }

    var activePageIdx by remember(config.activePage) { mutableIntStateOf(config.activePage) }
    var editMode by remember { mutableStateOf(false) }

    // Customization bottom sheet states
    var editingKey by remember { mutableStateOf<DeckKey?>(null) }
    var selectedKeyIndex by remember { mutableIntStateOf(0) }
    var editingInfobar by remember { mutableStateOf(false) }

    // Transient action feedback message
    var noticeMessage by remember { mutableStateOf("") }

    fun triggerClickHaptic() {
        if (!view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    fun triggerLongPressHaptic() {
        if (!view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    // Live clock ticker
    var clockTime by remember { mutableStateOf("") }
    var clockDate by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val timeFmt = SimpleDateFormat("h:mm a", Locale.US)
        val dateFmt = SimpleDateFormat("EEE, MMM d", Locale.US)
        while (true) {
            val now = Date()
            clockTime = timeFmt.format(now).uppercase(Locale.US)
            clockDate = dateFmt.format(now).uppercase(Locale.US)
            delay(1000)
        }
    }

    // Clear notice after brief delay
    LaunchedEffect(noticeMessage) {
        if (noticeMessage.isNotEmpty()) {
            delay(2200)
            noticeMessage = ""
        }
    }

    val pages = if (config.pages.isNotEmpty()) config.pages else listOf(DeckPage())
    val safePageIndex = activePageIdx.coerceIn(0, pages.size - 1)
    val currentPage = pages[safePageIndex]

    // Complete 8 keys for 2x4 layout
    val keys = remember(currentPage) {
        List(8) { i ->
            currentPage.keys.find { it.index == i } ?: DeckKey(
                index = i,
                title = "Key ${i + 1}",
                icon = "code",
                action = DeckAction("url", "https://google.com")
            )
        }
    }

    val handlePrevPage: () -> Unit = {
        triggerClickHaptic()
        if (pages.size > 1) {
            val nextIdx = (safePageIndex - 1 + pages.size) % pages.size
            activePageIdx = nextIdx
            onSaveConfig(config.copy(activePage = nextIdx))
        }
    }

    val handleNextPage: () -> Unit = {
        triggerClickHaptic()
        if (pages.size > 1) {
            val nextIdx = (safePageIndex + 1) % pages.size
            activePageIdx = nextIdx
            onSaveConfig(config.copy(activePage = nextIdx))
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        val isLandscape = maxWidth > maxHeight

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = if (isLandscape) 14.dp else 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // TOP HEADER & NAVIGATION
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left: Back button + Title & Status
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            onClick = {
                                triggerClickHaptic()
                                onBack()
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Switchboard Deck",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )

                                // Status Pill
                                val isConnected = state.status == ConnectionStatus.Connected
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isConnected) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHigh
                                    },
                                    border = BorderStroke(
                                        1.dp,
                                        if (isConnected) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                                        else MaterialTheme.colorScheme.outlineVariant
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    if (isConnected) Color(0xFF34D399)
                                                    else MaterialTheme.colorScheme.outline
                                                )
                                        )
                                        Text(
                                            text = if (isConnected) "Connected" else "Macro Console",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (isConnected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Right: Edit Mode Toggle
                    FilterChip(
                        selected = editMode,
                        onClick = {
                            triggerClickHaptic()
                            editMode = !editMode
                        },
                        label = {
                            Text(
                                text = if (editMode) "Editing" else "Edit Mode",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (editMode) Icons.Filled.Check else Icons.Filled.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp)
                            )
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            iconColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = editMode,
                            borderColor = MaterialTheme.colorScheme.outlineVariant,
                            selectedBorderColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.height(32.dp)
                    )
                }

                // Page Tabs Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Scrollable Page Pill Tabs
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        pages.forEachIndexed { idx, p ->
                            val isActive = idx == safePageIndex
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isActive) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerLow,
                                border = BorderStroke(
                                    1.dp,
                                    if (isActive) MaterialTheme.colorScheme.secondary
                                    else MaterialTheme.colorScheme.outlineVariant
                                ),
                                modifier = Modifier
                                    .height(28.dp)
                                    .clickable {
                                        triggerClickHaptic()
                                        activePageIdx = idx
                                        onSaveConfig(config.copy(activePage = idx))
                                    }
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(horizontal = 10.dp)
                                ) {
                                    Text(
                                        text = p.name.ifEmpty { "Page ${idx + 1}" },
                                        fontSize = 11.sp,
                                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isActive) MaterialTheme.colorScheme.onSecondaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // Add Page button (+)
                        IconButton(
                            onClick = {
                                triggerClickHaptic()
                                val newPageNum = config.pages.size + 1
                                val newPages = config.pages + DeckPage(
                                    id = "page-${System.currentTimeMillis()}",
                                    name = "Page $newPageNum"
                                )
                                val newActive = newPages.size - 1
                                activePageIdx = newActive
                                onSaveConfig(config.copy(pages = newPages, activePage = newActive))
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = "Add Page",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(17.dp)
                            )
                        }

                        // Delete Page button (if > 1 page)
                        if (pages.size > 1) {
                            IconButton(
                                onClick = {
                                    triggerClickHaptic()
                                    val newPages = config.pages.filterIndexed { idx, _ -> idx != safePageIndex }
                                    val newActive = (safePageIndex - 1).coerceAtLeast(0)
                                    activePageIdx = newActive
                                    onSaveConfig(config.copy(pages = newPages, activePage = newActive))
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = "Delete Page",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                // Subtitle / Feedback helper banner
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AnimatedContent(
                        targetState = noticeMessage,
                        transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
                        label = "deckNotice"
                    ) { notice ->
                        if (notice.isNotEmpty()) {
                            Text(
                                text = notice,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        } else {
                            Text(
                                text = if (editMode) "Edit Mode: Tap key to customize • Tap infobar for modes"
                                else "Tap key to execute • Long-press to customize",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        }
                    }

                    Text(
                        text = "${currentPage.name} • ${safePageIndex + 1}/${pages.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // 2x4 KEY COMMAND SURFACE
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AnimatedContent(
                        targetState = safePageIndex,
                        transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
                        label = "deckKeysTransition"
                    ) { _ ->
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Row 1: Keys 0..3
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                for (i in 0..3) {
                                    val key = keys[i]
                                    ModernDeckKeyCard(
                                        key = key,
                                        isSelected = selectedKeyIndex == key.index,
                                        editMode = editMode,
                                        modifier = Modifier
                                            .weight(1f)
                                            .aspectRatio(if (isLandscape) 1.25f else 1.05f),
                                        onClick = {
                                            triggerClickHaptic()
                                            selectedKeyIndex = key.index
                                            if (editMode) {
                                                editingKey = key
                                            } else {
                                                noticeMessage = "Executed ${key.title.ifEmpty { "Key ${key.index + 1}" }}"
                                                onAction(key.index, key.action, currentPage.id)
                                            }
                                        },
                                        onLongClick = {
                                            triggerLongPressHaptic()
                                            selectedKeyIndex = key.index
                                            editingKey = key
                                        }
                                    )
                                }
                            }

                            // Row 2: Keys 4..7
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                for (i in 4..7) {
                                    val key = keys[i]
                                    ModernDeckKeyCard(
                                        key = key,
                                        isSelected = selectedKeyIndex == key.index,
                                        editMode = editMode,
                                        modifier = Modifier
                                            .weight(1f)
                                            .aspectRatio(if (isLandscape) 1.25f else 1.05f),
                                        onClick = {
                                            triggerClickHaptic()
                                            selectedKeyIndex = key.index
                                            if (editMode) {
                                                editingKey = key
                                            } else {
                                                noticeMessage = "Executed ${key.title.ifEmpty { "Key ${key.index + 1}" }}"
                                                onAction(key.index, key.action, currentPage.id)
                                            }
                                        },
                                        onLongClick = {
                                            triggerLongPressHaptic()
                                            selectedKeyIndex = key.index
                                            editingKey = key
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // DYNAMIC INFOBAR ROW
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Prev Page Button
                IconButton(
                    onClick = handlePrevPage,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Previous Page",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Infobar Central Pill
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .combinedClickable(
                            onClick = {
                                triggerClickHaptic()
                                if (editMode) {
                                    editingInfobar = true
                                } else {
                                    // Cycle to next page on tap or show edit hint
                                    handleNextPage()
                                }
                            },
                            onLongClick = {
                                triggerLongPressHaptic()
                                editingInfobar = true
                            }
                        )
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp)
                    ) {
                        val mode = config.infobar.mode
                        when {
                            mode == "media" && state.host.media.active && state.host.media.title.isNotEmpty() -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.MusicNote,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "${state.host.media.title} — ${state.host.media.artist}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            mode == "media" -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.MusicNote,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "No media playing",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            mode == "page" -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = "${currentPage.name}  •  Page ${safePageIndex + 1} of ${pages.size}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                            mode == "text" && config.infobar.customText.isNotEmpty() -> {
                                Text(
                                    text = config.infobar.customText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            else -> {
                                // Default Clock Mode: Clean and modern date & time readout
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Schedule,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Text(
                                            text = clockDate,
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.Medium,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(
                                        text = clockTime,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }

                // Next Page Button
                IconButton(
                    onClick = handleNextPage,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Next Page",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }

    // Key Customizer Bottom Sheet
    editingKey?.let { targetKey ->
        KeyCustomizerSheet(
            key = targetKey,
            installedApps = state.installedApps,
            onRefreshApps = onRefreshApps,
            onDismiss = { editingKey = null },
            onSave = { updatedKey ->
                val updatedKeys = currentPage.keys.filter { it.index != updatedKey.index } + updatedKey
                val updatedPages = config.pages.mapIndexed { idx, p ->
                    if (idx == safePageIndex) p.copy(keys = updatedKeys) else p
                }
                onSaveConfig(config.copy(pages = updatedPages))
                editingKey = null
            }
        )
    }

    // Infobar Customizer Bottom Sheet
    if (editingInfobar) {
        InfobarCustomizerSheet(
            infobar = config.infobar,
            onDismiss = { editingInfobar = false },
            onSave = { updatedInfobar ->
                onSaveConfig(config.copy(infobar = updatedInfobar))
                editingInfobar = false
            }
        )
    }
}

/**
 * Modern Squircle Key Card for the 2x4 command surface.
 */
@Composable
private fun ModernDeckKeyCard(
    key: DeckKey,
    isSelected: Boolean,
    editMode: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val defaultBg = MaterialTheme.colorScheme.surfaceContainerLowest
    val defaultIconColor = MaterialTheme.colorScheme.secondary
    val bgColor = parseHexColor(key.bgColor, defaultBg)
    val iconColor = parseHexColor(key.iconColor, defaultIconColor)
    val iconVector = resolveIcon(key.icon)

    Box(
        modifier = modifier
            .shadow(if (isSelected) 4.dp else 2.dp, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = when {
                    isSelected -> MaterialTheme.colorScheme.primary
                    editMode -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                },
                shape = RoundedCornerShape(14.dp)
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(6.dp),
        contentAlignment = Alignment.Center
    ) {
        // Subtle glass top sheen highlight
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.42f)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.08f), Color.Transparent)
                    )
                )
        )

        // Notification badge indicator
        if (!key.badge.isNullOrEmpty()) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .align(Alignment.TopEnd)
                    .clip(CircleShape)
                    .background(Color(0xFF34D399))
                    .border(1.dp, MaterialTheme.colorScheme.background, CircleShape)
            )
        }

        // Edit mode index badge
        if (editMode) {
            Text(
                text = "${key.index + 1}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 2.dp, top = 2.dp)
            )
        }

        // Main Icon & Title
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = iconVector,
                contentDescription = key.title,
                tint = iconColor,
                modifier = Modifier.size(28.dp)
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = key.title.ifEmpty { "Key ${key.index + 1}" },
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Key Customizer Modal Bottom Sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeyCustomizerSheet(
    key: DeckKey,
    installedApps: List<InstalledApp> = emptyList(),
    onRefreshApps: () -> Unit = {},
    onDismiss: () -> Unit,
    onSave: (DeckKey) -> Unit
) {
    var title by remember { mutableStateOf(key.title) }
    var selectedIcon by remember { mutableStateOf(key.icon) }
    var actionType by remember { mutableStateOf(key.action.type) }
    var actionValue by remember { mutableStateOf(key.action.value) }
    var hasBadge by remember { mutableStateOf(!key.badge.isNullOrEmpty()) }
    var bgColorHex by remember { mutableStateOf(key.bgColor ?: "#1A1B26") }
    var iconColorHex by remember { mutableStateOf(key.iconColor ?: "#7AA2F7") }
    var appSearch by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Sheet Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Configure Key #${key.index + 1}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Customize action, appearance, and labels",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Reset Key Button
                TextButton(
                    onClick = {
                        title = "Key ${key.index + 1}"
                        selectedIcon = "code"
                        actionType = "url"
                        actionValue = "https://google.com"
                        bgColorHex = "#1A1B26"
                        iconColorHex = "#7AA2F7"
                        hasBadge = false
                    }
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.RotateLeft,
                        contentDescription = "Reset Key",
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Reset", fontSize = 12.sp)
                }
            }

            // Key Label Input
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Label / Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // Action Type Filter Chips
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Action Type",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("app", "url", "hotkey", "media", "system").forEach { type ->
                        val isSelected = actionType == type
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                actionType = type
                                actionValue = when (type) {
                                    "url" -> if (actionValue.startsWith("http")) actionValue else "https://google.com"
                                    "hotkey" -> "win+d"
                                    "media" -> "toggle"
                                    "system" -> "lock"
                                    else -> "notepad"
                                }
                            },
                            label = { Text(type.uppercase(Locale.US), fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                }
            }

            // Action Value Configuration
            when (actionType) {
                "app" -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Installed Desktop Applications",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            IconButton(onClick = onRefreshApps, modifier = Modifier.size(24.dp)) {
                                Icon(
                                    imageVector = Icons.Filled.Refresh,
                                    contentDescription = "Refresh Apps",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        OutlinedTextField(
                            value = appSearch,
                            onValueChange = { appSearch = it },
                            label = { Text("Search desktop applications...") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        val filteredApps = remember(installedApps, appSearch) {
                            if (appSearch.isEmpty()) installedApps
                            else installedApps.filter {
                                it.name.contains(appSearch, ignoreCase = true) ||
                                it.path.contains(appSearch, ignoreCase = true)
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLowest,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp)
                        ) {
                            if (filteredApps.isNotEmpty()) {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(4.dp)
                                ) {
                                    items(filteredApps.size) { idx ->
                                        val app = filteredApps[idx]
                                        val isChosen = actionValue == app.path || actionValue == app.name
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(
                                                    if (isChosen) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                                                    else Color.Transparent
                                                )
                                                .clickable {
                                                    actionValue = app.path.ifEmpty { app.name }
                                                    if (title.startsWith("Key ") || title.isEmpty()) {
                                                        title = app.name
                                                    }
                                                    findBestMatchingDeckIcon(app.name)?.let {
                                                        selectedIcon = it
                                                    }
                                                }
                                                .padding(horizontal = 8.dp, vertical = 6.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.weight(1f, fill = false)
                                            ) {
                                                Icon(
                                                    imageVector = resolveIcon(
                                                        app.icon.ifBlank {
                                                            findBestMatchingDeckIcon(app.name) ?: "code"
                                                        }
                                                    ),
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.secondary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    text = app.name,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            Text(
                                                text = app.path.substringAfterLast('\\').substringAfterLast('/'),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 9.sp,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            } else {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (installedApps.isEmpty()) "Click refresh above to scan desktop apps"
                                        else "No matching apps found",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = actionValue,
                            onValueChange = { actionValue = it },
                            label = { Text("Command or Executable Path") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                "url" -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = actionValue,
                            onValueChange = { actionValue = it },
                            label = { Text("Web URL") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                "Google" to "https://google.com",
                                "YouTube" to "https://youtube.com",
                                "GitHub" to "https://github.com",
                                "Reddit" to "https://reddit.com",
                                "Spotify" to "https://open.spotify.com"
                            ).forEach { (label, url) ->
                                OutlinedButton(
                                    onClick = {
                                        actionValue = url
                                        if (title.startsWith("Key ") || title.isEmpty()) title = label
                                        findBestMatchingDeckIcon(label)?.let { selectedIcon = it }
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text(label, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
                "hotkey" -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = actionValue,
                            onValueChange = { actionValue = it },
                            label = { Text("Shortcut Chord (e.g. win+d, ctrl+c, alt+tab)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf("win+d", "ctrl+c", "ctrl+v", "alt+tab", "win+l", "ctrl+shift+esc").forEach { chord ->
                                OutlinedButton(
                                    onClick = { actionValue = chord },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text(chord, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }
                "media" -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = actionValue,
                            onValueChange = { actionValue = it },
                            label = { Text("Media Command (toggle, next, prev, vol_up, vol_down, mute)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                "Play/Pause" to "toggle",
                                "Next" to "next",
                                "Prev" to "prev",
                                "Vol +" to "vol_up",
                                "Vol -" to "vol_down",
                                "Mute" to "mute"
                            ).forEach { (label, cmd) ->
                                OutlinedButton(
                                    onClick = { actionValue = cmd },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text(label, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
                "system" -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = actionValue,
                            onValueChange = { actionValue = it },
                            label = { Text("System Command (lock, screenshot, bright_up, bright_down)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                "Lock PC" to "lock",
                                "Screenshot" to "screenshot",
                                "Bright +" to "bright_up",
                                "Bright -" to "bright_down",
                                "Sleep" to "sleep"
                            ).forEach { (label, cmd) ->
                                OutlinedButton(
                                    onClick = { actionValue = cmd },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text(label, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }

            // Icon Picker
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Icon",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(DECK_ICONS) { item ->
                        val isSelected = selectedIcon == item.id
                        Surface(
                            onClick = { selectedIcon = item.id },
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerLowest,
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = item.label,
                                    tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Color Customization: Tile Color & Icon Color
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Tile Color
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Tile Background Color",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TILE_COLOR_PRESETS.forEach { hex ->
                            val color = parseHexColor(hex, Color.DarkGray)
                            val isChosen = bgColorHex.equals(hex, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(
                                        width = if (isChosen) 2.dp else 1.dp,
                                        color = if (isChosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                        shape = CircleShape
                                    )
                                    .clickable { bgColorHex = hex }
                            )
                        }
                    }
                }

                // Icon Color
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Icon Tint Color",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ICON_COLOR_PRESETS.forEach { hex ->
                            val color = parseHexColor(hex, Color.White)
                            val isChosen = iconColorHex.equals(hex, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(
                                        width = if (isChosen) 2.dp else 1.dp,
                                        color = if (isChosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                        shape = CircleShape
                                    )
                                    .clickable { iconColorHex = hex }
                            )
                        }
                    }
                }

                // Hex input fields
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = bgColorHex,
                        onValueChange = { bgColorHex = it },
                        label = { Text("Tile Hex") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = iconColorHex,
                        onValueChange = { iconColorHex = it },
                        label = { Text("Icon Hex") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
            }

            // Notification Badge Option
            FilterChip(
                selected = hasBadge,
                onClick = { hasBadge = !hasBadge },
                label = { Text("Notification Dot Badge") },
                shape = RoundedCornerShape(10.dp)
            )

            // Save and Cancel Buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        onSave(
                            key.copy(
                                title = title,
                                icon = selectedIcon,
                                bgColor = bgColorHex,
                                iconColor = iconColorHex,
                                badge = if (hasBadge) "active" else null,
                                action = DeckAction(type = actionType, value = actionValue)
                            )
                        )
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Save Changes")
                }
            }
        }
    }
}

/**
 * Infobar Customizer Modal Bottom Sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InfobarCustomizerSheet(
    infobar: DeckInfobar,
    onDismiss: () -> Unit,
    onSave: (DeckInfobar) -> Unit
) {
    var mode by remember { mutableStateOf(infobar.mode) }
    var customText by remember { mutableStateOf(infobar.customText) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Infobar Display Settings",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = "Choose what information is rendered in the central deck pill",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    "clock" to "Clock & Date",
                    "media" to "Now Playing",
                    "page" to "Page Info",
                    "text" to "Custom Text"
                ).forEach { (m, label) ->
                    val isSelected = mode == m
                    FilterChip(
                        selected = isSelected,
                        onClick = { mode = m },
                        label = { Text(label, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }

            if (mode == "text") {
                OutlinedTextField(
                    value = customText,
                    onValueChange = { customText = it },
                    label = { Text("Custom Text") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        onSave(infobar.copy(mode = mode, customText = customText))
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Apply")
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
