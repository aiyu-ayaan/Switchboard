package com.aiyu.switchboard.ui.home

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.text.font.FontWeight
import com.aiyu.switchboard.ui.kit.MonoCaption
import com.aiyu.switchboard.ui.system.Section
import com.aiyu.switchboard.ui.system.VolumeRow
import com.aiyu.switchboard.net.Cmd
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import com.aiyu.switchboard.ui.kit.SectionCard
import com.aiyu.switchboard.ui.kit.bouncyClickable
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiyu.switchboard.AppViewModel
import com.aiyu.switchboard.data.SavedHost
import com.aiyu.switchboard.net.HostConnection
import com.aiyu.switchboard.net.Link
import com.aiyu.switchboard.ui.components.BreathingShape
import com.aiyu.switchboard.ui.components.ShapeIcon

/** Start screen: every paired computer with its live status. */
@Composable
fun SystemsScreen(vm: AppViewModel, onOpen: (String) -> Unit, onSection: (String, Section) -> Unit, onScan: () -> Unit, onCode: () -> Unit, onAdd: () -> Unit, onSettings: () -> Unit) {
    val data by vm.vault.data.collectAsStateWithLifecycle()
    val live by vm.connections.live.collectAsStateWithLifecycle()
    val hosts = data.hosts
    var fabOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(fabOpen) { fabOpen = false }
    val behavior = TopAppBarDefaults.pinnedScrollBehavior()
    val online = hosts.count { h -> live[h.id]?.link?.collectAsStateWithLifecycle()?.value is Link.Online }

    Scaffold(
        modifier = Modifier.nestedScroll(behavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Switchboard", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        MonoCaption(if (hosts.isEmpty()) "No computers yet" else "${hosts.size} computer${if (hosts.size > 1) "s" else ""} · $online connected", color = if (online > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Settings") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface, scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer),
                scrollBehavior = behavior,
            )
        },
        floatingActionButton = {
            if (hosts.isNotEmpty()) {
                FloatingActionButtonMenu(
                    expanded = fabOpen,
                    button = {
                        ToggleFloatingActionButton(
                            modifier = Modifier.semantics {
                                contentDescription = "Add computer"
                                stateDescription = if (fabOpen) "Expanded" else "Collapsed"
                            },
                            checked = fabOpen,
                            onCheckedChange = { fabOpen = it },
                        ) {
                            val icon by remember { derivedStateOf { if (checkedProgress > 0.5f) Icons.Rounded.Close else Icons.Rounded.Add } }
                            Icon(icon, contentDescription = null, modifier = Modifier.animateIcon({ checkedProgress }))
                        }
                    },
                ) {
                    FloatingActionButtonMenuItem(onClick = { fabOpen = false; onScan() }, icon = { Icon(Icons.Rounded.QrCodeScanner, null) }, text = { Text("Scan QR code") })
                    FloatingActionButtonMenuItem(onClick = { fabOpen = false; onCode() }, icon = { Icon(Icons.Rounded.Keyboard, null) }, text = { Text("Enter code") })
                    FloatingActionButtonMenuItem(onClick = { fabOpen = false; onAdd() }, icon = { Icon(Icons.Rounded.Computer, null) }, text = { Text("Find on network") })
                }
            }
        },
    ) { pad ->
        if (hosts.isEmpty()) {
            Empty(Modifier.padding(pad), onScan, onCode, onAdd)
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 4.dp, bottom = pad.calculateBottomPadding() + 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(hosts, key = { it.id }) { h ->
                    val conn = live[h.id]
                    SystemCard(h, conn, Modifier.animateItem(), onSection = { vm.select(h.id); onSection(h.id, it) }) {
                        vm.select(h.id)
                        onOpen(h.id)
                    }
                }
            }
        }
    }
}

@Composable
private fun SystemCard(host: SavedHost, conn: HostConnection?, modifier: Modifier = Modifier, onSection: (Section) -> Unit, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val link = conn?.link?.collectAsStateWithLifecycle()?.value ?: Link.Connecting
    val state = conn?.state?.collectAsStateWithLifecycle()?.value
    val on = link is Link.Online
    var lockAsk by remember { mutableStateOf(false) }
    SectionCard(modifier.bouncyClickable(onClick = onClick), color = if (on) cs.surfaceContainer else cs.surfaceContainerLow) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShapeIcon(
                    Icons.Rounded.DesktopWindows,
                    if (on) MaterialShapes.Cookie9Sided else MaterialShapes.Circle,
                    if (on) cs.primaryContainer else cs.surfaceContainerHighest,
                    if (on) cs.onPrimaryContainer else cs.onSurfaceVariant,
                    size = 44.dp,
                    iconSize = 22.dp,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(host.name, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(on, Modifier.size(8.dp))
                        Spacer(Modifier.width(6.dp))
                        MonoCaption(
                            when (link) {
                                is Link.Online -> "Connected"
                                Link.Connecting -> "Connecting…"
                                is Link.Offline -> "Offline"
                                is Link.Refused -> "Not paired"
                            },
                            color = if (on) cs.primary else cs.onSurfaceVariant,
                        )
                        if (host.os.isNotBlank()) {
                            Text("  •  ${host.os}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = cs.onSurfaceVariant)
            }
            if (on && state != null && conn != null) {
                // Quick controls right on the card; the header still opens the full home.
                state.volume?.let {
                    Spacer(Modifier.height(12.dp))
                    VolumeRow(it.level, it.muted, conn)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill(Icons.Rounded.DesktopWindows, "${state.displays.size} display${if (state.displays.size == 1) "" else "s"}") { onSection(Section.Displays) }
                    val m = state.media
                    if (m != null) {
                        Pill(if (m.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, m.title.ifBlank { m.player }, Modifier.weight(1f, fill = false), accent = true) {
                            conn.post(Cmd.media("play_pause"))
                        }
                    }
                    Pill(Icons.Rounded.Lock, "Lock") { lockAsk = true }
                }
            }
        }
    }
    if (lockAsk && conn != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { lockAsk = false },
            title = { Text("Lock ${host.name}?") },
            text = { Text("Its screen locks; sign in again on the computer.") },
            confirmButton = { Button(onClick = { lockAsk = false; conn.post(Cmd.power("lock")) }) { Text("Lock") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { lockAsk = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Pill(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, modifier: Modifier = Modifier, accent: Boolean = false, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = CircleShape, color = if (accent) cs.tertiaryContainer else cs.secondaryContainer, modifier = modifier.bouncyClickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(16.dp), tint = if (accent) cs.onTertiaryContainer else cs.onSecondaryContainer)
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, color = if (accent) cs.onTertiaryContainer else cs.onSecondaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
@Composable
private fun Empty(modifier: Modifier, onScan: () -> Unit, onCode: () -> Unit, onAdd: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        BreathingShape(MaterialShapes.Cookie9Sided, MaterialShapes.Sunny, cs.primaryContainer, size = 140.dp) {
            Icon(Icons.Rounded.Computer, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(52.dp))
        }
        Spacer(Modifier.height(28.dp))
        Text("Control your computer", style = MaterialTheme.typography.headlineSmallEmphasized, textAlign = TextAlign.Center)
        Text(
            "On your computer, open Switchboard and choose Pair a phone. No account, no password.",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onScan, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.Rounded.QrCodeScanner, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Scan QR code")
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onCode, modifier = Modifier.weight(1f).height(48.dp)) { Text("Enter code") }
            OutlinedButton(onClick = onAdd, modifier = Modifier.weight(1f).height(48.dp)) { Text("On network") }
        }
    }
}
