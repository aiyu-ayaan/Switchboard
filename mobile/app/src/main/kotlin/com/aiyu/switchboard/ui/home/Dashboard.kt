package com.aiyu.switchboard.ui.home

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.TvOff
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import com.aiyu.switchboard.net.Cmd
import com.aiyu.switchboard.net.Display
import com.aiyu.switchboard.net.HostConnection
import com.aiyu.switchboard.net.HostState
import com.aiyu.switchboard.ui.components.ShapeIcon
import com.aiyu.switchboard.ui.theme.LocalOnline
import kotlinx.coroutines.launch

private val Big = RoundedCornerShape(32.dp)
private val Small = RoundedCornerShape(28.dp)

/** The phone bento: media hero, volume, system rings, a tile per display, power. */
@Composable
fun Dashboard(s: HostState, conn: HostConnection) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MediaTile(s, conn)
        VolumeTile(s, conn)
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RingTile("CPU", s.stats.cpu / 100f, "${s.stats.cpu.toInt()}%", tertiary = false)
            val mem = if (s.stats.memTotal > 0) s.stats.memUsed.toFloat() / s.stats.memTotal else 0f
            RingTile("Memory", mem, "${(mem * 100).toInt()}%", tertiary = true)
        }
        s.displays.forEach { DisplayTile(it, conn) }
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val b = s.stats.battery
            FactTile(
                if (b?.charging == true) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryStd,
                MaterialShapes.Clover4Leaf,
                b?.let { "${it.percent}%" } ?: "—",
                if (b == null) "No battery" else if (b.charging) "Charging" else "On battery",
                tint = if (b != null) LocalOnline.current else null,
            )
            FactTile(Icons.Rounded.Schedule, MaterialShapes.Flower, uptime(s.stats.uptime), "Uptime")
        }
        PowerTile(conn)
    }
}

private fun uptime(secs: Long): String {
    val d = secs / 86400
    val h = (secs % 86400) / 3600
    val m = (secs % 3600) / 60
    return if (d > 0) "${d}d ${h}h" else if (h > 0) "${h}h ${m}m" else "${m}m"
}

@Composable
private fun Tile(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.surfaceContainer, shape: RoundedCornerShape = Small, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = shape, color = color, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp).animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()), content = content)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MediaTile(s: HostState, conn: HostConnection) {
    val cs = MaterialTheme.colorScheme
    val m = s.media
    val haptic = LocalHapticFeedback.current
    Tile(color = cs.tertiaryContainer, shape = Big) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(m?.player ?: "Media", style = MaterialTheme.typography.labelLarge, color = cs.onTertiaryContainer.copy(alpha = 0.8f), modifier = Modifier.weight(1f))
            if (m != null) {
                Surface(shape = CircleShape, color = cs.onTertiaryContainer.copy(alpha = 0.12f)) {
                    Text(if (m.playing) "Playing" else "Paused", style = MaterialTheme.typography.labelMedium, color = cs.onTertiaryContainer, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShapeIcon(Icons.Rounded.MusicNote, MaterialShapes.Sunny, cs.tertiary, cs.onTertiary, size = 88.dp, iconSize = 36.dp, spin = m?.playing == true)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(m?.title?.ifBlank { null } ?: "Nothing playing", style = MaterialTheme.typography.titleLargeEmphasized, color = cs.onTertiaryContainer, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(m?.artist?.ifBlank { null } ?: if (m == null) "Play something on your computer" else "", style = MaterialTheme.typography.bodyMedium, color = cs.onTertiaryContainer.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            val tonal = IconButtonDefaults.filledTonalIconButtonColors(containerColor = cs.onTertiaryContainer.copy(alpha = 0.12f), contentColor = cs.onTertiaryContainer)
            FilledTonalIconButton(onClick = { conn.post(Cmd.media("previous")) }, enabled = m != null, colors = tonal, shapes = IconButtonDefaults.shapes(), modifier = Modifier.size(56.dp)) {
                Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(28.dp))
            }
            FilledIconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    conn.post(Cmd.media("play_pause"))
                },
                enabled = m != null,
                shapes = IconButtonDefaults.shapes(),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = cs.tertiary, contentColor = cs.onTertiary),
                modifier = Modifier.size(width = 104.dp, height = 72.dp),
            ) {
                Icon(if (m?.playing == true) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = if (m?.playing == true) "Pause" else "Play", modifier = Modifier.size(36.dp))
            }
            FilledTonalIconButton(onClick = { conn.post(Cmd.media("next")) }, enabled = m != null, colors = tonal, shapes = IconButtonDefaults.shapes(), modifier = Modifier.size(56.dp)) {
                Icon(Icons.Rounded.SkipNext, contentDescription = "Next", modifier = Modifier.size(28.dp))
            }
        }
    }
}

/**
 * A slider for a live hardware value: sends at most every 120 ms while
 * dragged and once more on release, and ignores pushed values for 2 s after a
 * touch so a stale reading cannot pull the handle back.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LiveSlider(value: Int?, label: String, onSend: (Int) -> Unit, modifier: Modifier = Modifier, leading: ImageVector? = null, trackHeight: Dp = 24.dp) {
    var local by remember { mutableFloatStateOf((value ?: 0).toFloat()) }
    var touched by remember { mutableLongStateOf(0L) }
    var sent by remember { mutableLongStateOf(0L) }
    LaunchedEffect(value) {
        if (value != null && System.currentTimeMillis() - touched > 2000) local = value.toFloat()
    }
    val haptic = LocalHapticFeedback.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) {
            Icon(leading, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
        }
        Slider(
            value = local,
            onValueChange = {
                val now = System.currentTimeMillis()
                if (it.toInt() / 10 != local.toInt() / 10) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                local = it
                touched = now
                if (now - sent > 120) {
                    sent = now
                    onSend(it.toInt())
                }
            },
            onValueChangeFinished = {
                touched = System.currentTimeMillis()
                onSend(local.toInt())
            },
            valueRange = 0f..100f,
            enabled = value != null,
            track = { st -> SliderDefaults.Track(sliderState = st, modifier = Modifier.height(trackHeight), trackCornerSize = 12.dp) },
            modifier = Modifier.weight(1f).semantics { contentDescription = label },
        )
        Spacer(Modifier.width(12.dp))
        Text(if (value == null) "—" else "${local.toInt()}%", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(44.dp), textAlign = TextAlign.End)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun VolumeTile(s: HostState, conn: HostConnection) {
    val cs = MaterialTheme.colorScheme
    val v = s.volume
    Tile {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Volume", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                Text(if (v == null) "—" else if (v.muted) "Muted" else "${v.level}%", style = MaterialTheme.typography.displaySmallEmphasized)
            }
            FilledIconToggleButton(
                checked = v?.muted == true,
                onCheckedChange = { conn.post(Cmd.setMute(it)) },
                enabled = v != null,
                shapes = IconButtonDefaults.toggleableShapes(),
                modifier = Modifier.size(64.dp),
            ) {
                Icon(if (v?.muted == true) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp, contentDescription = if (v?.muted == true) "Unmute" else "Mute")
            }
        }
        Spacer(Modifier.height(12.dp))
        LiveSlider(v?.level, "Volume", { conn.post(Cmd.setVolume(it)) }, trackHeight = 40.dp)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RowScope.RingTile(label: String, value: Float, text: String, tertiary: Boolean) {
    val cs = MaterialTheme.colorScheme
    val p by animateFloatAsState(value.coerceIn(0f, 1f), MaterialTheme.motionScheme.slowEffectsSpec(), label = label)
    Tile(Modifier.weight(1f).fillMaxHeight()) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CircularWavyProgressIndicator(
                progress = { p },
                modifier = Modifier.size(104.dp),
                color = if (tertiary) cs.tertiary else cs.primary,
                trackColor = if (tertiary) cs.tertiaryContainer else cs.secondaryContainer,
            )
            Text(text, style = MaterialTheme.typography.titleLargeEmphasized)
        }
        Spacer(Modifier.height(12.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RowScope.FactTile(icon: ImageVector, shape: RoundedPolygon, value: String, label: String, tint: Color? = null) {
    val cs = MaterialTheme.colorScheme
    Tile(Modifier.weight(1f).fillMaxHeight()) {
        ShapeIcon(icon, shape, tint?.copy(alpha = 0.2f) ?: cs.secondaryContainer, tint ?: cs.onSecondaryContainer, size = 48.dp)
        Spacer(Modifier.height(16.dp))
        Text(value, style = MaterialTheme.typography.headlineMediumEmphasized)
        Text(label, style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DisplayTile(d: Display, conn: HostConnection) {
    val cs = MaterialTheme.colorScheme
    Tile {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShapeIcon(
                if (d.external) Icons.Rounded.DesktopWindows else Icons.Rounded.Laptop,
                if (d.external) MaterialShapes.Cookie6Sided else MaterialShapes.Cookie9Sided,
                if (d.external) cs.tertiaryContainer else cs.secondaryContainer,
                if (d.external) cs.onTertiaryContainer else cs.onSecondaryContainer,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(d.name, style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (d.external) "External · DDC/CI" else "Built-in display", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Text(d.brightness?.toString() ?: "—", style = MaterialTheme.typography.headlineMediumEmphasized)
        }
        Spacer(Modifier.height(12.dp))
        LiveSlider(d.brightness, "${d.name} brightness", { conn.post(Cmd.setBrightness(d.id, it)) }, leading = Icons.Rounded.WbSunny)
        if (d.external) {
            Spacer(Modifier.height(4.dp))
            LiveSlider(d.contrast, "${d.name} contrast", { conn.post(Cmd.setContrast(d.id, it)) }, leading = Icons.Rounded.Contrast)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PowerTile(conn: HostConnection) {
    val cs = MaterialTheme.colorScheme
    var ask by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Tile {
        Text("Power", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PowerButton(Icons.Rounded.Lock, "Lock", MaterialShapes.Cookie9Sided) { ask = Triple("lock", "Lock the computer?", "Its screen locks; you sign in again on the computer.") }
            PowerButton(Icons.Rounded.TvOff, "Screen off", MaterialShapes.Cookie6Sided) { ask = Triple("display_off", "Turn the screen off?", "Move the mouse or press a key on the computer to wake it.") }
            PowerButton(Icons.Rounded.Bedtime, "Sleep", MaterialShapes.Clover4Leaf) { ask = Triple("sleep", "Put the computer to sleep?", "It disconnects until it wakes up.") }
        }
        error?.let { Text(it, color = cs.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
    }
    ask?.let { (action, title, text) ->
        AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text(title) },
            text = { Text(text) },
            confirmButton = {
                Button(onClick = {
                    ask = null
                    scope.launch {
                        val r = conn.send(Cmd.power(action))
                        error = if (r.ok) null else r.error
                    }
                }) { Text("Yes") }
            },
            dismissButton = { TextButton(onClick = { ask = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RowScope.PowerButton(icon: ImageVector, label: String, shape: RoundedPolygon, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp), color = cs.surfaceContainerHigh, modifier = Modifier.weight(1f)) {
        Column(Modifier.padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            ShapeIcon(icon, shape, cs.primaryContainer, cs.onPrimaryContainer, size = 48.dp)
            Spacer(Modifier.height(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
