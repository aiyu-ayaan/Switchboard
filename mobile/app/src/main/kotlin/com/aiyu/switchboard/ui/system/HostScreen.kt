package com.aiyu.switchboard.ui.system

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Monitor
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.TvOff
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiyu.switchboard.AppViewModel
import com.aiyu.switchboard.data.SavedHost
import com.aiyu.switchboard.net.Cmd
import com.aiyu.switchboard.net.HostConnection
import com.aiyu.switchboard.net.HostState
import com.aiyu.switchboard.net.Link
import com.aiyu.switchboard.ui.components.ShapeIcon
import com.aiyu.switchboard.ui.home.HostSheet
import com.aiyu.switchboard.ui.home.LinkCard
import com.aiyu.switchboard.ui.kit.LevelRow
import com.aiyu.switchboard.ui.kit.MonoBadge
import com.aiyu.switchboard.ui.kit.MonoCaption
import com.aiyu.switchboard.ui.kit.PodHeader
import com.aiyu.switchboard.ui.kit.RoundIcon
import com.aiyu.switchboard.ui.kit.SectionCard
import com.aiyu.switchboard.ui.kit.bouncyClickable
import kotlinx.coroutines.launch

enum class Section(val title: String) { Audio("Audio & Media"), Displays("Displays"), Files("Files"), Input("Touchpad & keyboard"), System("System") }

/** The pieces every screen of one computer needs. */
class HostScope(val host: SavedHost, val conn: HostConnection)

/** Top bar shared by the dashboard and section screens: name, mono status, back or switch. */
@Composable
fun HostTopBar(title: String, sub: String, subIsStatus: Boolean, link: Link, onBack: (() -> Unit)?, onSwitch: (() -> Unit)?, onInfo: (() -> Unit)?, scroll: androidx.compose.material3.TopAppBarScrollBehavior) {
    val cs = MaterialTheme.colorScheme
    TopAppBar(
        title = {
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                MonoCaption(sub, color = if (subIsStatus && link is Link.Online) cs.primary else cs.onSurfaceVariant)
            }
        },
        navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
        actions = {
            if (onSwitch != null) IconButton(onClick = onSwitch) { Icon(Icons.Rounded.SwapHoriz, "Switch computer") }
            if (onInfo != null) IconButton(onClick = onInfo) { Icon(Icons.Rounded.Info, "Connection details") }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.surface, scrolledContainerColor = cs.surfaceContainer),
        scrollBehavior = scroll,
    )
}

fun linkLabel(link: Link) = when (link) {
    is Link.Online -> "Connected"
    Link.Connecting -> "Connecting…"
    is Link.Offline -> "Offline"
    is Link.Refused -> "Not paired"
}

/** Resolves the host and its connection, or leaves when it was forgotten. */
@Composable
fun rememberHost(vm: AppViewModel, id: String, onGone: () -> Unit): HostScope? {
    val data by vm.vault.data.collectAsStateWithLifecycle()
    val live by vm.connections.live.collectAsStateWithLifecycle()
    val host = data.hosts.find { it.id == id }
    val conn = live[id]
    if (host == null || conn == null) {
        LaunchedEffect(Unit) { onGone() }
        return null
    }
    return remember(host, conn) { HostScope(host, conn) }
}

/** One computer's home: status, now playing, displays, resources and power. */
@Composable
fun HostScreen(vm: AppViewModel, id: String, onSwitch: () -> Unit, onOpen: (Section) -> Unit, onSettings: () -> Unit) {
    val scope = rememberHost(vm, id, onSwitch) ?: return
    val link by scope.conn.link.collectAsStateWithLifecycle()
    val state by scope.conn.state.collectAsStateWithLifecycle()
    var info by remember { mutableStateOf(false) }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { HostTopBar(scope.host.name, linkLabel(link), true, link, null, onSwitch, { info = true }, scroll) },
    ) { pad ->
        val s = state
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (link !is Link.Online || s == null) {
                item { LinkCard(link, scope.host.name, onRetry = scope.conn::retry, wakeMacs = scope.host.macs, wakeAddrs = scope.host.addrs) }
            } else {
                item(key = "hero") { HeroCard(scope.host, s, scope.conn) }
                item(key = "audio") { AudioPod(s, scope.conn) { onOpen(Section.Audio) } }
                if (s.displays.isNotEmpty()) item(key = "displays") { DisplaysPod(s, scope.conn) { onOpen(Section.Displays) } }
                item(key = "files") { FilesPod(scope.conn, vm.connections.transfers, scope.host.id) { onOpen(Section.Files) } }
                item(key = "input") { InputPod(s) { onOpen(Section.Input) } }
                item(key = "bento") { ResourcesBento(s) { onOpen(Section.System) } }
                item(key = "power") { PowerPod(scope.conn) }
            }
        }
    }
    if (info) HostSheet(scope.host, scope.conn, onDismiss = { info = false }, onSettings = { info = false; onSettings() }, onForget = { info = false; vm.forget(scope.host.id) }, onRemoteAddrs = { vm.setRemoteAddrs(scope.host.id, it) })
}

// --- Pods ----------------------------------------------------------------------------

@Composable
private fun HeroCard(host: SavedHost, s: HostState, conn: HostConnection) {
    val cs = MaterialTheme.colorScheme
    val t = rememberInfiniteTransition(label = "pulse")
    val pulse by t.animateFloat(0.4f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    var askLock by remember { mutableStateOf(false) }
    SectionCard(color = cs.surfaceContainerLow) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = cs.primaryContainer, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Box(Modifier.size(14.dp).graphicsLayer { alpha = pulse }.clip(CircleShape).background(cs.primary))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(host.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    MonoCaption("Connected", color = cs.primary)
                    Text("•", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    Text("${s.displays.size} panel${if (s.displays.size == 1) "" else "s"}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    s.volume?.let {
                        Text("•", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                        Text(if (it.muted) "Muted" else "${it.level}% vol", style = MaterialTheme.typography.labelSmall, color = if (it.muted) cs.error else cs.onSurfaceVariant)
                    }
                }
            }
            RoundIcon(Icons.Rounded.Lock, "Lock computer", container = cs.primaryContainer, tint = cs.onPrimaryContainer, size = 38.dp) { askLock = true }
        }
    }
    if (askLock) Confirm("Lock ${host.name}?", "Its screen locks; sign in again on the computer.", { askLock = false }) { conn.post(Cmd.power("lock")) }
}

@Composable
private fun Confirm(title: String, text: String, onDismiss: () -> Unit, onYes: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { Button(onClick = { onDismiss(); onYes() }) { Text("Yes") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Now playing with transport and the master volume, as the old home did. */
@Composable
fun AudioPod(s: HostState, conn: HostConnection, onOpen: (() -> Unit)?) {
    val m = s.media
    SectionCard {
        PodHeader(if (m != null) Icons.Rounded.MusicNote else Icons.AutoMirrored.Rounded.VolumeUp, if (m != null) "Now Playing" else "Audio", action = onOpen?.let { "Mixer" }, onAction = onOpen)
        if (m != null) {
            Spacer(Modifier.height(14.dp))
            NowPlaying(s, conn)
            Spacer(Modifier.height(8.dp))
            SeekBar(m, conn)
            Spacer(Modifier.height(4.dp))
            Transport(m, conn)
            PlayerChips(s, conn)
        }
        s.volume?.let { v ->
            Spacer(Modifier.height(14.dp))
            VolumeRow(v.level, v.muted, conn)
        }
    }
}

@Composable
fun VolumeRow(level: Int, muted: Boolean, conn: HostConnection) {
    val cs = MaterialTheme.colorScheme
    LevelRow(
        value = level,
        label = "Master volume",
        onSend = { conn.post(Cmd.setVolume(it)) },
        icon = if (muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
        onIcon = { conn.post(Cmd.setMute(!muted)) },
        iconContainer = if (muted) cs.errorContainer else cs.surfaceContainerHighest,
        iconTint = if (muted) cs.error else cs.primary,
        badge = if (muted) "MUTED" else null,
        enabled = !muted,
    )
}

/** Panel chips, one brightness slider for the chosen panel, and quick presets. */
@Composable
private fun DisplaysPod(s: HostState, conn: HostConnection, onOpen: () -> Unit) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val d = s.displays.getOrNull(selected.coerceIn(0, s.displays.lastIndex)) ?: return
    SectionCard {
        PodHeader(
            if (d.external) Icons.Rounded.Monitor else Icons.Rounded.Laptop,
            "Displays",
            if (s.displays.size == 1) d.name else "${s.displays.size} panels connected",
            shape = MaterialShapes.Cookie6Sided,
            action = "All panels",
            onAction = onOpen,
        )
        if (s.displays.size > 1) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                s.displays.forEachIndexed { i, x ->
                    FilterChip(
                        selected = i == selected,
                        onClick = { selected = i },
                        label = { Text(x.name, maxLines = 1) },
                        leadingIcon = { Icon(if (x.external) Icons.Rounded.Monitor else Icons.Rounded.Laptop, null, Modifier.size(16.dp)) },
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        // key: a different panel starts from its own value, not the last one's.
        androidx.compose.runtime.key(d.id) {
            LevelRow(d.brightness, "${d.name} brightness", { conn.post(Cmd.setBrightness(d.id, it)) }, icon = Icons.Rounded.WbSunny)
        }
        Spacer(Modifier.height(12.dp))
        Presets(d.brightness) { conn.post(Cmd.setBrightness(d.id, it)) }
    }
}

@Composable
fun Presets(current: Int?, values: List<Int> = listOf(25, 50, 75, 100), label: (Int) -> String = { "$it%" }, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        values.forEachIndexed { i, p ->
            ToggleButton(
                checked = current == p,
                onCheckedChange = { onPick(p) },
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    values.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.weight(1f).height(40.dp),
            ) { Text(label(p), style = MaterialTheme.typography.labelMedium) }
        }
    }
}

/** CPU and memory rings plus battery and uptime, tapping through to System. */
@Composable
private fun ResourcesBento(s: HostState, onOpen: () -> Unit) {
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BentoRing(Modifier.weight(1f), "CPU", s.stats.cpu / 100f, "${s.stats.cpu.toInt()}%", false, onOpen)
        val mem = if (s.stats.memTotal > 0) s.stats.memUsed.toFloat() / s.stats.memTotal else 0f
        BentoRing(Modifier.weight(1f), "Memory", mem, "${(mem * 100).toInt()}%", true, onOpen)
    }
}

@Composable
private fun BentoRing(modifier: Modifier, label: String, value: Float, text: String, tertiary: Boolean, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val p by androidx.compose.animation.core.animateFloatAsState(value.coerceIn(0f, 1f), MaterialTheme.motionScheme.slowEffectsSpec(), label = label)
    SectionCard(modifier.fillMaxHeight().bouncyClickable(onClick = onOpen)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center) {
                CircularWavyProgressIndicator(progress = { p }, modifier = Modifier.size(52.dp), color = if (tertiary) cs.tertiary else cs.primary, trackColor = if (tertiary) cs.tertiaryContainer else cs.secondaryContainer)
                Icon(Icons.Rounded.Memory, null, Modifier.size(18.dp), tint = cs.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                Text(label, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun PowerPod(conn: HostConnection) {
    var ask by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    SectionCard {
        PodHeader(Icons.Rounded.Computer, "Power & session", "Lock, sleep, log out, restart or shut down", shape = MaterialShapes.Clover4Leaf)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PowerButton(Icons.Rounded.Lock, "Lock", MaterialShapes.Cookie9Sided) { ask = Triple("lock", "Lock the computer?", "Its screen locks; sign in again on the computer.") }
            PowerButton(Icons.Rounded.TvOff, "Screen off", MaterialShapes.Cookie6Sided) { ask = Triple("display_off", "Turn the screen off?", "Move the mouse or press a key on the computer to wake it.") }
            PowerButton(Icons.Rounded.Bedtime, "Sleep", MaterialShapes.Clover4Leaf) { ask = Triple("sleep", "Put the computer to sleep?", "It disconnects until it wakes up.") }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PowerButton(Icons.AutoMirrored.Rounded.Logout, "Log out", MaterialShapes.Cookie9Sided) { ask = Triple("logout", "Log out of the computer?", "Programs on it close. Unsaved work is lost.") }
            PowerButton(Icons.Rounded.RestartAlt, "Restart", MaterialShapes.Cookie6Sided) { ask = Triple("restart", "Restart the computer?", "Programs on it close and it disconnects until it is back. Unsaved work is lost.") }
            PowerButton(Icons.Rounded.PowerSettingsNew, "Shut down", MaterialShapes.Clover4Leaf) { ask = Triple("shutdown", "Shut the computer down?", "It turns off and cannot be turned back on from here. Unsaved work is lost.") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
    }
    ask?.let { (action, title, text) ->
        Confirm(title, text, { ask = null }) {
            scope.launch {
                val r = conn.send(Cmd.power(action))
                error = if (r.ok) null else r.error
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.PowerButton(icon: ImageVector, label: String, shape: RoundedPolygon, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), color = cs.surfaceContainerHigh, modifier = Modifier.weight(1f).bouncyClickable(onClick = onClick)) {
        Column(Modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            ShapeIcon(icon, shape, cs.primaryContainer, cs.onPrimaryContainer, size = 34.dp, iconSize = 17.dp)
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
