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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.RotateLeft
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
 * Authentic Full-Screen Physical Stream Deck Neo Console for Android.
 * Designed with zero bulky toolbars, perfect non-overlapping keys, rich Tokyo Night theming,
 * OLED Infobar, and capacitive touch point page navigation.
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

    // Keep screen awake while Deck is actively visible
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

    // Customization bottom sheets
    var editingKey by remember { mutableStateOf<DeckKey?>(null) }
    var selectedKeyIndex by remember { mutableIntStateOf(0) }
    var editingInfobar by remember { mutableStateOf(false) }

    // Haptic helpers
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
                .padding(
                    horizontal = if (isLandscape) 12.dp else 10.dp,
                    vertical = if (isLandscape) 4.dp else 8.dp
                ),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // COMPACT TOP CHROME (Only ~28dp tall: Back button, Page Indicator Capsule, and Edit Mode toggle)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Discreet exit back button
                IconButton(
                    onClick = {
                        triggerClickHaptic()
                        onBack()
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Exit Deck",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Center: Page Badge Capsule (Click to cycle pages)
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.clickable { handleNextPage() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = currentPage.name.ifEmpty { "Page ${safePageIndex + 1}" },
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${safePageIndex + 1}/${pages.size}",
                            fontSize = 9.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Right: Page Operations and Edit Toggle
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // Add page button
                    IconButton(
                        onClick = {
                            triggerClickHaptic()
                            val newPages = config.pages + DeckPage(
                                id = "page-${System.currentTimeMillis()}",
                                name = "Page ${config.pages.size + 1}"
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
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp)
                        )
                    }

                    // Delete page button (if > 1 page)
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
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }

                    // Edit Mode Toggle
                    IconButton(
                        onClick = {
                            triggerClickHaptic()
                            editMode = !editMode
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = if (editMode) Icons.Filled.Check else Icons.Filled.Edit,
                            contentDescription = "Edit Mode",
                            tint = if (editMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // PHYSICAL DECK HARDWARE CASING (Fills remaining height)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 2x4 KEY COMMAND SURFACE (Shares 100% of grid height evenly - ZERO OVERLAP GUARANTEED)
                    AnimatedContent(
                        targetState = safePageIndex,
                        transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(140)) },
                        label = "pageKeysTransition",
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) { _ ->
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Row 1: Keys 0..3 (Takes exact 50% height)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                for (i in 0..3) {
                                    val key = keys[i]
                                    PhysicalDeckKeyButton(
                                        key = key,
                                        isSelected = selectedKeyIndex == key.index,
                                        editMode = editMode,
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight(),
                                        onClick = {
                                            triggerClickHaptic()
                                            selectedKeyIndex = key.index
                                            if (editMode) {
                                                editingKey = key
                                            } else {
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

                            // Row 2: Keys 4..7 (Takes exact 50% height)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                for (i in 4..7) {
                                    val key = keys[i]
                                    PhysicalDeckKeyButton(
                                        key = key,
                                        isSelected = selectedKeyIndex == key.index,
                                        editMode = editMode,
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight(),
                                        onClick = {
                                            triggerClickHaptic()
                                            selectedKeyIndex = key.index
                                            if (editMode) {
                                                editingKey = key
                                            } else {
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

                    Spacer(Modifier.height(8.dp))

                    // DYNAMIC INFOBAR & HARDWARE TOUCH POINTS
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(32.dp)
                            .padding(horizontal = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left Touch Point (< Prev Page)
                        HardwareTouchPoint(
                            onClick = handlePrevPage,
                            isLeft = true
                        )

                        // Central OLED Infobar Capsule
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerLowest,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier
                                .weight(1f)
                                .height(30.dp)
                                .padding(horizontal = 10.dp)
                                .combinedClickable(
                                    onClick = {
                                        triggerClickHaptic()
                                        if (editMode) {
                                            editingInfobar = true
                                        } else {
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
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                text = "${state.host.media.title} — ${state.host.media.artist}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    mode == "page" -> {
                                        Text(
                                            text = "${currentPage.name} • Page ${safePageIndex + 1} of ${pages.size}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    mode == "text" && config.infobar.customText.isNotEmpty() -> {
                                        Text(
                                            text = config.infobar.customText,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    else -> {
                                        // Standard OLED Clock & Date
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = clockDate,
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = clockTime,
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Right Touch Point (> Next Page)
                        HardwareTouchPoint(
                            onClick = handleNextPage,
                            isLeft = false
                        )
                    }
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
 * Authentic Physical Squircle Key Button with glass reflection, centered icon, title, and badge.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PhysicalDeckKeyButton(
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
                    isSelected -> MaterialTheme.colorScheme.secondary
                    editMode -> MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                    else -> Color.White.copy(alpha = 0.08f)
                },
                shape = RoundedCornerShape(14.dp)
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        // Physical LCD Glass top sheen reflection
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.42f)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.12f), Color.Transparent)
                    )
                )
        )

        // Notification badge indicator (top-right)
        if (!key.badge.isNullOrEmpty()) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .align(Alignment.TopEnd)
                    .clip(CircleShape)
                    .background(Color(0xFF34D399))
                    .border(0.5.dp, Color.Black.copy(alpha = 0.5f), CircleShape)
            )
        }

        // Edit mode index tag (top-left)
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
                modifier = Modifier.size(26.dp)
            )

            if (key.title.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = key.title,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Capacitive Touch Point Button with illuminated indicator line and chevron.
 */
@Composable
private fun HardwareTouchPoint(
    onClick: () -> Unit,
    isLeft: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Icon(
            imageVector = if (isLeft) Icons.AutoMirrored.Filled.ArrowBack else Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = if (isLeft) "Previous Page" else "Next Page",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
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
                            else installedApps.filter { it.name.contains(appSearch, ignoreCase = true) || it.path.contains(appSearch, ignoreCase = true) }
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                        ) {
                            if (filteredApps.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = if (installedApps.isEmpty()) "No apps discovered yet. Tap refresh." else "No matching applications",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    items(filteredApps) { app ->
                                        val isChosen = actionValue == app.path || actionValue == app.name
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    actionValue = app.path.ifEmpty { app.name }
                                                    if (title.startsWith("Key ")) {
                                                        title = app.name
                                                    }
                                                    findBestMatchingDeckIcon(app.name)?.let { matched ->
                                                        selectedIcon = matched
                                                    }
                                                }
                                                .background(if (isChosen) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f) else Color.Transparent)
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Icon(
                                                imageVector = resolveIcon(app.icon.ifEmpty { "code" }),
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = app.name,
                                                    fontSize = 12.sp,
                                                    fontWeight = if (isChosen) FontWeight.Bold else FontWeight.Normal,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                if (app.path.isNotEmpty()) {
                                                    Text(
                                                        text = app.path,
                                                        fontSize = 10.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                "url" -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = actionValue,
                            onValueChange = { actionValue = it },
                            label = { Text("Destination URL") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("https://google.com", "https://youtube.com", "https://github.com").forEach { quickUrl ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier.clickable { actionValue = quickUrl }
                                ) {
                                    Text(
                                        text = quickUrl.removePrefix("https://").substringBefore(".com"),
                                        fontSize = 10.sp,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        color = MaterialTheme.colorScheme.primary
                                    )
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
                            label = { Text("Keyboard Shortcut (e.g. win+d, ctrl+shift+esc)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("win+d", "win+shift+s", "ctrl+c", "ctrl+v").forEach { chord ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier.clickable { actionValue = chord }
                                ) {
                                    Text(
                                        text = chord,
                                        fontSize = 10.sp,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
                "media" -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Media Command", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("toggle", "next", "prev", "vol_up", "vol_down", "mute").forEach { cmd ->
                                val isSelected = actionValue == cmd
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier.clickable { actionValue = cmd }
                                ) {
                                    Text(
                                        text = cmd,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                "system" -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("System Command", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("lock", "screensaver", "sleep").forEach { cmd ->
                                val isSelected = actionValue == cmd
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier.clickable { actionValue = cmd }
                                ) {
                                    Text(
                                        text = cmd,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Icon Picker Grid
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Key Icon",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DECK_ICONS.take(8).forEach { iconItem ->
                        val isSelected = selectedIcon == iconItem.id
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                                .border(1.dp, if (isSelected) MaterialTheme.colorScheme.secondary else Color.Transparent, RoundedCornerShape(8.dp))
                                .clickable { selectedIcon = iconItem.id },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = iconItem.icon,
                                contentDescription = iconItem.label,
                                tint = if (isSelected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DECK_ICONS.drop(8).take(8).forEach { iconItem ->
                        val isSelected = selectedIcon == iconItem.id
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                                .border(1.dp, if (isSelected) MaterialTheme.colorScheme.secondary else Color.Transparent, RoundedCornerShape(8.dp))
                                .clickable { selectedIcon = iconItem.id },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = iconItem.icon,
                                contentDescription = iconItem.label,
                                tint = if (isSelected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // Tile Background & Icon Color Swatches
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Tile Background Color
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Tile Color", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TILE_COLOR_PRESETS.take(4).forEach { hex ->
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(parseHexColor(hex, Color.DarkGray))
                                    .border(1.dp, if (bgColorHex.equals(hex, ignoreCase = true)) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                                    .clickable { bgColorHex = hex }
                            )
                        }
                    }
                }

                // Icon Accent Color
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Icon Color", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ICON_COLOR_PRESETS.take(4).forEach { hex ->
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(parseHexColor(hex, Color.White))
                                    .border(1.dp, if (iconColorHex.equals(hex, ignoreCase = true)) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                                    .clickable { iconColorHex = hex }
                            )
                        }
                    }
                }
            }

            // Save Key Button
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
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(
                    text = "Save Key Changes",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
            Spacer(Modifier.height(10.dp))
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
    var selectedMode by remember { mutableStateOf(infobar.mode) }
    var customText by remember { mutableStateOf(infobar.customText) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Stream Deck Neo Infobar",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Choose the dynamic content shown on the center OLED capsule display:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Mode Selector
            val modes = listOf(
                "clock" to "Clock & Date",
                "media" to "Now Playing",
                "page" to "Page Indicator",
                "text" to "Custom Text"
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                modes.forEach { (mode, label) ->
                    val isSelected = selectedMode == mode
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedMode = mode }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = label,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
                            )
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (selectedMode == "text") {
                OutlinedTextField(
                    value = customText,
                    onValueChange = { customText = it },
                    label = { Text("Display text") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Button(
                onClick = {
                    onSave(infobar.copy(mode = selectedMode, customText = customText))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Save Infobar Mode", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
