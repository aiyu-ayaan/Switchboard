package com.aiyu.switchboard.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiyu.switchboard.BuildConfig
import com.aiyu.switchboard.data.Vault
import com.aiyu.switchboard.net.AppUpdate
import com.aiyu.switchboard.net.UpdateChannel
import com.aiyu.switchboard.net.Updates
import com.aiyu.switchboard.ui.components.ShapeIcon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

private val installed = BuildConfig.VERSION_NAME

/** A dev build is its own app (`.dev`); a release APK would install beside it, not over it. */
private val released = Updates.versionKey(installed) != null

private fun Vault.channel(): UpdateChannel =
    data.value.updateChannel?.let { name -> UpdateChannel.entries.firstOrNull { it.name == name } } ?: Updates.channelOf(installed)

/** Downloads [update] and hands it to Android's installer, which asks to allow Switchboard the first time. */
private suspend fun install(context: Context, update: AppUpdate, onProgress: (Float) -> Unit) {
    val apk = Updates.download(update, File(context.cacheDir, "updates"), onProgress)
    context.startActivity(
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(FileProvider.getUriForFile(context, "${context.packageName}.updates", apk), "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/** One download at a time: [progress] is 0..1 while it runs, null otherwise. */
private class Installer(private val context: Context, private val scope: CoroutineScope) {
    var progress by mutableStateOf<Float?>(null)
        private set
    var failed by mutableStateOf(false)
        private set

    fun start(update: AppUpdate, onDone: () -> Unit = {}) {
        if (progress != null) return
        scope.launch {
            failed = false
            progress = 0f
            failed = runCatching { install(context, update) { progress = it } }.isFailure
            progress = null
            if (!failed) onDone()
        }
    }
}

@Composable
private fun rememberInstaller(): Installer {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    return remember { Installer(context, scope) }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Progress(value: Float) = LinearWavyProgressIndicator(progress = { value.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())

/** At start: offers a newer release on the chosen channel. "Later" is remembered for that version. */
@Composable
fun UpdatePrompt(vault: Vault) {
    var found by remember { mutableStateOf<AppUpdate?>(null) }
    val installer = rememberInstaller()
    LaunchedEffect(Unit) {
        if (!released) return@LaunchedEffect
        found = runCatching { Updates.check(installed, vault.channel()) }.getOrNull()
            ?.takeIf { it.version != vault.data.value.skippedUpdate }
    }
    val update = found ?: return
    val progress = installer.progress
    AlertDialog(
        onDismissRequest = { if (progress == null) found = null },
        icon = { Icon(Icons.Rounded.SystemUpdate, null) },
        title = { Text("Switchboard ${update.version} is out") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(if (installer.failed) "The download failed. Try again?" else "You have $installed. Your paired computers stay paired.")
                progress?.let { Progress(it) }
            }
        },
        confirmButton = {
            TextButton(enabled = progress == null, onClick = { installer.start(update) { found = null } }) {
                Text(progress?.let { "Downloading ${(it * 100).toInt()}%" } ?: "Update")
            }
        },
        dismissButton = {
            TextButton(enabled = progress == null, onClick = {
                vault.update { it.copy(skippedUpdate = update.version) }
                found = null
            }) { Text("Later") }
        },
    )
}

/** Settings > Updates: whether the channel has something newer, the button to take it, and the channel. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UpdatesGroup(vault: Vault) {
    val cs = MaterialTheme.colorScheme
    val data by vault.data.collectAsStateWithLifecycle()
    val channel = data.updateChannel?.let { n -> UpdateChannel.entries.firstOrNull { it.name == n } } ?: Updates.channelOf(installed)
    // null: still asking.
    var result by remember { mutableStateOf<Result<AppUpdate?>?>(null) }
    var asked by remember { mutableIntStateOf(0) }
    LaunchedEffect(channel, asked) {
        if (!released) return@LaunchedEffect
        result = null
        result = runCatching { Updates.check(installed, channel) }
    }
    val installer = rememberInstaller()
    val progress = installer.progress
    val found = result?.getOrNull()

    Surface(shape = RoundedCornerShape(28.dp), color = if (found != null) cs.primaryContainer else cs.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShapeIcon(Icons.Rounded.SystemUpdate, MaterialShapes.Cookie6Sided, cs.secondaryContainer, cs.onSecondaryContainer)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            !released -> "Updates come with rebuilding"
                            found != null -> "Switchboard ${found.version} is out"
                            result == null -> "Checking for updates…"
                            result?.isFailure == true -> "Couldn't check for updates"
                            else -> "You're up to date"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        when {
                            !released -> "This is a development build ($installed)."
                            installer.failed -> "The download failed. Try again."
                            else -> "You have $installed."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
            progress?.let { Progress(it) }
            if (found != null) {
                Button({ installer.start(found) }, Modifier.fillMaxWidth(), enabled = progress == null) {
                    Text(progress?.let { "Downloading ${(it * 100).toInt()}%" } ?: "Download and install")
                }
            } else if (released && result != null) {
                OutlinedButton({ asked++ }, Modifier.fillMaxWidth()) { Text("Check again") }
            }
            if (released) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                    UpdateChannel.entries.forEachIndexed { i, c ->
                        ToggleButton(
                            checked = channel == c,
                            onCheckedChange = { vault.update { it.copy(updateChannel = c.name) } },
                            enabled = progress == null,
                            shapes = when (i) {
                                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                UpdateChannel.entries.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                            },
                            modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                        ) { Text(c.label) }
                    }
                }
                Text("Beta also takes stable releases; Alpha takes every release.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
    }
}
