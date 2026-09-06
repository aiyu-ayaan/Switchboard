package com.switchboard.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalHapticFeedback
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
    DeckIconItem("google", "Google", Icons.Filled.Language),
    DeckIconItem("youtube", "YouTube", Icons.Filled.PlayArrow),
    DeckIconItem("spotify", "Spotify", Icons.Filled.MusicNote),
    DeckIconItem("linkedin", "LinkedIn", Icons.Filled.Work),
    DeckIconItem("terminal", "Terminal", Icons.Filled.Terminal),
    DeckIconItem("play_pause", "Media", Icons.Filled.PlayArrow),
    DeckIconItem("volume_up", "Volume", Icons.AutoMirrored.Filled.VolumeUp),
    DeckIconItem("volume_mute", "Mute", Icons.AutoMirrored.Filled.VolumeOff),
    DeckIconItem("lock", "Lock", Icons.Filled.Lock),
    DeckIconItem("camera", "Screenshot", Icons.Filled.PhotoCamera),
    DeckIconItem("code", "Code", Icons.Filled.Code),
    DeckIconItem("monitor", "Display", Icons.Filled.Monitor),
    DeckIconItem("settings", "Settings", Icons.Filled.Settings)
)

private fun resolveIcon(iconId: String): ImageVector {
    return DECK_ICONS.find { it.id == iconId }?.icon ?: Icons.Filled.Code
}

private fun findBestMatchingDeckIcon(name: String): String? {
    val lower = name.lowercase(Locale.ROOT)
    return when {
        lower.contains("code") || lower.contains("visual studio") || lower.contains("git") -> "code"
        lower.contains("term") || lower.contains("cmd") || lower.contains("powershell") -> "terminal"
        lower.contains("chrome") || lower.contains("edge") || lower.contains("firefox") || lower.contains("browser") -> "google"
        lower.contains("spotify") || lower.contains("music") -> "spotify"
        lower.contains("note") || lower.contains("word") || lower.contains("doc") -> "notes"
        lower.contains("file") || lower.contains("explorer") -> "folder"
        lower.contains("snip") || lower.contains("camera") || lower.contains("screen") -> "camera"
        lower.contains("setting") || lower.contains("control") || lower.contains("task") -> "settings"
        lower.contains("calc") || lower.contains("calendar") -> "calendar"
        else -> null
    }
}

private fun parseHexColor(hex: String?, fallback: Color): Color {
    if (hex.isNullOrEmpty()) return fallback
    return runCatching {
        val clean = hex.removePrefix("#")
        val colorInt = clean.toLong(16)
        if (clean.length == 6) {
            Color((0xFF000000 or colorInt).toInt())
        } else {
            Color(colorInt.toInt())
        }
    }.getOrDefault(fallback)
}

/**
 * Authentic Elgato Stream Deck Neo view for Android.
 * Replicates the hardware face: 8 squircle LCD keys in 2x4 grid, dynamic Infobar,
 * and capacitive touch points with glowing LED indicators.
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
    val haptics = LocalHapticFeedback.current
    val config = state.deckConfig
    var activePageIdx by remember(config.activePage) { mutableIntStateOf(config.activePage) }
    var editMode by remember { mutableStateOf(false) }

    // Selected key for bottom sheet customization
    var editingKey by remember { mutableStateOf<DeckKey?>(null) }
    var editingInfobar by remember { mutableStateOf(false) }

    // Live clock ticker
    var clockTime by remember { mutableStateOf("") }
    var clockDate by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val timeFmt = SimpleDateFormat("h:mm a", Locale.US)
        val dateFmt = SimpleDateFormat("EEEE d/M/yy", Locale.US)
        while (true) {
            val now = Date()
            clockTime = timeFmt.format(now).uppercase()
            clockDate = dateFmt.format(now).uppercase()
            delay(1000)
        }
    }

    val pages = if (config.pages.isNotEmpty()) config.pages else listOf(DeckPage())
    val safePageIndex = activePageIdx.coerceIn(0, pages.size - 1)
    val currentPage = pages[safePageIndex]

    // Complete 8 keys
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

    val handlePrevPage = {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        if (pages.size > 1) {
            activePageIdx = (safePageIndex - 1 + pages.size) % pages.size
            onSaveConfig(config.copy(activePage = activePageIdx))
        }
    }

    val handleNextPage = {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        if (pages.size > 1) {
            activePageIdx = (safePageIndex + 1) % pages.size
            onSaveConfig(config.copy(activePage = activePageIdx))
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0B0C13)),
        contentAlignment = Alignment.Center
    ) {
        // Main Stream Deck Neo hardware body - Fullscreen dark console
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .shadow(20.dp, RoundedCornerShape(26.dp)),
            shape = RoundedCornerShape(26.dp),
            color = Color(0xFF13141F), // Dark matte console casing
            border = androidx.compose.foundation.BorderStroke(1.2.dp, Color(0xFF26283A))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Header: Integrated Back button, Page title, Central Emblem & Edit Mode Toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Exit Deck",
                                tint = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Page indicator / name
                        Text(
                            text = "${currentPage.name} (${safePageIndex + 1}/${pages.size})",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.7f),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Stream Deck Neo Central Emblem
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E2032))
                            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "G",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    // Customize Mode Toggle Button
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (editMode) {
                            IconButton(
                                onClick = {
                                    val newPages = config.pages + DeckPage(
                                        id = "page-${System.currentTimeMillis()}",
                                        name = "Page ${config.pages.size + 1}"
                                    )
                                    onSaveConfig(config.copy(pages = newPages))
                                },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = "Add Page", tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(16.dp))
                            }
                        }

                        IconButton(
                            onClick = { editMode = !editMode },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = "Customize Mode",
                                tint = if (editMode) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.5f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                // 8 Squircle LCD Keys Grid (2 rows x 4 columns)
                AnimatedContent(
                    targetState = safePageIndex,
                    transitionSpec = {
                        fadeIn(tween(180)) togetherWith fadeOut(tween(180))
                    },
                    label = "pageKeysAnim",
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = 4.dp)
                ) { _ ->
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Top Row: Keys 0..3
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            for (i in 0..3) {
                                val key = keys[i]
                                NeoKeyButton(
                                    key = key,
                                    editMode = editMode,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        if (editMode) {
                                            editingKey = key
                                        } else {
                                            onAction(key.index, key.action, currentPage.id)
                                        }
                                    },
                                    onLongClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        editingKey = key
                                    }
                                )
                            }
                        }

                        // Bottom Row: Keys 4..7
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            for (i in 4..7) {
                                val key = keys[i]
                                NeoKeyButton(
                                    key = key,
                                    editMode = editMode,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        if (editMode) {
                                            editingKey = key
                                        } else {
                                            onAction(key.index, key.action, currentPage.id)
                                        }
                                    },
                                    onLongClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        editingKey = key
                                    }
                                )
                            }
                        }
                    }
                }

                // Bottom Strip: Left Touch Point + Infobar + Right Touch Point
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left Touch Point (Prev Page)
                    TouchPointButton(onClick = handlePrevPage)

                    // Central Infobar
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(28.dp)
                            .padding(horizontal = 16.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF08090E))
                            .border(1.dp, Color(0xFF222538), RoundedCornerShape(14.dp))
                            .combinedClickable(
                                onClick = {
                                    if (editMode) editingInfobar = true
                                },
                                onLongClick = {
                                    editingInfobar = true
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        val mode = config.infobar.mode
                        when {
                            mode == "media" && state.host.media.active && state.host.media.title.isNotEmpty() -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.MusicNote,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = "${state.host.media.title} — ${state.host.media.artist}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            mode == "page" -> {
                                Text(
                                    text = "${currentPage.name} • Page ${safePageIndex + 1} of ${pages.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            mode == "text" && config.infobar.customText.isNotEmpty() -> {
                                Text(
                                    text = config.infobar.customText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            else -> {
                                // Authentic Stream Deck Neo Infobar Clock & Date format
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = clockDate,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color.White.copy(alpha = 0.65f)
                                    )
                                    Text(
                                        text = clockTime,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color(0xFF67E8F9)
                                    )
                                }
                            }
                        }
                    }

                    // Right Touch Point (Next Page)
                    TouchPointButton(onClick = handleNextPage)
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
 * Squircle LCD Key Button with glossy glass highlight, badge support, and haptics.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun NeoKeyButton(
    key: DeckKey,
    editMode: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val bgColor = parseHexColor(key.bgColor, Color(0xFF1B1C2A))
    val iconColor = parseHexColor(key.iconColor, Color(0xFF60A5FA))
    val iconVector = resolveIcon(key.icon)

    Box(
        modifier = modifier
            .fillMaxHeight()
            .aspectRatio(1f)
            .shadow(6.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .background(bgColor)
            .border(
                1.dp,
                if (editMode) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.12f),
                RoundedCornerShape(18.dp)
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        // Glass glossy top reflection
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.5f)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.15f), Color.Transparent)
                    )
                )
        )

        // Notification badge dot (like folder badge in reference image)
        if (!key.badge.isNullOrEmpty()) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .align(Alignment.TopEnd)
                    .padding(1.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF34D399))
                    .border(0.5.dp, Color.Black.copy(alpha = 0.5f), CircleShape)
            )
        }

        // Icon & Title
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = iconVector,
                contentDescription = key.title,
                tint = iconColor,
                modifier = Modifier.size(24.dp)
            )
            if (key.title.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = key.title,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.9f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Capacitive Touch Point Button with horizontal glowing LED indicator line.
 */
@Composable
private fun TouchPointButton(onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .combinedClickable(onClick = onClick)
            .padding(4.dp)
    ) {
        // Glowing cyan/white LED line
        Box(
            modifier = Modifier
                .width(28.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0xFF67E8F9))
                .shadow(4.dp, RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.height(3.dp))
        // Subtle sensor dot
        Box(
            modifier = Modifier
                .size(4.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.25f))
        )
    }
}

/**
 * Mobile Key Customizer Modal Bottom Sheet.
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
    var bgColorHex by remember { mutableStateOf(key.bgColor ?: "#1B1C2A") }
    var iconColorHex by remember { mutableStateOf(key.iconColor ?: "#60A5FA") }
    var appSearch by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Customize Key #${key.index + 1}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            // Label
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Label") },
                modifier = Modifier.fillMaxWidth()
            )

            // Icon Picker
            Text(text = "Icon", style = MaterialTheme.typography.labelMedium)
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
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(item.icon, contentDescription = item.label, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            // Action Type Chips
            Text(text = "Action Type", style = MaterialTheme.typography.labelMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("url", "hotkey", "media", "system", "app").forEach { type ->
                    FilterChip(
                        selected = actionType == type,
                        onClick = {
                            actionType = type
                            actionValue = when (type) {
                                "url" -> "https://google.com"
                                "hotkey" -> "win+d"
                                "media" -> "toggle"
                                "system" -> "lock"
                                else -> "notepad"
                            }
                        },
                        label = { Text(type.uppercase(Locale.US), fontSize = 10.sp) }
                    )
                }
            }

            // Action Value Input / Presets
            when (actionType) {
                "url" -> {
                    OutlinedTextField(
                        value = actionValue,
                        onValueChange = { actionValue = it },
                        label = { Text("Web URL") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                "hotkey" -> {
                    OutlinedTextField(
                        value = actionValue,
                        onValueChange = { actionValue = it },
                        label = { Text("Shortcut Chord (e.g. win+d, ctrl+c, alt+tab)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                "media" -> {
                    OutlinedTextField(
                        value = actionValue,
                        onValueChange = { actionValue = it },
                        label = { Text("Media (toggle, next, prev, vol_up, vol_down, mute)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                "system" -> {
                    OutlinedTextField(
                        value = actionValue,
                        onValueChange = { actionValue = it },
                        label = { Text("System Action (lock, screenshot, bright_up, bright_down)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                "app" -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Installed Desktop Applications", style = MaterialTheme.typography.labelMedium)
                            IconButton(onClick = onRefreshApps, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Filled.Settings, contentDescription = "Refresh Apps", modifier = Modifier.size(14.dp))
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
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(140.dp)
                        ) {
                            if (filteredApps.isNotEmpty()) {
                                androidx.compose.foundation.lazy.LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)
                                ) {
                                    items(filteredApps.size) { idx ->
                                        val app = filteredApps[idx]
                                        val isChosen = actionValue == app.path || actionValue == app.name
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(if (isChosen) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                                .combinedClickable(onClick = {
                                                    actionValue = app.path.ifEmpty { app.name }
                                                    if (title.startsWith("Key ") || title.isEmpty()) {
                                                        title = app.name
                                                    }
                                                    findBestMatchingDeckIcon(app.name)?.let {
                                                        selectedIcon = it
                                                    }
                                                })
                                                .padding(horizontal = 8.dp, vertical = 6.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.weight(1f, fill = false)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Terminal,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
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
                                        text = if (installedApps.isEmpty()) "Loading apps from desktop..." else "No matching apps found",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = actionValue,
                            onValueChange = { actionValue = it },
                            label = { Text("Command or File Path") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // Save & Cancel Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
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
                    }
                ) {
                    Text("Save")
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * Mobile Infobar Customizer Bottom Sheet.
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
        sheetState = rememberModalBottomSheetState()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Infobar Display Settings",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("clock", "media", "page", "text").forEach { m ->
                    FilterChip(
                        selected = mode == m,
                        onClick = { mode = m },
                        label = { Text(m.replaceFirstChar { it.uppercase() }) }
                    )
                }
            }

            if (mode == "text") {
                OutlinedTextField(
                    value = customText,
                    onValueChange = { customText = it },
                    label = { Text("Custom Text") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        onSave(infobar.copy(mode = mode, customText = customText))
                    }
                ) {
                    Text("Apply")
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
