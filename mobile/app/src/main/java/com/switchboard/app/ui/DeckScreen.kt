package com.switchboard.app.ui

import android.app.Activity
import android.graphics.BitmapFactory
import android.util.Base64
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

// ---------------------------------------------------------------------------
// Catalogue
// ---------------------------------------------------------------------------

private const val KEYS_PER_PAGE = 8
private const val DECK_COLUMNS = 4

private data class DeckIconItem(val id: String, val label: String, val icon: ImageVector)

// Mirrors the desktop's icon catalogue slug for slug. A slug the phone cannot
// resolve used to fall through to a generic glyph, so the same key wore one
// picture on the desktop and a different one here.
private val DECK_ICONS = listOf(
    DeckIconItem("home", "Home", Icons.Filled.Home),
    DeckIconItem("notes", "Notes / Docs", Icons.Filled.Description),
    DeckIconItem("folder", "Files & Folders", Icons.Filled.Folder),
    DeckIconItem("calendar", "Calendar", Icons.Filled.CalendarToday),
    DeckIconItem("google", "Web & Search", Icons.Filled.Language),
    DeckIconItem("youtube", "YouTube", Icons.Filled.SmartDisplay),
    DeckIconItem("spotify", "Music", Icons.Filled.MusicNote),
    DeckIconItem("linkedin", "LinkedIn", Icons.Filled.Work),
    DeckIconItem("terminal", "Terminal", Icons.Filled.Terminal),
    DeckIconItem("play_pause", "Play / Pause", Icons.Filled.PlayArrow),
    DeckIconItem("skip_next", "Next Track", Icons.Filled.SkipNext),
    DeckIconItem("skip_prev", "Previous Track", Icons.Filled.SkipPrevious),
    DeckIconItem("volume_up", "Volume Up", Icons.AutoMirrored.Filled.VolumeUp),
    DeckIconItem("volume_down", "Volume Down", Icons.AutoMirrored.Filled.VolumeDown),
    DeckIconItem("volume_mute", "Mute", Icons.AutoMirrored.Filled.VolumeOff),
    DeckIconItem("lock", "Lock System", Icons.Filled.Lock),
    DeckIconItem("camera", "Camera / Capture", Icons.Filled.PhotoCamera),
    DeckIconItem("code", "Developer", Icons.Filled.Code),
    DeckIconItem("monitor", "Display", Icons.Filled.Monitor),
    DeckIconItem("settings", "Preferences", Icons.Filled.Settings),
    DeckIconItem("mic", "Microphone", Icons.Filled.Mic),
    DeckIconItem("headphones", "Audio Output", Icons.Filled.Headphones),
    DeckIconItem("sparkles", "AI / Assistant", Icons.Filled.AutoAwesome),
    DeckIconItem("cpu", "Hardware", Icons.Filled.Memory),
    DeckIconItem("layers", "Windows", Icons.Filled.Layers),
    DeckIconItem("flame", "Trending", Icons.Filled.LocalFireDepartment),
    DeckIconItem("sun", "Brightness Up", Icons.Filled.LightMode),
    DeckIconItem("moon", "Brightness Down", Icons.Filled.DarkMode),
    DeckIconItem("tv", "Media Stream", Icons.Filled.Tv),
    DeckIconItem("compass", "Explore", Icons.Filled.Explore),
    DeckIconItem("link", "Quick Link", Icons.Filled.Link),
    DeckIconItem("bookmark", "Bookmark", Icons.Filled.Bookmark),
    DeckIconItem("bell", "Notification", Icons.Filled.Notifications),
    DeckIconItem("power", "Power", Icons.Filled.PowerSettingsNew)
)

private data class ActionKind(val id: String, val label: String, val icon: ImageVector)

private val ACTION_KINDS = listOf(
    ActionKind("app", "App", Icons.Filled.Apps),
    ActionKind("url", "Link", Icons.Filled.Language),
    ActionKind("hotkey", "Hotkey", Icons.Filled.Keyboard),
    ActionKind("media", "Media", Icons.Filled.MusicNote),
    ActionKind("system", "System", Icons.Filled.Monitor),
    ActionKind("page", "Page", Icons.Filled.Apps)
)

private val MEDIA_PRESETS = listOf(
    "toggle" to "Play / Pause",
    "next" to "Next track",
    "prev" to "Previous track",
    "vol_up" to "Volume up",
    "vol_down" to "Volume down",
    "mute" to "Toggle mute"
)

private val SYSTEM_PRESETS = listOf(
    "lock" to "Lock workstation",
    "screenshot" to "Screenshot",
    "bright_up" to "Brightness up",
    "bright_down" to "Brightness down"
)

private val HOTKEY_PRESETS = listOf(
    "win+d" to "Show desktop",
    "ctrl+shift+esc" to "Task manager",
    "win+shift+s" to "Snip",
    "alt+tab" to "Switch app",
    "win+e" to "Explorer",
    "ctrl+c" to "Copy",
    "ctrl+v" to "Paste"
)

private val URL_PRESETS = listOf(
    "https://google.com" to "Google",
    "https://github.com" to "GitHub",
    "https://youtube.com" to "YouTube",
    "https://chatgpt.com" to "ChatGPT"
)

private fun resolveDeckIcon(id: String): ImageVector =
    DECK_ICONS.firstOrNull { it.id == id }?.icon ?: Icons.Filled.Code

/**
 * A key the user has never configured. Empty slots read as invitations to add
 * something rather than as broken buttons.
 */
private fun DeckKey.isBlank(): Boolean = action.value.isBlank() && title.isBlank()

private fun blankKey(index: Int) = DeckKey(index = index, title = "", icon = "code")

/**
 * Per-key accent, parsed from the host's hex string.
 *
 * Only the accent survives the trip: the host also stores an opaque tile colour
 * picked against the desktop's fixed dark palette, and honouring it here would
 * punch holes in a Material You scheme the user chose on the phone. Tiles are
 * drawn from the scheme and tinted with this accent instead, so a key keeps its
 * identity while the deck still recolours with the wallpaper.
 */
private fun accentOf(key: DeckKey, fallback: Color): Color {
    val hex = key.iconColor?.trim()?.removePrefix("#") ?: return fallback
    if (hex.length != 6 && hex.length != 8) return fallback
    val value = hex.toLongOrNull(16) ?: return fallback
    return if (hex.length == 6) Color(value or 0xFF000000L) else Color(value)
}

/** Decodes the host-supplied application icon once per distinct payload. */
@Composable
private fun rememberHostIcon(dataUri: String?): ImageBitmap? = remember(dataUri) {
    if (dataUri.isNullOrBlank()) return@remember null
    val payload = dataUri.substringAfter("base64,", missingDelimiterValue = "")
    if (payload.isEmpty()) return@remember null
    runCatching {
        val bytes = Base64.decode(payload, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()
}

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

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
    val haptics = LocalHaptics.current
    val config = state.deckConfig

    // A deck is glanced at and tapped, not read. Letting the display sleep
    // mid-session is the one thing that makes it useless as a control surface.
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    var pageIndex by remember(config.activePage, config.pages.size) {
        mutableIntStateOf(config.activePage.coerceIn(0, maxOf(0, config.pages.size - 1)))
    }
    var editing by remember { mutableStateOf(false) }
    var editingSlot by remember { mutableStateOf<Int?>(null) }
    var editingInfobar by remember { mutableStateOf(false) }
    var firedSlot by remember { mutableStateOf<Int?>(null) }

    // Routed through Haptics rather than the view directly, so the deck answers
    // to the same switch as the rest of the app.
    fun tap() = haptics.tap()
    fun press() = haptics.longPress()

    // Clear the launch flash without leaving the tile stuck lit.
    LaunchedEffect(firedSlot) {
        if (firedSlot != null) {
            delay(320)
            firedSlot = null
        }
    }

    val page: DeckPage? = config.pages.getOrNull(pageIndex)
    val keys = remember(page) {
        List(KEYS_PER_PAGE) { slot -> page?.keys?.firstOrNull { it.index == slot } ?: blankKey(slot) }
    }

    fun writeKeys(updated: List<DeckKey>) {
        val pages = config.pages.mapIndexed { idx, p ->
            if (idx == pageIndex) p.copy(keys = updated) else p
        }
        onSaveConfig(config.copy(activePage = pageIndex, pages = pages))
    }

    fun goToPage(idx: Int) {
        if (config.pages.isEmpty()) return
        val target = ((idx % config.pages.size) + config.pages.size) % config.pages.size
        pageIndex = target
        onSaveConfig(config.copy(activePage = target))
    }

    fun launch(key: DeckKey) {
        if (key.isBlank()) {
            editing = true
            editingSlot = key.index
            press()
            return
        }
        if (key.action.type == "page") {
            val target = config.pages.indexOfFirst { it.id == key.action.value }
            if (target >= 0) goToPage(target)
            tap()
            return
        }
        tap()
        firedSlot = key.index
        onAction(key.index, key.action, page?.id)
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            DeckTopBar(
                pages = config.pages,
                pageIndex = pageIndex,
                editing = editing,
                onBack = onBack,
                onSelectPage = { goToPage(it) },
                onToggleEdit = {
                    editing = !editing
                    press()
                },
                onAddPage = {
                    val next = config.pages.size
                    val fresh = DeckPage(
                        id = "page-${System.currentTimeMillis()}",
                        name = "Page ${next + 1}",
                        keys = List(KEYS_PER_PAGE) { blankKey(it) }
                    )
                    pageIndex = next
                    onSaveConfig(config.copy(activePage = next, pages = config.pages + fresh))
                    press()
                },
                onDeletePage = {
                    if (config.pages.size <= 1) return@DeckTopBar
                    val target = maxOf(0, pageIndex - 1)
                    pageIndex = target
                    onSaveConfig(
                        config.copy(
                            activePage = target,
                            pages = config.pages.filterIndexed { idx, _ -> idx != pageIndex }
                        )
                    )
                    press()
                }
            )

            Spacer(Modifier.height(10.dp))

            // The grid claims every pixel between the bars and splits it evenly,
            // so keys scale with the device instead of overflowing a fixed size.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                keys.chunked(DECK_COLUMNS).forEach { row ->
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        row.forEach { key ->
                            DeckKeyTile(
                                deckKey = key,
                                editing = editing,
                                fired = firedSlot == key.index,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                onClick = {
                                    if (editing) {
                                        editingSlot = key.index
                                        tap()
                                    } else {
                                        launch(key)
                                    }
                                },
                                onLongClick = {
                                    editing = true
                                    editingSlot = key.index
                                    press()
                                }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            DeckInfobar(
                infobar = config.infobar,
                pageName = page?.name.orEmpty(),
                pageLabel = "${pageIndex + 1}/${maxOf(1, config.pages.size)}",
                mediaTitle = state.host.media.takeIf { it.active }?.title.orEmpty(),
                editing = editing,
                onPrev = { goToPage(pageIndex - 1) },
                onNext = { goToPage(pageIndex + 1) },
                onConfigure = { editingInfobar = true }
            )
        }
    }

    editingSlot?.let { slot ->
        KeyEditorSheet(
            deckKey = keys.getOrElse(slot) { blankKey(slot) },
            pages = config.pages,
            apps = state.installedApps,
            onRefreshApps = onRefreshApps,
            onDismiss = { editingSlot = null },
            onClear = {
                writeKeys(keys.map { if (it.index == slot) blankKey(slot) else it })
                editingSlot = null
            },
            onApply = { updated ->
                writeKeys(keys.map { if (it.index == slot) updated else it })
                editingSlot = null
            }
        )
    }

    if (editingInfobar) {
        InfobarSheet(
            infobar = config.infobar,
            onDismiss = { editingInfobar = false },
            onApply = {
                onSaveConfig(config.copy(infobar = it))
                editingInfobar = false
            }
        )
    }
}

// ---------------------------------------------------------------------------
// Top bar
// ---------------------------------------------------------------------------

@Composable
private fun DeckTopBar(
    pages: List<DeckPage>,
    pageIndex: Int,
    editing: Boolean,
    onBack: () -> Unit,
    onSelectPage: (Int) -> Unit,
    onToggleEdit: () -> Unit,
    onAddPage: () -> Unit,
    onDeletePage: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        DeckIconButton(icon = Icons.AutoMirrored.Filled.ArrowBack, label = "Back", onClick = onBack)

        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(pages.size) { idx ->
                val selected = idx == pageIndex
                FilterChip(
                    selected = selected,
                    onClick = { onSelectPage(idx) },
                    label = {
                        Text(
                            pages[idx].name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelLarge
                        )
                    },
                    shape = CircleShape,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
            if (editing) {
                item {
                    AssistChip(
                        onClick = onAddPage,
                        label = { Text("Add") },
                        leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(16.dp)) },
                        shape = CircleShape,
                        colors = AssistChipDefaults.assistChipColors(
                            labelColor = MaterialTheme.colorScheme.primary,
                            leadingIconContentColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
                if (pages.size > 1) {
                    item {
                        AssistChip(
                            onClick = onDeletePage,
                            label = { Text("Remove") },
                            leadingIcon = { Icon(Icons.Filled.Delete, null, Modifier.size(16.dp)) },
                            shape = CircleShape,
                            colors = AssistChipDefaults.assistChipColors(
                                labelColor = MaterialTheme.colorScheme.error,
                                leadingIconContentColor = MaterialTheme.colorScheme.error
                            )
                        )
                    }
                }
            }
        }

        FilledTonalButton(
            onClick = onToggleEdit,
            shape = CircleShape,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Icon(
                imageVector = if (editing) Icons.Filled.DoneAll else Icons.Filled.Tune,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(if (editing) "Done" else "Edit")
        }
    }
}

@Composable
private fun DeckIconButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .bouncyClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, label, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

// ---------------------------------------------------------------------------
// Key tile
// ---------------------------------------------------------------------------

@Composable
private fun DeckKeyTile(
    deckKey: DeckKey,
    editing: Boolean,
    fired: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val blank = deckKey.isBlank()
    val accent = accentOf(deckKey, scheme.primary)

    val container by animateColorAsState(
        targetValue = when {
            fired -> scheme.primaryContainer
            blank -> scheme.surfaceContainerLow
            else -> scheme.surfaceContainerHigh
        },
        label = "deck-key-container"
    )
    // A launched key blooms: a brief lift plus the accent flooding the surface
    // is the only confirmation a control surface can give at arm's length.
    val elevation by animateFloatAsState(if (fired) 1f else 0f, label = "deck-key-bloom")

    val hostIcon = rememberHostIcon(deckKey.iconData)

    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(container)
            .then(
                if (blank) {
                    Modifier.border(
                        width = 1.dp,
                        color = scheme.outlineVariant,
                        shape = MaterialTheme.shapes.large
                    )
                } else {
                    Modifier.background(accent.copy(alpha = 0.10f + 0.16f * elevation))
                }
            )
            .bouncyCombinedClickable(pressedScale = 0.93f, onLongClick = onLongClick, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (deckKey.badge?.isNotBlank() == true) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp),
                shape = CircleShape,
                color = scheme.tertiaryContainer
            ) {
                Text(
                    text = deckKey.badge!!.uppercase(Locale.US),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onTertiaryContainer,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                )
            }
        }

        if (editing) {
            Text(
                text = "${deckKey.index + 1}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = scheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(horizontal = 10.dp, vertical = 7.dp)
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 8.dp)
        ) {
            when {
                blank -> Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp)
                )
                // The desktop sends the launch target's real icon; nothing we
                // could draw identifies the app as well as the app's own art.
                hostIcon != null -> androidx.compose.foundation.Image(
                    bitmap = hostIcon,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(34.dp)
                )
                else -> Icon(
                    imageVector = resolveDeckIcon(deckKey.icon),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(30.dp)
                )
            }

            Text(
                text = if (blank) "Empty" else deckKey.title.ifBlank { "Key ${deckKey.index + 1}" },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (blank) FontWeight.Normal else FontWeight.Medium,
                color = if (blank) scheme.onSurfaceVariant else scheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Infobar
// ---------------------------------------------------------------------------

@Composable
private fun DeckInfobar(
    infobar: DeckInfobar,
    pageName: String,
    pageLabel: String,
    mediaTitle: String,
    editing: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onConfigure: () -> Unit
) {
    var clock by remember { mutableStateOf(formatClock()) }
    LaunchedEffect(Unit) {
        while (true) {
            clock = formatClock()
            delay(1_000)
        }
    }

    val body = when (infobar.mode) {
        "text" -> infobar.customText.ifBlank { "Switchboard" }
        "page" -> pageName.ifBlank { "Deck" }
        "media" -> mediaTitle.ifBlank { "Nothing playing" }
        else -> clock
    }

    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 6.dp)
        ) {
            DeckIconButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous page", onPrev)

            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = pageLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = editing, enter = fadeIn(), exit = fadeOut()) {
                DeckIconButton(Icons.Filled.Tune, "Infobar settings", onConfigure)
            }
            Spacer(Modifier.width(6.dp))
            DeckIconButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next page", onNext)
        }
    }
}

private fun formatClock(): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())

// ---------------------------------------------------------------------------
// Key editor
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeyEditorSheet(
    deckKey: DeckKey,
    pages: List<DeckPage>,
    apps: List<InstalledApp>,
    onRefreshApps: () -> Unit,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onApply: (DeckKey) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var title by remember(deckKey) { mutableStateOf(deckKey.title) }
    var badge by remember(deckKey) { mutableStateOf(deckKey.badge.orEmpty()) }
    var iconId by remember(deckKey) { mutableStateOf(deckKey.icon) }
    var iconData by remember(deckKey) { mutableStateOf(deckKey.iconData) }
    var actionType by remember(deckKey) { mutableStateOf(deckKey.action.type) }
    var actionValue by remember(deckKey) { mutableStateOf(deckKey.action.value) }
    var appQuery by remember { mutableStateOf("") }

    LaunchedEffect(actionType) { if (actionType == "app") onRefreshApps() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Key ${deckKey.index + 1}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Label") },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = badge,
                onValueChange = { badge = it },
                label = { Text("Badge (optional)") },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth()
            )

            SectionLabel("Action")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ACTION_KINDS.size) { idx ->
                    val kind = ACTION_KINDS[idx]
                    FilterChip(
                        selected = actionType == kind.id,
                        onClick = {
                            actionType = kind.id
                            actionValue = ""
                            // A glyph the user picked survives a retype; a host
                            // icon belongs to one specific target and does not.
                            iconData = null
                        },
                        label = { Text(kind.label) },
                        leadingIcon = { Icon(kind.icon, null, Modifier.size(16.dp)) },
                        shape = CircleShape
                    )
                }
            }

            when (actionType) {
                "app" -> AppPicker(
                    apps = apps,
                    query = appQuery,
                    selectedPath = actionValue,
                    onQueryChange = { appQuery = it },
                    onRefresh = onRefreshApps,
                    onPick = { app ->
                        actionValue = app.path.ifBlank { app.name }
                        if (title.isBlank()) title = app.name
                        if (app.icon.isNotBlank()) iconId = app.icon
                        // The phone never sees a Windows icon; only the desktop
                        // can attach one, so leave any inherited art behind.
                        iconData = null
                    }
                )

                "page" -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    pages.forEach { page ->
                        SelectableRow(
                            label = page.name,
                            selected = actionValue == page.id,
                            onClick = {
                                actionValue = page.id
                                if (title.isBlank()) title = page.name
                            }
                        )
                    }
                }

                else -> {
                    val presets = when (actionType) {
                        "media" -> MEDIA_PRESETS
                        "system" -> SYSTEM_PRESETS
                        "hotkey" -> HOTKEY_PRESETS
                        else -> URL_PRESETS
                    }
                    OutlinedTextField(
                        value = actionValue,
                        onValueChange = { actionValue = it },
                        label = {
                            Text(
                                when (actionType) {
                                    "url" -> "Address"
                                    "hotkey" -> "Key combination"
                                    else -> "Command"
                                }
                            )
                        },
                        singleLine = true,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth()
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(presets.size) { idx ->
                            val (value, label) = presets[idx]
                            FilterChip(
                                selected = actionValue == value,
                                onClick = {
                                    actionValue = value
                                    if (title.isBlank()) title = label
                                },
                                label = { Text(label) },
                                shape = CircleShape
                            )
                        }
                    }
                }
            }

            SectionLabel("Icon")
            if (iconData != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    rememberHostIcon(iconData)?.let {
                        androidx.compose.foundation.Image(it, null, Modifier.size(28.dp))
                    }
                    Text(
                        "Using the desktop's own icon",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { iconData = null }) { Text("Choose glyph") }
                }
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(DECK_ICONS.size) { idx ->
                        val item = DECK_ICONS[idx]
                        val selected = iconId == item.id
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(MaterialTheme.shapes.medium)
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerHigh
                                )
                                .bouncyClickable { iconId = item.id },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                item.icon,
                                item.label,
                                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = onClear, modifier = Modifier.weight(1f)) { Text("Clear key") }
                FilledTonalButton(
                    modifier = Modifier.weight(1f),
                    shape = CircleShape,
                    onClick = {
                        onApply(
                            deckKey.copy(
                                title = title.trim(),
                                badge = badge.trim().ifBlank { null },
                                icon = iconId,
                                iconData = iconData,
                                action = DeckAction(type = actionType, value = actionValue.trim())
                            )
                        )
                    }
                ) {
                    Icon(Icons.Filled.Check, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Save key")
                }
            }
        }
    }
}

@Composable
private fun AppPicker(
    apps: List<InstalledApp>,
    query: String,
    selectedPath: String,
    onQueryChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onPick: (InstalledApp) -> Unit
) {
    val matches = remember(apps, query) {
        val q = query.trim().lowercase(Locale.US)
        if (q.isEmpty()) apps.take(40)
        else apps.filter { it.name.lowercase(Locale.US).contains(q) }.take(40)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                label = { Text("Search programs") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.weight(1f)
            )
            DeckIconButton(Icons.Filled.Refresh, "Refresh applications", onRefresh)
        }

        if (matches.isEmpty()) {
            Text(
                if (apps.isEmpty()) "Waiting for the desktop's application list…" else "No matching programs",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            // Bounded so the picker never swallows the sheet's action buttons.
            LazyColumn(
                modifier = Modifier.height(220.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(matches) { app ->
                    SelectableRow(
                        label = app.name,
                        supporting = app.path,
                        selected = selectedPath == app.path || selectedPath == app.name,
                        onClick = { onPick(app) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectableRow(
    label: String,
    supporting: String? = null,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .bouncyClickable(pressedScale = 0.98f, onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!supporting.isNullOrBlank()) {
                    Text(
                        supporting,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Infobar editor
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InfobarSheet(
    infobar: DeckInfobar,
    onDismiss: () -> Unit,
    onApply: (DeckInfobar) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var mode by remember(infobar) { mutableStateOf(infobar.mode) }
    var text by remember(infobar) { mutableStateOf(infobar.customText) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Infobar",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(4) { idx ->
                    val option = listOf("clock", "media", "page", "text")[idx]
                    FilterChip(
                        selected = mode == option,
                        onClick = { mode = option },
                        label = { Text(option.replaceFirstChar { it.uppercase() }) },
                        shape = CircleShape
                    )
                }
            }

            if (mode == "text") {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Display text") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            FilledTonalButton(
                onClick = { onApply(infobar.copy(mode = mode, customText = text)) },
                shape = CircleShape,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Apply") }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(Locale.US),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
