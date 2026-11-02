package com.aiyu.switchboard.ui.system

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.aiyu.switchboard.net.Cmd
import com.aiyu.switchboard.net.HostConnection
import com.aiyu.switchboard.net.HostState
import com.aiyu.switchboard.ui.kit.MonoCaption
import com.aiyu.switchboard.ui.kit.PodHeader
import com.aiyu.switchboard.ui.kit.SectionCard
import kotlinx.coroutines.launch

private fun label(t: String) = when (t) {
    "dark_mode" -> "Dark mode"
    "do_not_disturb" -> "Do not disturb"
    "wifi" -> "Wi-Fi"
    "bluetooth" -> "Bluetooth"
    else -> t
}

private fun icon(t: String): ImageVector = when (t) {
    "dark_mode" -> Icons.Rounded.DarkMode
    "do_not_disturb" -> Icons.Rounded.DoNotDisturbOn
    "wifi" -> Icons.Rounded.Wifi
    else -> Icons.Rounded.Bluetooth
}

/** The computer's quick settings; only the ones it can change are listed. */
@Composable
fun QuickSettings(s: HostState, conn: HostConnection) {
    if (s.toggles.isEmpty()) return
    var confirmWifiOff by remember { mutableStateOf(false) }
    SectionCard {
        PodHeader(Icons.Rounded.Tune, "Quick settings", "Switches on the computer", shape = MaterialShapes.Cookie6Sided)
        s.toggles.forEach { t ->
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon(t.toggle), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(14.dp))
                Text(label(t.toggle), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(
                    checked = t.on,
                    onCheckedChange = { on ->
                        // Wi-Fi off cuts this very connection when the computer is on Wi-Fi, and it cannot be undone from here.
                        if (t.toggle == "wifi" && !on) confirmWifiOff = true else conn.post(Cmd.setToggle(t.toggle, on))
                    },
                )
            }
        }
    }
    if (confirmWifiOff) {
        AlertDialog(
            onDismissRequest = { confirmWifiOff = false },
            title = { Text("Turn Wi-Fi off on the computer?") },
            text = { Text("If it is connected by Wi-Fi, this phone loses it too, and you cannot turn it back on from here.") },
            confirmButton = { Button(onClick = { confirmWifiOff = false; conn.post(Cmd.setToggle("wifi", false)) }) { Text("Turn off") } },
            dismissButton = { TextButton(onClick = { confirmWifiOff = false }) { Text("Cancel") } },
        )
    }
}

/** Text goes either way: this phone's clipboard to the computer, or the computer's to the phone. */
@Composable
fun ClipboardCard(conn: HostConnection) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<String?>(null) }
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    SectionCard {
        PodHeader(Icons.Rounded.ContentPaste, "Clipboard", "Copy on one, paste on the other", shape = MaterialShapes.Clover4Leaf)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                modifier = Modifier.weight(1f),
                onClick = {
                    val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                    if (text.isEmpty()) {
                        note = "Nothing to send: copy some text on this phone first."
                    } else {
                        scope.launch {
                            val r = conn.send(Cmd.clipboardSet(text))
                            note = if (r.ok) "Sent ${text.length} characters." else r.error
                        }
                    }
                },
            ) { Text("Send mine") }
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = {
                    scope.launch {
                        val r = conn.send(Cmd.clipboardGet())
                        val text = r.data.orEmpty()
                        if (!r.ok) {
                            note = r.error
                        } else if (text.isEmpty()) {
                            note = "The computer's clipboard has no text."
                        } else {
                            cm.setPrimaryClip(ClipData.newPlainText("Switchboard", text))
                            note = "Copied ${text.length} characters to this phone."
                        }
                    }
                },
            ) { Text("Get theirs") }
        }
        note?.let {
            Spacer(Modifier.height(8.dp))
            MonoCaption(it, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
