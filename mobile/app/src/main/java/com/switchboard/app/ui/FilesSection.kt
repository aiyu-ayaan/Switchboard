package com.switchboard.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.sin
import com.switchboard.app.net.Control
import com.switchboard.app.net.Direction
import com.switchboard.app.net.FileProgress
import com.switchboard.app.net.TransferStatus
import com.switchboard.app.transfer.RateUnit
import com.switchboard.app.transfer.TransferMath

/**
 * The Files section body, rendered in the same card stack as the other
 * sections. The document picker lives here rather than in the ViewModel: a SAF
 * launcher is bound to the composition that owns it, and hoisting it would
 * only add a callback hop.
 */
@Composable
fun FilesBody(
    transfers: List<FileProgress>,
    rateUnit: RateUnit,
    onSendFiles: (List<Uri>) -> Unit,
    onSendFolder: (Uri) -> Unit,
    onControl: (String, String) -> Unit,
    onSendFile: (Uri) -> Unit = { onSendFiles(listOf(it)) }
) {
    val context = LocalContext.current
    var blocked by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val filesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (!uris.isNullOrEmpty()) onSendFiles(uris)
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri != null) onSendFolder(treeUri)
    }

    // Asked before the picker rather than after: a transfer that starts and
    // then silently loses its progress notification looks like a stalled app.
    val notifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        blocked = !granted
        pendingAction?.invoke()
        pendingAction = null
    }

    fun launchWithNotifications(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pendingAction = action
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }

    val active = transfers.filterNot { TransferStatus.isTerminal(it.status) }
    val history = transfers.filter { TransferStatus.isTerminal(it.status) }

    SectionCard {
        Column {
            Text(
                text = "Send to Desktop",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Stream files or entire folders straight to your computer over the encrypted session.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { launchWithNotifications { filesPicker.launch(arrayOf("*/*")) } },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Files", maxLines = 1)
                }
                FilledTonalButton(
                    onClick = { launchWithNotifications { folderPicker.launch(null) } },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Folder", maxLines = 1)
                }
            }
        }
    }

    if (active.isEmpty() && history.isEmpty()) {
        EmptyCard(
            title = "No transfers yet",
            body = "Pick a file to send, or drop one from the desktop. Incoming files land " +
                "in the save folder chosen in Settings."
        )
    }

    active.forEach { transfer ->
        ActiveTransferCard(transfer, rateUnit, onControl)
    }

    if (history.isNotEmpty()) {
        SectionCard {
            Text(
                text = "History",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(10.dp))
            history.forEach { transfer ->
                HistoryRow(transfer)
                Spacer(Modifier.height(10.dp))
            }
        }
    }

    if (blocked) {
        AlertDialog(
            onDismissRequest = { blocked = false },
            title = { Text("Notifications are off") },
            text = {
                Text(
                    "Transfers still run, but Switchboard cannot show progress, speed, or the " +
                        "pause and cancel buttons outside the app. Turn notifications on to get them back."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    blocked = false
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.fromParts("package", context.packageName, null))
                    )
                }) { Text("Open settings") }
            },
            dismissButton = {
                TextButton(onClick = { blocked = false }) { Text("Not now") }
            }
        )
    }
}

@Composable
private fun ActiveTransferCard(
    transfer: FileProgress,
    rateUnit: RateUnit,
    onControl: (String, String) -> Unit
) {
    val paused = transfer.status == TransferStatus.PAUSED
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (transfer.direction == Direction.DOWNLOAD) {
                            Icons.Filled.Download
                        } else {
                            Icons.Filled.Upload
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = transfer.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (transfer.status == TransferStatus.PENDING) {
                        "Queued - ${TransferMath.formatBytes(transfer.size)}"
                    } else {
                        "${TransferMath.formatBytes(transfer.transferred)} of " +
                            "${TransferMath.formatBytes(transfer.size)} - " +
                            TransferMath.formatRate(transfer.bytesPerSec, rateUnit)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            CircleAction(
                icon = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                label = if (paused) "Resume" else "Pause",
                onClick = { onControl(transfer.transferId, if (paused) Control.RESUME else Control.PAUSE) }
            )
            Spacer(Modifier.width(8.dp))
            CircleAction(
                icon = Icons.Filled.Cancel,
                label = "Cancel",
                onClick = { onControl(transfer.transferId, Control.CANCEL) }
            )
        }
        Spacer(Modifier.height(14.dp))
        ExpressiveProgress(fraction = transfer.fraction)
    }
}

@Composable
private fun HistoryRow(transfer: FileProgress) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = when (transfer.status) {
                TransferStatus.COMPLETED -> Icons.Filled.CheckCircle
                TransferStatus.CANCELLED -> Icons.Filled.Cancel
                else -> Icons.Filled.ErrorOutline
            },
            contentDescription = null,
            tint = if (transfer.status == TransferStatus.COMPLETED) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = transfer.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = transfer.error.ifEmpty {
                    "${if (transfer.direction == Direction.DOWNLOAD) "Received" else "Sent"} - " +
                        TransferMath.formatBytes(transfer.size)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun CircleAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier
            .size(38.dp)
            .bouncyClickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * The expressive "snake" bar: the completed span is a travelling sine, the
 * remainder a flat track. Drawn by hand because this Compose Material3 version
 * has no wavy indicator, and pulling a newer one in for one bar is not worth
 * the dependency.
 */
@Composable
private fun ExpressiveProgress(fraction: Float) {
    val phase by rememberInfiniteTransition(label = "wave").animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "wavePhase"
    )
    val wave = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(16.dp)
    ) {
        val middle = size.height / 2
        val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
        val done = size.width * fraction.coerceIn(0f, 1f)

        if (done < size.width) {
            drawLine(track, Offset(done, middle), Offset(size.width, middle), stroke.width, StrokeCap.Round)
        }
        if (done > 0f) {
            val amplitude = size.height / 4
            val wavelength = 18.dp.toPx()
            val path = Path().apply {
                moveTo(0f, middle)
                var x = 0f
                while (x <= done) {
                    lineTo(x, middle + amplitude * sin(x / wavelength * 2 * Math.PI.toFloat() - phase))
                    x += 2f
                }
            }
            drawPath(path, wave, style = stroke)
        }
    }
}
