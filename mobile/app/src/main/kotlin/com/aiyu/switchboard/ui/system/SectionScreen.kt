package com.aiyu.switchboard.ui.system

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.RadioButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.Monitor
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiyu.switchboard.AppViewModel
import com.aiyu.switchboard.net.Cmd
import com.aiyu.switchboard.net.Display
import com.aiyu.switchboard.net.HostConnection
import com.aiyu.switchboard.net.HostState
import com.aiyu.switchboard.net.Link
import com.aiyu.switchboard.ui.components.ShapeIcon
import com.aiyu.switchboard.ui.home.LinkCard
import com.aiyu.switchboard.ui.kit.LevelRow
import com.aiyu.switchboard.ui.kit.MonoBadge
import com.aiyu.switchboard.ui.kit.MonoCaption
import com.aiyu.switchboard.ui.kit.PodHeader
import com.aiyu.switchboard.ui.kit.SectionCard
import com.aiyu.switchboard.ui.theme.LocalOnline

@Composable
fun SectionScreen(vm: AppViewModel, id: String, section: Section, onBack: () -> Unit) {
    val scope = rememberHost(vm, id, onBack) ?: return
    val link by scope.conn.link.collectAsStateWithLifecycle()
    val state by scope.conn.state.collectAsStateWithLifecycle()
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { HostTopBar(section.title, scope.host.name, false, link, onBack, null, null, scroll) },
    ) { pad ->
        val s = state
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (link !is Link.Online || s == null) {
                item { LinkCard(link, scope.host.name, onRetry = scope.conn::retry, wakeMacs = scope.host.macs, wakeAddrs = scope.host.addrs) }
                // Transfers wait for the link to come back, so keep them in view.
                if (section == Section.Files) item { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { TransfersCard(vm.connections.transfers, scope.host.id) } }
                return@LazyColumn
            }
            when (section) {
                Section.Audio -> {
                    item { PlayerCard(s, scope.conn) }
                    item { MasterVolume(s, scope.conn) }
                    if (s.outputs.size > 1) item { OutputDevices(s, scope.conn) }
                    item { Programs(s, scope.conn) }
                }
                Section.Displays -> {
                    if (s.displays.isEmpty()) item {
                        SectionCard { Text("No controllable display. External monitors need DDC/CI turned on in their own menu.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    if (s.displays.size > 1) item { AllPanels(s, scope.conn) }
                    items(s.displays, key = { it.id }) { DisplayCard(it, scope.conn) }
                }
                Section.Files -> item { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { FilesContent(scope.conn, vm.connections.transfers, scope.host.id, vm.vault) } }
                Section.Input -> item { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { TouchpadContent(s, scope.conn) } }
                Section.System -> {
                    item { Rings(s) }
                    item { Facts(s) }
                    item { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { QuickSettings(s, scope.conn) } }
                    item { ClipboardCard(scope.conn) }
                    item { PowerPod(scope.conn) }
                }
            }
        }
    }
}

// --- Audio ------------------------------------------------------------------------

@Composable
private fun PlayerCard(s: HostState, conn: HostConnection) {
    SectionCard {
        if (s.media == null) {
            PodHeader(Icons.Rounded.WbSunny, "Nothing playing", "Start a player on the computer to control it here", shape = MaterialShapes.Sunny)
        } else {
            NowPlaying(s, conn, art = 72)
            Spacer(Modifier.height(12.dp))
            SeekBar(s.media, conn)
            Spacer(Modifier.height(4.dp))
            Transport(s.media, conn)
            PlayerChips(s, conn)
        }
    }
}

@Composable
private fun MasterVolume(s: HostState, conn: HostConnection) {
    val v = s.volume
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Master volume", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            MonoBadge(if (v == null) "—" else if (v.muted) "MUTED" else "${v.level}%", color = MaterialTheme.colorScheme.primaryContainer, content = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.height(12.dp))
        if (v == null) {
            Text("No audio output found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            VolumeRow(v.level, v.muted, conn)
            Spacer(Modifier.height(12.dp))
            Presets(if (v.muted) null else v.level, listOf(0, 25, 50, 75, 100)) { conn.post(Cmd.setVolume(it)) }
            Spacer(Modifier.height(8.dp))
            Text("Controls the computer's default output device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Where sound comes out. Picking one makes it the computer's default. */
@Composable
private fun OutputDevices(s: HostState, conn: HostConnection) {
    SectionCard {
        PodHeader(Icons.Rounded.Headphones, "Output device", "Where the computer plays sound", shape = MaterialShapes.Cookie6Sided)
        Spacer(Modifier.height(12.dp))
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            s.outputs.forEach { o ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).selectable(selected = o.default, role = Role.RadioButton) { if (!o.default) conn.post(Cmd.setOutput(o.id)) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = o.default, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text(o.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** One slider and mute per program that is making sound. */
@Composable
private fun Programs(s: HostState, conn: HostConnection) {
    val cs = MaterialTheme.colorScheme
    SectionCard {
        PodHeader(Icons.Rounded.Tune, "Programs", if (s.apps.isEmpty()) "Nothing is playing sound" else "Volume for each program", shape = MaterialShapes.Clover4Leaf)
        s.apps.forEach { a ->
            Spacer(Modifier.height(14.dp))
            // key: a stream keeps its own slider position when the list reorders.
            androidx.compose.runtime.key(a.id) {
                Column {
                    Text(a.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (a.detail.isNotBlank()) Text(a.detail, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    LevelRow(
                        value = a.level,
                        label = "${a.name} volume",
                        onSend = { conn.post(Cmd.setAppVolume(a.id, it)) },
                        icon = if (a.muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                        onIcon = { conn.post(Cmd.setAppMute(a.id, !a.muted)) },
                        iconContainer = if (a.muted) cs.errorContainer else cs.surfaceContainerHighest,
                        iconTint = if (a.muted) cs.error else cs.primary,
                        badge = if (a.muted) "MUTED" else null,
                        enabled = !a.muted,
                    )
                }
            }
        }
    }
}

// --- Displays ---------------------------------------------------------------------

@Composable
private fun AllPanels(s: HostState, conn: HostConnection) {
    val avg = s.displays.mapNotNull { it.brightness }.takeIf { it.isNotEmpty() }?.average()?.toInt()
    SectionCard(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        PodHeader(Icons.Rounded.BrightnessMedium, "All panels", "Sets every display at once")
        Spacer(Modifier.height(12.dp))
        LevelRow(avg, "All panels brightness", { v -> s.displays.forEach { conn.post(Cmd.setBrightness(it.id, v)) } }, icon = Icons.Rounded.WbSunny)
        Spacer(Modifier.height(12.dp))
        Presets(avg) { v -> s.displays.forEach { conn.post(Cmd.setBrightness(it.id, v)) } }
    }
}

@Composable
private fun DisplayCard(d: Display, conn: HostConnection) {
    val cs = MaterialTheme.colorScheme
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShapeIcon(if (d.external) Icons.Rounded.Monitor else Icons.Rounded.Laptop, if (d.external) MaterialShapes.Cookie6Sided else MaterialShapes.Cookie9Sided, cs.primaryContainer, cs.onPrimaryContainer, size = 36.dp, iconSize = 18.dp)
            Spacer(Modifier.width(12.dp))
            Text(d.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            MonoBadge(if (d.external) "DDC/CI" else "Internal", color = cs.surfaceContainerHighest, content = cs.onSurfaceVariant)
        }
        Spacer(Modifier.height(14.dp))
        LevelRow(d.brightness, "${d.name} brightness", { conn.post(Cmd.setBrightness(d.id, it)) }, icon = Icons.Rounded.WbSunny)
        if (d.external) {
            Spacer(Modifier.height(10.dp))
            LevelRow(d.contrast, "${d.name} contrast", { conn.post(Cmd.setContrast(d.id, it)) }, icon = Icons.Rounded.Contrast)
        }
        Spacer(Modifier.height(12.dp))
        MonoCaption(listOfNotNull("Brightness: ${d.brightness ?: "—"} / 100", d.contrast?.let { "Contrast: $it / 100" }).joinToString("  •  "))
    }
}

// --- System -----------------------------------------------------------------------

@Composable
private fun Rings(s: HostState) {
    val mem = if (s.stats.memTotal > 0) s.stats.memUsed.toFloat() / s.stats.memTotal else 0f
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BigRing(Modifier.weight(1f), "CPU", s.stats.cpu / 100f, "Processor load", false)
        BigRing(Modifier.weight(1f), "Memory", mem, "%.1f of %.1f GB".format(s.stats.memUsed / 1.0737e9, s.stats.memTotal / 1.0737e9), true)
    }
}

@Composable
private fun BigRing(modifier: Modifier, label: String, value: Float, sub: String, tertiary: Boolean) {
    val cs = MaterialTheme.colorScheme
    val p by androidx.compose.animation.core.animateFloatAsState(value.coerceIn(0f, 1f), MaterialTheme.motionScheme.slowEffectsSpec(), label = label)
    SectionCard(modifier.fillMaxHeight()) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CircularWavyProgressIndicator(progress = { p }, modifier = Modifier.size(96.dp), color = if (tertiary) cs.tertiary else cs.primary, trackColor = if (tertiary) cs.tertiaryContainer else cs.secondaryContainer)
            Text("${(p * 100).toInt()}%", style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(10.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    }
}

@Composable
private fun Facts(s: HostState) {
    val b = s.stats.battery
    val up = s.stats.uptime
    val upText = if (up >= 86400) "${up / 86400}d ${(up % 86400) / 3600}h" else if (up >= 3600) "${up / 3600}h ${(up % 3600) / 60}m" else "${up / 60}m"
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Fact(Modifier.weight(1f), if (b?.charging == true) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryStd, MaterialShapes.Clover4Leaf, b?.let { "${it.percent}%" } ?: "—", if (b == null) "No battery" else if (b.charging) "Charging" else "On battery", LocalOnline.current)
        Fact(Modifier.weight(1f), Icons.Rounded.Schedule, MaterialShapes.Flower, upText, "Uptime", null)
    }
}

@Composable
private fun Fact(modifier: Modifier, icon: ImageVector, shape: RoundedPolygon, value: String, label: String, tint: Color?) {
    val cs = MaterialTheme.colorScheme
    SectionCard(modifier.fillMaxHeight()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShapeIcon(icon, shape, tint?.copy(alpha = 0.2f) ?: cs.secondaryContainer, tint ?: cs.onSecondaryContainer, size = 40.dp, iconSize = 20.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(value, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                Text(label, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            }
        }
    }
}

