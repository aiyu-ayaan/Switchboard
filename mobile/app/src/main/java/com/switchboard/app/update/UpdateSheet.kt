package com.switchboard.app.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * What a launch check does when it finds something.
 *
 * Two answers and no third: install it, or not now — which is a snooze with a
 * length the update screen sets. There is deliberately no "never" here: the
 * version being refused is superseded next week, and a permanent refusal is
 * what the switch on that screen is for.
 *
 * Nothing is downloaded before somebody asks. A phone is somebody's storage and
 * quite possibly somebody's data allowance, and spending either on a decision
 * they have not made is not a decision this app gets to take.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSheet(onOpenSettings: () -> Unit) {
    val state by Updates.state.collectAsState()
    val release = when (val current = state) {
        is UpdateState.Available -> current.release
        is UpdateState.Downloading -> current.release
        is UpdateState.Ready -> current.release
        else -> null
    } ?: return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()
    var permissionNeeded by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = { Updates.dismiss() },
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.SystemUpdate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        release.name.ifBlank { "A new version" },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Replacing ${Updates.installedName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (release.notes.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                Column(
                    Modifier
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    ReleaseNotes(release.notes)
                }
            }

            Spacer(Modifier.height(20.dp))

            when (val current = state) {
                is UpdateState.Downloading -> LinearProgressIndicator(
                    progress = { current.progress },
                    modifier = Modifier.fillMaxWidth(),
                )

                is UpdateState.Ready -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (!Updates.canInstall(context)) {
                                permissionNeeded = true
                                Updates.requestInstallPermission(context)
                                return@Button
                            }
                            scope.launch {
                                runCatching { Updates.install(context, current.file) }.onFailure {
                                    Updates.fail(it.message ?: "The install could not be started")
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Install") }
                    TextButton(onClick = { Updates.snooze() }) { Text("Not now") }
                }

                is UpdateState.Available -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                runCatching { Updates.download(current.release, current.apk) }
                                    .onFailure { Updates.fail(it.message ?: "The download failed") }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Download") }
                    TextButton(onClick = { Updates.snooze() }) { Text("Not now") }
                }

                else -> Unit
            }

            if (permissionNeeded) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Android needs permission to install apps from Switchboard. Grant it in the " +
                        "settings page that just opened, then press Install again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onOpenSettings) { Text("Update settings") }
        }
    }
}
