package com.switchboard.app.update

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.switchboard.app.ui.tapping
import com.switchboard.app.ui.toggling
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch

private val SNOOZE_CHOICES = listOf(1, 3, 7)

/**
 * Settings → App updates.
 *
 * A screen rather than four rows in settings, because choosing alpha is
 * choosing builds nobody has finished testing, and that belongs beside the
 * paragraph saying so.
 */
@Composable
fun UpdateScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by Updates.state.collectAsState()

    var enabled by remember { mutableStateOf(Updates.enabled) }
    var channel by remember { mutableStateOf(Updates.channel) }
    var snoozeDays by remember { mutableStateOf(Updates.snoozeDays) }

    // "Install unknown apps" is a per-app setting rather than a runtime
    // permission: it cannot be asked for with a dialog, only opened in
    // settings, and the answer only arrives when the user comes back here.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var canInstall by remember { mutableStateOf(Updates.canInstall(context)) }
    LaunchedEffect(lifecycle) {
        lifecycle.currentStateFlow.collect { current ->
            if (current.isAtLeast(Lifecycle.State.RESUMED)) canInstall = Updates.canInstall(context)
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            UpdateCard {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(42.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Filled.SystemUpdate,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Installed version",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                Updates.installedName,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(
                            onClick = tapping { scope.launch { Updates.check(manual = true) } },
                            enabled = state !is UpdateState.Checking,
                        ) {
                            if (state is UpdateState.Checking) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Check")
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = statusLine(state),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // The offer, once there is one.
        item {
            AnimatedVisibility(visible = state is UpdateState.Available || state is UpdateState.Downloading || state is UpdateState.Ready) {
                UpdateCard {
                    Column(Modifier.padding(18.dp)) {
                        val release = when (val current = state) {
                            is UpdateState.Available -> current.release
                            is UpdateState.Downloading -> current.release
                            is UpdateState.Ready -> current.release
                            else -> null
                        }
                        Text(
                            release?.name.orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Replacing ${Updates.installedName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        when (val current = state) {
                            is UpdateState.Downloading -> {
                                Spacer(Modifier.height(12.dp))
                                LinearProgressIndicator(
                                    progress = { current.progress },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }

                            is UpdateState.Available -> {
                                Spacer(Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = tapping {
                                        scope.launch {
                                            runCatching { Updates.download(current.release, current.apk) }
                                                .onFailure { Updates.fail(it.message ?: "The download failed") }
                                        }
                                    }) {
                                        Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Download")
                                    }
                                    TextButton(onClick = tapping { Updates.snooze() }) {
                                        Text("Not now")
                                    }
                                }
                            }

                            is UpdateState.Ready -> {
                                Spacer(Modifier.height(12.dp))
                                if (!canInstall) {
                                    Text(
                                        "Android needs permission to install apps from Switchboard. " +
                                            "It is a per-app setting, so it has to be granted in system settings.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Button(onClick = tapping { Updates.requestInstallPermission(context) }) {
                                        Text("Open settings")
                                    }
                                } else {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = tapping {
                                            scope.launch {
                                                runCatching { Updates.install(context, current.file) }
                                                    .onFailure {
                                                        Updates.fail(it.message ?: "The install could not be started")
                                                    }
                                            }
                                        }) {
                                            Text("Install")
                                        }
                                        TextButton(onClick = tapping { Updates.snooze() }) { Text("Not now") }
                                    }
                                }
                            }

                            else -> Unit
                        }

                        if (!release?.notes.isNullOrBlank()) {
                            Spacer(Modifier.height(14.dp))
                            ReleaseNotes(release.notes)
                        }
                    }
                }
            }
        }

        item {
            UpdateCard {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Check for updates",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "Switchboard is installed from a GitHub release rather than a store, " +
                                    "so nothing tells this phone a security fix exists unless the app looks.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = enabled,
                            onCheckedChange = toggling {
                                enabled = it
                                Updates.enabled = it
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        )
                    }
                }
            }
        }

        item {
            UpdateCard {
                Column(Modifier.padding(18.dp)) {
                    Text(
                        "Release channel",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        UpdateChannel.entries.forEach { option ->
                            FilterChip(
                                selected = channel == option,
                                onClick = tapping {
                                    channel = option
                                    Updates.channel = option
                                    scope.launch { Updates.check(manual = true) }
                                },
                                label = { Text(option.label) },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        channel.detail + " A channel also takes everything steadier than itself, " +
                            "so beta still sees the stable release that supersedes a beta build.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            UpdateCard {
                Column(Modifier.padding(18.dp)) {
                    Text(
                        "Remind me again after",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SNOOZE_CHOICES.forEach { days ->
                            FilterChip(
                                selected = snoozeDays == days,
                                onClick = tapping {
                                    snoozeDays = days
                                    Updates.snoozeDays = days
                                },
                                label = { Text(if (days == 1) "1 day" else "$days days") },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "How long \"Not now\" lasts. There is no \"never\": the version being " +
                            "refused is superseded next week, and a permanent refusal is what the " +
                            "switch above is for.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    // A failure is worth saying once rather than leaving on screen forever.
    val failure = (state as? UpdateState.Failed)?.message
    LaunchedEffect(failure) {
        if (failure != null) Toast.makeText(context, failure, Toast.LENGTH_LONG).show()
    }
}

private fun statusLine(state: UpdateState): String = when (state) {
    is UpdateState.Idle -> "Not checked yet in this session."
    is UpdateState.Checking -> "Asking GitHub what has been released…"
    is UpdateState.UpToDate -> "Switchboard is up to date."
    is UpdateState.Available -> "Version ${versionLabel(state.release)} is available."
    is UpdateState.Downloading -> "Downloading ${versionLabel(state.release)} — ${(state.progress * 100).toInt()}%"
    is UpdateState.Ready -> "Version ${versionLabel(state.release)} is downloaded and ready to install."
    is UpdateState.Failed -> state.message
}

private fun versionLabel(release: Release): String = release.name.removePrefix("v")

@Composable
private fun UpdateCard(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        content()
    }
}
