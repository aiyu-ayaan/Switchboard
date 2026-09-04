package com.switchboard.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.switchboard.app.data.KnownHost

/**
 * Shown when no host is connected: scan a new desktop, or pick one already
 * paired. Reconnecting needs no code, only the stored identity key.
 */
@Composable
fun PairingScreen(
    hosts: List<KnownHost>,
    error: String?,
    onScan: () -> Unit,
    onManual: (String, String) -> Unit,
    onConnect: (KnownHost) -> Unit,
    onForget: (KnownHost) -> Unit,
    modifier: Modifier = Modifier
) {
    var confirmForget by remember { mutableStateOf<KnownHost?>(null) }
    var manualOpen by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        "Connect a desktop",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Open Switchboard on your computer and scan the pairing code. " +
                            "Both devices must be on the same network.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = onScan,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Scan QR code")
                    }
                    Spacer(Modifier.height(8.dp))
                    // Scanning is not always possible: no camera permission, a
                    // damaged lens, or a desktop across the room.
                    OutlinedButton(
                        onClick = { manualOpen = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Icon(Icons.Filled.Keyboard, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Enter code manually")
                    }
                }
            }
        }

        if (error != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    // Errors say what to do next, not just what broke.
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }

        if (hosts.isNotEmpty()) {
            item {
                Text(
                    "Paired desktops",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            items(hosts, key = { it.daemonId }) { host ->
                HostRow(
                    host = host,
                    onConnect = { onConnect(host) },
                    onForget = { confirmForget = host }
                )
            }
        }
    }

    if (manualOpen) {
        ManualPairingDialog(
            onDismiss = { manualOpen = false },
            onSubmit = { address, code ->
                manualOpen = false
                onManual(address, code)
            }
        )
    }

    confirmForget?.let { host ->
        AlertDialog(
            onDismissRequest = { confirmForget = null },
            title = { Text("Forget ${host.hostName}?") },
            text = {
                Text(
                    "This deletes the keys for this desktop. You will need to scan its " +
                        "pairing code again to reconnect."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onForget(host)
                    confirmForget = null
                }) {
                    Text("Forget", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmForget = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun HostRow(host: KnownHost, onConnect: () -> Unit, onForget: () -> Unit) {
    Card(
        onClick = onConnect,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Computer,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(host.hostName, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${host.host}:${host.port}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onForget, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Forget ${host.hostName}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Manual pairing.
 *
 * Takes the address and the code exactly as the desktop prints them. The code
 * is the same secret the QR carries, so a typed pairing is no weaker than a
 * scanned one.
 */
@Composable
private fun ManualPairingDialog(onDismiss: () -> Unit, onSubmit: (String, String) -> Unit) {
    var address by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter pairing details") },
        text = {
            Column {
                Text(
                    "Both values are shown on the Devices screen of the desktop app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Host address") },
                    placeholder = { Text("192.168.1.10:9427") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.uppercase() },
                    label = { Text("Pairing code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(address, code) },
                enabled = address.isNotBlank() && code.isNotBlank()
            ) {
                Text("Connect")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
