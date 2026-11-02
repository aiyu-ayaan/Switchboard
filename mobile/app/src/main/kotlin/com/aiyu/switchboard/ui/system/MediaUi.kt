package com.aiyu.switchboard.ui.system

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiyu.switchboard.data.B64
import com.aiyu.switchboard.net.Cmd
import com.aiyu.switchboard.net.HostConnection
import com.aiyu.switchboard.net.HostState
import com.aiyu.switchboard.net.Media
import com.aiyu.switchboard.ui.components.ShapeIcon
import com.aiyu.switchboard.ui.kit.MonoBadge
import com.aiyu.switchboard.ui.kit.MonoCaption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The cover of the current track, fetched from the computer when it changes. */
@Composable
fun rememberCover(conn: HostConnection, art: String?): ImageBitmap? {
    var cover by remember(art) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(art) {
        if (art == null) return@LaunchedEffect
        val r = conn.send(Cmd.mediaArt())
        if (r.ok) {
            cover = withContext(Dispatchers.Default) {
                B64.decode(r.data.orEmpty())?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
            }
        }
    }
    return cover
}

@Composable
fun NowPlaying(s: HostState, conn: HostConnection, art: Int = 56) {
    val cs = MaterialTheme.colorScheme
    val m = s.media ?: return
    val cover = rememberCover(conn, m.art)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (cover != null) {
            Image(cover, null, Modifier.size(art.dp).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop)
        } else {
            ShapeIcon(Icons.Rounded.MusicNote, MaterialShapes.Sunny, cs.tertiaryContainer, cs.onTertiaryContainer, size = art.dp, iconSize = (art / 2.4).dp, spin = m.playing)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(m.title.ifBlank { "Unknown title" }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(m.artist.ifBlank { " " }, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            MonoBadge(m.player, color = cs.surfaceContainerHighest, content = cs.onSurfaceVariant)
        }
    }
}

fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Progress with seek. Between state pushes the position advances on its own while playing. */
@Composable
fun SeekBar(m: Media, conn: HostConnection) {
    val length = m.length?.takeIf { it > 0 } ?: return
    val position = m.position ?: return
    // Restart the local clock whenever the computer reports a position.
    val anchor = remember(position, m.id, m.title, m.playing) { SystemClock.elapsedRealtime() }
    var now by remember { mutableLongStateOf(anchor) }
    LaunchedEffect(anchor, m.playing) {
        now = SystemClock.elapsedRealtime()
        while (m.playing) {
            delay(250)
            now = SystemClock.elapsedRealtime()
        }
    }
    var drag by remember { mutableStateOf<Float?>(null) }
    var holdUntil by remember { mutableLongStateOf(0L) }
    val live = (position + if (m.playing) now - anchor else 0L).coerceIn(0, length)
    val shown = drag ?: if (SystemClock.elapsedRealtime() < holdUntil) null else live.toFloat()
    val value = (shown ?: live.toFloat()).coerceIn(0f, length.toFloat())
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = value,
            onValueChange = { drag = it },
            onValueChangeFinished = {
                drag?.let { conn.post(Cmd.seek(it.toLong())) }
                // Keep the thumb where it was dropped until the player reports back.
                holdUntil = SystemClock.elapsedRealtime() + 1500
                drag = null
            },
            valueRange = 0f..length.toFloat(),
            enabled = m.canSeek,
            modifier = Modifier.semantics { contentDescription = "Track position" },
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            MonoCaption(clock(value.toLong()))
            MonoCaption(clock(length))
        }
    }
}

@Composable
fun Transport(m: Media, conn: HostConnection) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        val shuffle = m.shuffle
        IconButton(onClick = { shuffle?.let { conn.post(Cmd.setShuffle(!it)) } }, enabled = shuffle != null) {
            Icon(Icons.Rounded.Shuffle, if (shuffle == true) "Shuffle on" else "Shuffle off", tint = if (shuffle == true) cs.primary else cs.onSurfaceVariant)
        }
        FilledTonalIconButton(onClick = { conn.post(Cmd.media("previous")) }, shapes = IconButtonDefaults.shapes(), colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = cs.surfaceContainerHighest)) {
            Icon(Icons.Rounded.SkipPrevious, "Previous")
        }
        // The play button morphs from circle to rounded square while pressed (expressive shapes).
        FilledIconButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                conn.post(Cmd.media("play_pause"))
            },
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.size(56.dp),
        ) { Icon(if (m.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (m.playing) "Pause" else "Play", Modifier.size(28.dp)) }
        FilledTonalIconButton(onClick = { conn.post(Cmd.media("next")) }, shapes = IconButtonDefaults.shapes(), colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = cs.surfaceContainerHighest)) {
            Icon(Icons.Rounded.SkipNext, "Next")
        }
        val repeat = m.repeat
        IconButton(
            onClick = { conn.post(Cmd.setRepeat(when (repeat) { "off" -> "all"; "all" -> "track"; else -> "off" })) },
            enabled = repeat != null,
        ) {
            Icon(
                if (repeat == "track") Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                when (repeat) { "track" -> "Repeat one"; "all" -> "Repeat all"; else -> "Repeat off" },
                tint = if (repeat == "track" || repeat == "all") cs.primary else cs.onSurfaceVariant,
            )
        }
    }
}

/** Which player the controls drive; shown only when there is a choice. */
@Composable
fun PlayerChips(s: HostState, conn: HostConnection) {
    if (s.players.size < 2) return
    val current = s.media?.id
    Spacer(Modifier.height(8.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        s.players.forEach { p ->
            FilterChip(selected = p.id == current, onClick = { conn.post(Cmd.selectPlayer(p.id)) }, label = { Text(p.name, maxLines = 1) })
        }
    }
}
