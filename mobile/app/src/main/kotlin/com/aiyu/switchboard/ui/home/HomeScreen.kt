package com.aiyu.switchboard.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiyu.switchboard.AppViewModel
import com.aiyu.switchboard.data.SavedHost
import com.aiyu.switchboard.data.fingerprint
import com.aiyu.switchboard.net.HostConnection
import com.aiyu.switchboard.net.Link
import com.aiyu.switchboard.ui.components.BreathingShape
import com.aiyu.switchboard.ui.components.ShapeIcon
import com.aiyu.switchboard.ui.theme.LocalOnline
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeScreen(vm: AppViewModel, onAdd: () -> Unit, onScan: () -> Unit, onCode: () -> Unit, onSettings: () -> Unit) {
    val data by vm.vault.data.collectAsStateWithLifecycle()
    val live by vm.connections.live.collectAsStateWithLifecycle()
    val hosts = data.hosts
    val selected = hosts.find { it.id == data.selected } ?: hosts.firstOrNull()
    val conn = selected?.let { live[it.id] }
    var fabOpen by rememberSaveable { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    BackHandler(fabOpen) { fabOpen = false }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        floatingActionButton = {
            if (hosts.isNotEmpty()) {
                FloatingActionButtonMenu(
                    expanded = fabOpen,
                    button = {
                        ToggleFloatingActionButton(
                            modifier = Modifier
                                .semantics {
                                    contentDescription = "Add computer"
                                    stateDescription = if (fabOpen) "Expanded" else "Collapsed"
                                }
                                .animateFloatingActionButton(visible = true, alignment = Alignment.BottomEnd),
                            checked = fabOpen,
                            onCheckedChange = { fabOpen = it },
                        ) {
                            val icon by remember { androidx.compose.runtime.derivedStateOf { if (checkedProgress > 0.5f) Icons.Rounded.Close else Icons.Rounded.Add } }
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
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(top = pad.calculateTopPadding())
                .statusBarsPadding()
                .padding(horizontal = 16.dp),
        ) {
            TopRow(onSettings)
            if (selected == null) {
                Empty(onScan, onCode, onAdd)
            } else {
                Header(selected, conn, onDetails = { details = true })
                if (hosts.size > 1) Switcher(hosts, selected.id, live, vm::select)
                Spacer(Modifier.height(16.dp))
                if (conn != null) HostBody(conn, selected)
            }
            Spacer(Modifier.height(112.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }

    if (details && selected != null) {
        HostSheet(selected, conn, onDismiss = { details = false }, onForget = {
            details = false
            vm.forget(selected.id)
        })
    }
}

@Composable
private fun TopRow(onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ShapeIcon(null, MaterialShapes.Cookie9Sided, MaterialTheme.colorScheme.primary, Color.Unspecified, size = 28.dp)
        Spacer(Modifier.width(10.dp))
        Text("Switchboard", style = MaterialTheme.typography.titleMediumEmphasized, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, contentDescription = "Settings") }
    }
}

@Composable
private fun Empty(onScan: () -> Unit, onCode: () -> Unit, onAdd: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        BreathingShape(MaterialShapes.Cookie9Sided, MaterialShapes.Sunny, cs.primaryContainer, size = 200.dp) {
            Icon(Icons.Rounded.Computer, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(72.dp))
        }
        Spacer(Modifier.height(40.dp))
        Text("Your computer,\nin your hand", style = MaterialTheme.typography.displaySmallEmphasized, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            "Open Switchboard on your computer and choose Pair a phone. Then scan its code — no account, no password.",
            style = MaterialTheme.typography.bodyLarge,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onScan, modifier = Modifier.fillMaxWidth().height(64.dp), shape = CircleShape) {
            Icon(Icons.Rounded.QrCodeScanner, null)
            Spacer(Modifier.width(12.dp))
            Text("Scan QR code", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = onCode, modifier = Modifier.weight(1f).height(56.dp)) {
                Icon(Icons.Rounded.Keyboard, null)
                Spacer(Modifier.width(8.dp))
                Text("Enter code")
            }
            OutlinedButton(onClick = onAdd, modifier = Modifier.weight(1f).height(56.dp)) {
                Icon(Icons.Rounded.Computer, null)
                Spacer(Modifier.width(8.dp))
                Text("On network")
            }
        }
    }
}

@Composable
fun StatusDot(on: Boolean, modifier: Modifier = Modifier) {
    val online = LocalOnline.current
    val t = rememberInfiniteTransition(label = "dot")
    val pulse by t.animateFloat(1f, 2.4f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "pulse")
    val color by animateColorAsState(if (on) online else MaterialTheme.colorScheme.outline, label = "dot")
    Box(modifier.size(10.dp), contentAlignment = Alignment.Center) {
        if (on) Box(Modifier.size(10.dp).scale(pulse).clip(CircleShape).background(online.copy(alpha = (2.4f - pulse) / 3f)))
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Header(host: SavedHost, conn: HostConnection?, onDetails: () -> Unit) {
    val link = conn?.link?.collectAsState()?.value ?: Link.Connecting
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(16.dp)).combinedClickable(onClick = onDetails, onLongClick = onDetails).padding(vertical = 4.dp)) {
        Text(host.name, style = MaterialTheme.typography.displayMediumEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(link is Link.Online)
            Spacer(Modifier.width(8.dp))
            AnimatedContent(link, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "link") { l ->
                Text(
                    when (l) {
                        is Link.Online -> listOf("Connected", l.os.ifBlank { host.os }).filter { it.isNotBlank() }.joinToString(" · ")
                        Link.Connecting -> "Connecting…"
                        is Link.Offline -> l.reason
                        is Link.Refused -> l.reason
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Switcher(hosts: List<SavedHost>, selected: String, live: Map<String, HostConnection>, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (h in hosts) {
            val on = live[h.id]?.link?.collectAsState()?.value is Link.Online
            val active = h.id == selected
            val bg by animateColorAsState(if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh, label = "chip")
            Surface(
                onClick = { onSelect(h.id) },
                shape = if (active) RoundedCornerShape(16.dp) else CircleShape,
                color = bg,
                modifier = Modifier.height(48.dp),
            ) {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(on)
                    Spacer(Modifier.width(10.dp))
                    Text(h.name, style = MaterialTheme.typography.labelLarge, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HostBody(conn: HostConnection, host: SavedHost) {
    val link by conn.link.collectAsStateWithLifecycle()
    val state by conn.state.collectAsStateWithLifecycle()
    AnimatedVisibility(link !is Link.Online, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        LinkCard(link, host.name, onRetry = conn::retry)
    }
    val s = state
    AnimatedVisibility(link is Link.Online && s != null, enter = fadeIn(tween(400)) + expandVertically(), exit = fadeOut()) {
        if (s != null) Dashboard(s, conn)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LinkCard(link: Link, name: String, onRetry: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val refused = link is Link.Refused
    Surface(shape = RoundedCornerShape(32.dp), color = if (refused) cs.errorContainer else cs.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when (link) {
                Link.Connecting -> {
                    LoadingIndicator(Modifier.size(72.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("Reaching $name…", style = MaterialTheme.typography.titleLarge)
                }
                is Link.Offline -> {
                    ShapeIcon(Icons.Rounded.LinkOff, MaterialShapes.Pill, cs.secondaryContainer, cs.onSecondaryContainer, size = 72.dp, iconSize = 32.dp)
                    Spacer(Modifier.height(16.dp))
                    Text(link.reason, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    Text("Make sure Switchboard is running on it and you're on the same Wi-Fi.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(16.dp))
                    FilledTonalButton(onClick = onRetry) {
                        Icon(Icons.Rounded.Refresh, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Try again")
                    }
                }
                is Link.Refused -> {
                    ShapeIcon(Icons.AutoMirrored.Rounded.Logout, MaterialShapes.Cookie6Sided, cs.error, cs.onError, size = 72.dp, iconSize = 32.dp)
                    Spacer(Modifier.height(16.dp))
                    Text(link.reason, style = MaterialTheme.typography.titleLarge, color = cs.onErrorContainer, textAlign = TextAlign.Center)
                    Text("Pair again from the computer's Pair a phone screen.", style = MaterialTheme.typography.bodyMedium, color = cs.onErrorContainer, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                }
                is Link.Online -> {}
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostSheet(host: SavedHost, conn: HostConnection?, onDismiss: () -> Unit, onForget: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    val link = conn?.link?.collectAsState()?.value
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            ShapeIcon(Icons.Rounded.Computer, MaterialShapes.Cookie9Sided, cs.primaryContainer, cs.onPrimaryContainer, size = 88.dp, iconSize = 40.dp)
            Spacer(Modifier.height(16.dp))
            Text(host.name, style = MaterialTheme.typography.headlineMediumEmphasized)
            Text(host.os.ifBlank { "Computer" }, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            Surface(shape = RoundedCornerShape(24.dp), color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Info("Status", if (link is Link.Online) "Connected" else "Not connected")
                    Info("Fingerprint", fingerprint(host.pk))
                    Info("Address", host.addrs.joinToString(", ") + " · port ${host.port}")
                }
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { confirm = true },
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = cs.errorContainer, contentColor = cs.onErrorContainer),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("Forget this computer") }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            icon = { ShapeIcon(Icons.Rounded.LinkOff, MaterialShapes.Cookie9Sided, cs.errorContainer, cs.onErrorContainer, size = 56.dp) },
            title = { Text("Forget ${host.name}?") },
            text = { Text("This phone stops controlling it, and the computer forgets this phone too. You can pair again any time.") },
            confirmButton = {
                Button(onClick = {
                    confirm = false
                    scope.launch { sheet.hide() }.invokeOnCompletion { onForget() }
                }, colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = cs.error, contentColor = cs.onError)) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Info(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(110.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
