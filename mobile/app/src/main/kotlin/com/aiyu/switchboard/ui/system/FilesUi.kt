package com.aiyu.switchboard.ui.system

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiyu.switchboard.net.HostConnection
import com.aiyu.switchboard.net.Transfer
import com.aiyu.switchboard.net.Transfers
import com.aiyu.switchboard.ui.kit.MonoCaption
import com.aiyu.switchboard.ui.kit.PodHeader
import com.aiyu.switchboard.ui.kit.SectionCard

fun sizeText(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> "%d KB".format(b shr 10)
    else -> "$b B"
}

/** Home card: how many files are moving, and a way in. */
@Composable
fun FilesPod(conn: HostConnection, transfers: Transfers, hostId: String, onOpen: () -> Unit) {
    val all by transfers.items.collectAsStateWithLifecycle()
    val offers by conn.offers.collectAsStateWithLifecycle()
    val running = all.count { it.hostId == hostId && it.state == Transfer.State.Running }
    SectionCard {
        PodHeader(
            Icons.Rounded.Folder,
            "Files",
            when {
                offers.isNotEmpty() -> "${offers.size} waiting for you"
                running > 0 -> "$running moving now"
                else -> "Send to the computer or receive from it"
            },
            shape = MaterialShapes.Clover4Leaf,
            action = "Open",
            onAction = onOpen,
        )
        OfferCards(conn, transfers, hostId)
    }
}

/** Files the computer wants to send: take or decline each. */
@Composable
fun OfferCards(conn: HostConnection, transfers: Transfers, hostId: String) {
    val offers by conn.offers.collectAsStateWithLifecycle()
    offers.forEach { o ->
        Spacer(Modifier.height(12.dp))
        Column(Modifier.fillMaxWidth()) {
            Text(o.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            MonoCaption("${sizeText(o.size)} from this computer")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { transfers.download(conn, hostId, o) }) { Text("Save") }
                TextButton(onClick = { transfers.decline(conn, o) }) { Text("Decline") }
            }
        }
    }
}

/** The Files screen: pick files to send, take offers, watch progress. */
@Composable
fun FilesContent(conn: HostConnection, transfers: Transfers, hostId: String) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { transfers.upload(conn, hostId, it) }
    }
    val all by transfers.items.collectAsStateWithLifecycle()
    val mine = all.filter { it.hostId == hostId }
    SectionCard {
        PodHeader(Icons.Rounded.Upload, "Send to the computer", "Saved in its Downloads/Switchboard folder", shape = MaterialShapes.Cookie6Sided)
        Spacer(Modifier.height(12.dp))
        Button(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Choose files") }
        OfferCards(conn, transfers, hostId)
    }
    SectionCard {
        PodHeader(
            Icons.Rounded.Download,
            "Transfers",
            if (mine.isEmpty()) "Nothing yet" else "${mine.size} recent",
            shape = MaterialShapes.Cookie9Sided,
            trailing = { if (mine.any { it.state != Transfer.State.Running }) TextButton(onClick = transfers::clearFinished) { Text("Clear") } },
        )
        mine.forEach { t -> TransferRow(t) }
    }
}

@Composable
private fun TransferRow(t: Transfer) {
    val cs = MaterialTheme.colorScheme
    Spacer(Modifier.height(14.dp))
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (t.upload) Icons.Rounded.Upload else Icons.Rounded.Download, null, tint = cs.primary)
            Spacer(Modifier.width(10.dp))
            Text(t.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        when (t.state) {
            Transfer.State.Running -> {
                LinearWavyProgressIndicator(progress = { t.fraction }, modifier = Modifier.fillMaxWidth())
                MonoCaption("${sizeText(t.done)} of ${sizeText(t.total)}")
            }
            Transfer.State.Done -> MonoCaption("${sizeText(t.total)} · ${if (t.upload) "sent" else "saved to Downloads/Switchboard"}", color = cs.primary)
            Transfer.State.Failed -> MonoCaption(t.error ?: "Failed", color = cs.error)
        }
    }
}
