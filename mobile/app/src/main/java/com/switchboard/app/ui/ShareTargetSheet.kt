package com.switchboard.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.switchboard.app.data.KnownHost

/**
 * Where files shared into Switchboard from another app go.
 *
 * The system share sheet has already chosen the app; this chooses the desktop.
 * Every paired host is listed rather than only the live ones, because the
 * phone probes reachability every three seconds and a host that has just gone
 * quiet is usually still there — tapping it connects first and sends after.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareTargetSheet(
    fileCount: Int,
    hosts: List<KnownHost>,
    liveHostIds: Set<String>,
    activeHostId: String?,
    onPick: (KnownHost) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text(
                    text = if (fileCount == 1) "Send 1 file to" else "Send $fileCount files to",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Choose a paired desktop",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (hosts.isEmpty()) {
                Text(
                    text = "No desktop is paired yet. Scan a pairing code in Switchboard first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                )
            }

            hosts.forEach { host ->
                val live = host.daemonId in liveHostIds
                val active = host.daemonId == activeHostId
                ListItem(
                    modifier = Modifier.bouncyClickable { onPick(host) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Filled.Computer,
                            contentDescription = null,
                            tint = if (live) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    },
                    headlineContent = {
                        Text(host.hostName, fontWeight = FontWeight.SemiBold)
                    },
                    supportingContent = {
                        Text(
                            text = when {
                                active -> "Connected"
                                live -> "Online"
                                else -> "Offline — will try to connect"
                            },
                            style = MaterialTheme.typography.labelMedium
                        )
                    },
                    trailingContent = {
                        Surface(
                            modifier = Modifier.size(10.dp).clip(CircleShape),
                            color = if (live) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        ) {}
                    }
                )
            }
        }
    }
}
