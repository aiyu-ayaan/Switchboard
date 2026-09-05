package com.switchboard.app.transfer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import com.switchboard.app.data.TransferPreferences
import com.switchboard.app.net.WirePayload
import com.switchboard.app.net.Actions
import com.switchboard.app.net.CHUNK_SIZE
import com.switchboard.app.net.Control
import com.switchboard.app.net.Direction
import com.switchboard.app.net.FileAccept
import com.switchboard.app.net.FileAck
import com.switchboard.app.net.FileChunk
import com.switchboard.app.net.FileComplete
import com.switchboard.app.net.FileControl
import com.switchboard.app.net.FileOffer
import com.switchboard.app.net.FileProgress
import com.switchboard.app.net.SwitchboardJson
import com.switchboard.app.net.TransferStatus
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement

/**
 * Drives file transfers over the already-encrypted session socket.
 *
 * The engine is process-scoped rather than owned by the ViewModel: a transfer
 * that stops when the user leaves the screen is worse than no transfer at all,
 * and the foreground service that keeps the process alive needs the same
 * instance the UI is watching.
 *
 * Bytes are never held whole. Sends stream out of the [android.content.ContentResolver],
 * receives land in a `.part` file at the offset the peer states, and only the
 * final publish copies into the user's chosen folder — so a file larger than
 * the heap transfers exactly like a small one.
 */
class TransferEngine private constructor(
    private val context: Context,
    private val prefs: TransferPreferences
) {
    /** All transfers this process has seen, newest first; terminal ones are the history. */
    private val _transfers = MutableStateFlow<List<FileProgress>>(emptyList())
    val transfers: StateFlow<List<FileProgress>> = _transfers.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Inbound frames are queued rather than handled where they arrive: chunks
     * must be applied in the order the peer sent them, and the collector is on
     * the main thread where a disk write does not belong.
     */
    private val inbound = Channel<Pair<String, JsonElement>>(Channel.UNLIMITED)

    private val live = mutableMapOf<String, Live>()

    /** Set while a session is up. Cleared on disconnect, which fails everything in flight. */
    @Volatile
    private var sender: ((String, WirePayload) -> Unit)? = null

    private class Live(
        val offer: FileOffer,
        /** Highest byte the peer has confirmed; the send window is measured against it. */
        val acked: MutableStateFlow<Long> = MutableStateFlow(0L),
        val paused: MutableStateFlow<Boolean> = MutableStateFlow(false),
        val accept: CompletableDeferred<FileAccept> = CompletableDeferred(),
        val done: CompletableDeferred<FileComplete> = CompletableDeferred(),
        var job: Job? = null,
        var part: File? = null,
        var sink: RandomAccessFile? = null,
        var startedAt: Long = System.currentTimeMillis()
    )

    init {
        scope.launch {
            for ((action, payload) in inbound) {
                runCatching { handle(action, payload) }
                    .onFailure { Log.w(TAG, "dropping $action: ${it.message}") }
            }
        }
    }

    // ---- Session wiring ----

    fun bind(send: (String, WirePayload) -> Unit) {
        sender = send
    }

    /**
     * A dropped socket cannot be resumed transparently: the peer forgets the
     * transfer, so anything in flight is failed here rather than left spinning
     * against a socket that will never ack again.
     */
    fun unbind() {
        sender = null
        live.keys.toList().forEach { id ->
            live[id]?.job?.cancel()
            closeReceive(id, keepPart = true)
            finish(id, TransferStatus.FAILED, "Connection lost")
        }
        live.clear()
    }

    fun onFrame(action: String, payload: JsonElement) {
        inbound.trySend(action to payload)
    }

    // ---- Sending ----

    /** Starts an upload for a document the user picked through the SAF. */
    fun send(uri: Uri) {
        val transferId = UUID.randomUUID().toString()
        val (name, size) = readMetadata(uri)

        if (sender == null) {
            publish(FileProgress(transferId, name, Direction.UPLOAD, TransferStatus.FAILED, 0, size, error = "Not connected"))
            return
        }

        val offer = FileOffer(transferId, name, size, mimeOf(uri), "", Direction.UPLOAD)
        val entry = Live(offer)
        live[transferId] = entry
        TransferService.start(context)
        publish(FileProgress(transferId, name, Direction.UPLOAD, TransferStatus.PENDING, 0, size, startedAt = entry.startedAt))

        entry.job = scope.launch {
            runCatching { upload(uri, entry) }
                .onFailure { failure ->
                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                    finish(transferId, TransferStatus.FAILED, reasonOf(failure))
                }
            live.remove(transferId)
        }
    }

    private suspend fun upload(uri: Uri, entry: Live) {
        val id = entry.offer.transferId
        val emit = sender ?: error("Not connected")

        // The digest is computed before the offer so the receiver can verify
        // without a second pass over a file it may not be able to buffer.
        val sha = openStream(uri).use { TransferMath.sha256Hex(it) }
        emit(Actions.FILE_OFFER, entry.offer.copy(sha256 = sha))

        val accept = withTimeout(HANDSHAKE_TIMEOUT_MS) { entry.accept.await() }
        if (!accept.accepted) {
            finish(id, TransferStatus.CANCELLED, accept.reason.ifEmpty { "Declined by desktop" })
            return
        }

        val size = entry.offer.size
        var offset = TransferMath.resumeOffset(accept.offset, size)
        entry.acked.value = offset
        update(id) { it.copy(status = TransferStatus.ACTIVE, transferred = offset) }

        openStream(uri).use { stream ->
            skipFully(stream, offset)
            val buffer = ByteArray(CHUNK_SIZE)
            var marker = offset
            var markerAt = System.currentTimeMillis()

            while (offset < size) {
                entry.paused.first { !it }

                val want = TransferMath.chunkLength(size, offset)
                val read = readFully(stream, buffer, want)
                if (read <= 0) error("File ended early at $offset of $size bytes")

                emit(
                    Actions.FILE_CHUNK,
                    FileChunk(
                        transferId = id,
                        offset = offset,
                        data = Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP),
                        last = offset + read >= size
                    )
                )
                offset += read

                val now = System.currentTimeMillis()
                if (now - markerAt >= RATE_WINDOW_MS) {
                    val rate = TransferMath.bytesPerSec(offset - marker, now - markerAt)
                    marker = offset
                    markerAt = now
                    update(id) { it.copy(transferred = offset, bytesPerSec = rate) }
                } else {
                    update(id) { it.copy(transferred = offset) }
                }

                // Without this the sender would queue the whole file into the
                // socket's write buffer and report a finished transfer the
                // receiver has barely started.
                if (offset - entry.acked.value > SEND_WINDOW) {
                    try {
                        withTimeout(ACK_TIMEOUT_MS) {
                            entry.acked.first { offset - it <= SEND_WINDOW }
                        }
                    } catch (_: TimeoutCancellationException) {
                        error("Desktop stopped acknowledging at ${TransferMath.formatBytes(entry.acked.value)}")
                    }
                }
            }
        }

        val result = withTimeout(HANDSHAKE_TIMEOUT_MS) { entry.done.await() }
        if (result.ok && TransferMath.digestMatches(sha, result.sha256)) {
            finish(id, TransferStatus.COMPLETED, "")
        } else {
            finish(id, TransferStatus.FAILED, result.error.ifEmpty { "Desktop reported a checksum mismatch" })
        }
    }

    // ---- Receiving ----

    private fun onOffer(offer: FileOffer) {
        val emit = sender ?: return
        val entry = Live(offer)
        live[offer.transferId] = entry
        TransferService.start(context)
        publish(
            FileProgress(
                offer.transferId, offer.name, Direction.DOWNLOAD,
                TransferStatus.ACTIVE, 0, offer.size, startedAt = entry.startedAt
            )
        )

        if (prefs.config.value.saveDirectory.isEmpty()) {
            emit(Actions.FILE_ACCEPT, FileAccept(offer.transferId, 0, false, "No save folder chosen on the phone"))
            live.remove(offer.transferId)
            finish(offer.transferId, TransferStatus.FAILED, "Choose a save folder in Settings, then ask again.")
            return
        }

        val part = File(partDir(), "${offer.transferId}.part")
        // A part file left by an interrupted attempt is the resume point; the
        // peer is told how much we hold so it seeks instead of restarting.
        val held = TransferMath.resumeOffset(part.length(), offer.size)
        if (held != part.length()) part.delete()

        entry.part = part
        entry.sink = RandomAccessFile(part, "rw")
        entry.acked.value = held
        update(offer.transferId) { it.copy(transferred = held) }
        emit(Actions.FILE_ACCEPT, FileAccept(offer.transferId, held))
    }

    private fun onChunk(chunk: FileChunk) {
        val entry = live[chunk.transferId] ?: return
        val sink = entry.sink ?: return
        val emit = sender ?: return

        val bytes = Base64.decode(chunk.data, Base64.NO_WRAP)
        // The peer's offset is authoritative rather than a running local count,
        // so a resumed or re-ordered stream still lands in the right place.
        sink.seek(chunk.offset)
        sink.write(bytes)

        val received = chunk.offset + bytes.size
        entry.acked.value = received
        emit(Actions.FILE_ACK, FileAck(chunk.transferId, received))

        val elapsed = System.currentTimeMillis() - entry.startedAt
        update(chunk.transferId) {
            it.copy(transferred = received, bytesPerSec = TransferMath.bytesPerSec(received, elapsed))
        }

        if (chunk.last || received >= entry.offer.size) {
            completeReceive(entry, emit)
        }
    }

    private fun completeReceive(entry: Live, emit: (String, WirePayload) -> Unit) {
        val id = entry.offer.transferId
        val part = entry.part
        entry.sink?.close()
        entry.sink = null

        val actual = part?.inputStream()?.use { TransferMath.sha256Hex(it) }.orEmpty()
        if (!TransferMath.digestMatches(entry.offer.sha256, actual)) {
            part?.delete()
            emit(Actions.FILE_COMPLETE, FileComplete(id, false, actual, "checksum mismatch"))
            live.remove(id)
            finish(id, TransferStatus.FAILED, "The file arrived corrupted and was discarded.")
            return
        }

        val published = runCatching { publishToSaveDirectory(entry, part!!) }
        part?.delete()
        live.remove(id)

        published.fold(
            onSuccess = {
                emit(Actions.FILE_COMPLETE, FileComplete(id, true, actual))
                finish(id, TransferStatus.COMPLETED, "")
            },
            onFailure = {
                emit(Actions.FILE_COMPLETE, FileComplete(id, false, actual, reasonOf(it)))
                finish(id, TransferStatus.FAILED, reasonOf(it))
            }
        )
    }

    /**
     * Moves the verified file into the tree the user granted. Written through
     * the SAF rather than a filesystem path so the app needs no broad storage
     * permission on any supported release.
     */
    private fun publishToSaveDirectory(entry: Live, part: File) {
        val tree = Uri.parse(prefs.config.value.saveDirectory)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val target = DocumentsContract.createDocument(
            context.contentResolver,
            parent,
            entry.offer.mimeType.ifEmpty { "application/octet-stream" },
            entry.offer.name
        ) ?: error("Cannot write to the chosen save folder")

        context.contentResolver.openOutputStream(target)?.use { out ->
            part.inputStream().use { it.copyTo(out, CHUNK_SIZE) }
        } ?: error("Cannot write to the chosen save folder")
    }

    // ---- Control ----

    fun control(transferId: String, action: String) {
        val entry = live[transferId]
        sender?.invoke(Actions.FILE_CONTROL, FileControl(transferId, action))
        when (action) {
            Control.PAUSE -> {
                entry?.paused?.value = true
                update(transferId) { it.copy(status = TransferStatus.PAUSED) }
            }
            Control.RESUME -> {
                entry?.paused?.value = false
                update(transferId) { it.copy(status = TransferStatus.ACTIVE) }
            }
            Control.CANCEL -> {
                entry?.job?.cancel()
                closeReceive(transferId, keepPart = false)
                live.remove(transferId)
                finish(transferId, TransferStatus.CANCELLED, "")
            }
        }
    }

    fun clearHistory() {
        _transfers.update { list -> list.filterNot { TransferStatus.isTerminal(it.status) } }
    }

    // ---- Frame dispatch ----

    private fun handle(action: String, payload: JsonElement) {
        when (action) {
            Actions.FILE_OFFER ->
                onOffer(SwitchboardJson.decodeFromJsonElement(FileOffer.serializer(), payload))

            Actions.FILE_CHUNK ->
                onChunk(SwitchboardJson.decodeFromJsonElement(FileChunk.serializer(), payload))

            Actions.FILE_ACCEPT -> {
                val accept = SwitchboardJson.decodeFromJsonElement(FileAccept.serializer(), payload)
                live[accept.transferId]?.accept?.complete(accept)
            }

            Actions.FILE_ACK -> {
                val ack = SwitchboardJson.decodeFromJsonElement(FileAck.serializer(), payload)
                live[ack.transferId]?.acked?.update { held -> maxOf(held, ack.received) }
            }

            Actions.FILE_COMPLETE -> {
                val complete = SwitchboardJson.decodeFromJsonElement(FileComplete.serializer(), payload)
                live[complete.transferId]?.done?.complete(complete)
            }

            Actions.FILE_CONTROL -> {
                val control = SwitchboardJson.decodeFromJsonElement(FileControl.serializer(), payload)
                control(control.transferId, control.action)
            }
        }
    }

    // ---- State bookkeeping ----

    private fun publish(progress: FileProgress) {
        _transfers.update { listOf(progress) + it.filterNot { old -> old.transferId == progress.transferId } }
    }

    private fun update(transferId: String, transform: (FileProgress) -> FileProgress) {
        _transfers.update { list ->
            list.map { if (it.transferId == transferId) transform(it) else it }
        }
    }

    private fun finish(transferId: String, status: String, error: String) {
        update(transferId) {
            it.copy(
                status = status,
                error = error,
                bytesPerSec = 0,
                finishedAt = System.currentTimeMillis()
            )
        }
    }

    private fun closeReceive(transferId: String, keepPart: Boolean) {
        val entry = live[transferId] ?: return
        runCatching { entry.sink?.close() }
        entry.sink = null
        if (!keepPart) entry.part?.delete()
    }

    // ---- Storage helpers ----

    private fun partDir(): File = File(context.cacheDir, "transfers").apply { mkdirs() }

    private fun openStream(uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri) ?: error("Cannot read the selected file")

    private fun mimeOf(uri: Uri): String =
        context.contentResolver.getType(uri) ?: "application/octet-stream"

    /**
     * Falls back to the URI's last path segment: a provider is allowed to omit
     * the display name, and an unnamed file on the desktop is worse than a
     * clumsy one.
     */
    private fun readMetadata(uri: Uri): Pair<String, Long> {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L
                if (!name.isNullOrEmpty()) return name to size
            }
        }
        return (uri.lastPathSegment ?: "file") to 0L
    }

    private fun reasonOf(failure: Throwable): String =
        failure.message?.takeIf { it.isNotBlank() } ?: failure::class.java.simpleName

    companion object {
        private const val TAG = "TransferEngine"

        /** Bytes allowed in flight ahead of the last ack. */
        private const val SEND_WINDOW = 8L * CHUNK_SIZE
        private const val ACK_TIMEOUT_MS = 30_000L
        private const val HANDSHAKE_TIMEOUT_MS = 60_000L
        private const val RATE_WINDOW_MS = 500L

        @Volatile
        private var instance: TransferEngine? = null

        fun get(context: Context): TransferEngine = instance ?: synchronized(this) {
            instance ?: TransferEngine(
                context.applicationContext,
                TransferPreferences(context.applicationContext)
            ).also { instance = it }
        }
    }
}

private fun skipFully(stream: InputStream, count: Long) {
    var remaining = count
    while (remaining > 0) {
        val skipped = stream.skip(remaining)
        // skip() may legally return 0 on a stream that is not at its end, so a
        // plain loop on skip() alone can spin; reading one byte forces progress.
        if (skipped <= 0) {
            if (stream.read() < 0) return
            remaining--
        } else {
            remaining -= skipped
        }
    }
}

/** [InputStream.read] may return a short read long before the end of the file. */
private fun readFully(stream: InputStream, buffer: ByteArray, want: Int): Int {
    var filled = 0
    while (filled < want) {
        val read = stream.read(buffer, filled, want - filled)
        if (read < 0) break
        filled += read
    }
    return filled
}
