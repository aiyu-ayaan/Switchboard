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
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import com.aiyu.switchboard.ui.kit.MonoBadge
import com.aiyu.switchboard.ui.kit.MonoCaption
import com.aiyu.switchboard.ui.kit.RoundIcon
import com.aiyu.switchboard.ui.kit.SectionCard
import com.aiyu.switchboard.ui.kit.bouncyClickable
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


@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LinkCard(link: Link, name: String, onRetry: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val refused = link is Link.Refused
    Surface(shape = RoundedCornerShape(32.dp), color = if (refused) cs.errorContainer else cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when (link) {
                Link.Connecting -> {
                    LoadingIndicator(Modifier.size(56.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("Reaching $name…", style = MaterialTheme.typography.titleMedium)
                }
                is Link.Offline -> {
                    ShapeIcon(Icons.Rounded.LinkOff, MaterialShapes.Pill, cs.secondaryContainer, cs.onSecondaryContainer, size = 56.dp, iconSize = 26.dp)
                    Spacer(Modifier.height(16.dp))
                    Text(link.reason, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    Text("Make sure Switchboard is running on it and you're on the same Wi-Fi.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(16.dp))
                    FilledTonalButton(onClick = onRetry) {
                        Icon(Icons.Rounded.Refresh, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Try again")
                    }
                }
                is Link.Refused -> {
                    ShapeIcon(Icons.AutoMirrored.Rounded.Logout, MaterialShapes.Cookie6Sided, cs.error, cs.onError, size = 56.dp, iconSize = 26.dp)
                    Spacer(Modifier.height(16.dp))
                    Text(link.reason, style = MaterialTheme.typography.titleMedium, color = cs.onErrorContainer, textAlign = TextAlign.Center)
                    Text("Pair again from the computer's Pair a phone screen.", style = MaterialTheme.typography.bodyMedium, color = cs.onErrorContainer, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                }
                is Link.Online -> {}
            }
        }
    }
}

@Composable
fun HostSheet(host: SavedHost, conn: HostConnection?, onDismiss: () -> Unit, onSettings: () -> Unit, onForget: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    val link = conn?.link?.collectAsState()?.value
    val on = link is Link.Online
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = cs.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShapeIcon(Icons.Rounded.Info, MaterialShapes.Cookie9Sided, cs.primaryContainer, cs.onPrimaryContainer, size = 40.dp, iconSize = 20.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Connection details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Active session information", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
            SectionCard(color = cs.surfaceContainer) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ShapeIcon(Icons.Rounded.Computer, MaterialShapes.Cookie6Sided, cs.secondaryContainer, cs.onSecondaryContainer, size = 40.dp, iconSize = 20.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(host.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        MonoCaption("${host.addrs.firstOrNull() ?: "—"}:${host.port}")
                    }
                    MonoBadge(if (on) "Connected" else "Offline", color = if (on) cs.primaryContainer else cs.surfaceContainerHighest, content = if (on) cs.onPrimaryContainer else cs.onSurfaceVariant)
                }
                Spacer(Modifier.height(12.dp))
                MonoBadge("E2EE · X25519 + ChaCha20-Poly1305", color = cs.surfaceContainerHigh, content = cs.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                MonoCaption("Fingerprint ${fingerprint(host.pk)}" + (host.os.takeIf { it.isNotBlank() }?.let { "  •  $it" } ?: ""))
            }
            SheetRow(Icons.Rounded.Settings, "Settings", "Theme, colours and about", cs.onSurface, onSettings)
            SheetRow(Icons.Rounded.LinkOff, "Forget this computer", "Unpair on both sides", cs.error) { confirm = true }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            icon = { ShapeIcon(Icons.Rounded.LinkOff, MaterialShapes.Cookie9Sided, cs.errorContainer, cs.onErrorContainer, size = 48.dp) },
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
private fun SheetRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, tint: Color, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    SectionCard(Modifier.bouncyClickable(onClick = onClick), color = cs.surfaceContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(icon, null, tint = tint)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = tint)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = cs.onSurfaceVariant)
        }
    }
}
