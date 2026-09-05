package com.switchboard.app.transfer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
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
    /** One inbound frame: its action, its metadata, and a chunk's raw bytes. */
    private data class Inbound(val action: String, val payload: JsonElement, val blob: ByteArray?)

    private val inbound = Channel<Inbound>(Channel.UNLIMITED)
    private val uploadQueue = Channel<String>(Channel.UNLIMITED)
    private var queueWorkerJob: Job? = null

    // Concurrent because bind/unbind arrive on the connection collector while
    // an upload coroutine is still touching its own entry.
    private val live = java.util.concurrent.ConcurrentHashMap<String, Live>()

    /** Set while a session is up. Cleared on disconnect, which fails everything in flight. */
    @Volatile
    private var sender: ((String, WirePayload, ByteArray?) -> Unit)? = null

    private class Live(
        val offer: FileOffer,
        /** The source document, for uploads. Null on the receiving side. */
        val uri: Uri? = null,
        /** Highest byte the peer has confirmed; the send window is measured against it. */
        val acked: MutableStateFlow<Long> = MutableStateFlow(0L),
        val paused: MutableStateFlow<Boolean> = MutableStateFlow(false),
        // Re-created on a resume: a CompletableDeferred fires once, and a
        // reconnected transfer needs a fresh handshake of its own.
        var accept: CompletableDeferred<FileAccept> = CompletableDeferred(),
        var done: CompletableDeferred<FileComplete> = CompletableDeferred(),
        var job: Job? = null,
        var part: File? = null,
        var sink: RandomAccessFile? = null,
        /** Set while the socket is down and this transfer is waiting it out. */
        var parked: Boolean = false,
        var cancelled: Boolean = false,
        var startedAt: Long = System.currentTimeMillis()
    )

    init {
        scope.launch {
            for (frame in inbound) {
                runCatching { handle(frame.action, frame.payload, frame.blob) }
                    .onFailure { Log.w(TAG, "dropping ${frame.action}: ${it.message}") }
            }
        }
    }

    // ---- Session wiring ----

    fun bind(send: (String, WirePayload, ByteArray?) -> Unit) {
        sender = send
        resumeParked()
    }

    /**
     * A dropped socket is not a failed transfer. Everything in flight is
     * parked with its bytes intact — the `.part` file on the receiving side,
     * the source document on the sending side — so reconnecting picks the file
     * up where it stopped instead of starting it again.
     *
     * The pumps are cancelled either way: they would otherwise spin against a
     * socket that will never acknowledge them.
     */
    fun unbind() {
        sender = null
        live.values.toList().forEach { entry ->
            val id = entry.offer.transferId
            entry.parked = true
            entry.job?.cancel()
            entry.job = null
            closeReceive(id, keepPart = true)
            update(id) {
                it.copy(status = TransferStatus.PAUSED, error = "Waiting to reconnect", bytesPerSec = 0)
            }
        }
    }

    /**
     * Restarts what the last disconnect parked.
     *
     * Only uploads are restarted here. A parked download needs nothing: the
     * desktop re-offers it, and [onOffer] resumes onto the `.part` file that
     * was deliberately kept — the same path a manually resumed transfer takes,
     * rather than a second one written for reconnects.
     */
    private fun resumeParked() {
        live.values.toList().filter { it.parked }.forEach { entry ->
            entry.parked = false
            val uri = entry.uri ?: return@forEach
            val id = entry.offer.transferId

            entry.accept = CompletableDeferred()
            entry.done = CompletableDeferred()
            update(id) { it.copy(status = TransferStatus.PENDING, error = "") }
            TransferService.start(context)
            uploadQueue.trySend(id)
        }
        ensureQueueWorker()
    }

    fun onFrame(action: String, payload: JsonElement, blob: ByteArray? = null) {
        inbound.trySend(Inbound(action, payload, blob))
    }

    // ---- Sending ----

    private fun ensureQueueWorker() {
        synchronized(this) {
            if (queueWorkerJob?.isActive == true) return
            queueWorkerJob = scope.launch {
                for (transferId in uploadQueue) {
                    val entry = live[transferId] ?: continue
                    if (entry.parked || entry.cancelled) continue
                    val uri = entry.uri ?: continue

                    update(transferId) { it.copy(status = TransferStatus.ACTIVE) }
                    val currentJob = launch {
                        runCatching { upload(uri, entry) }
                            .onFailure { failure ->
                                if (failure is kotlinx.coroutines.CancellationException) throw failure
                                finish(transferId, TransferStatus.FAILED, reasonOf(failure))
                            }
                        live.remove(transferId)
                    }
                    entry.job = currentJob
                    currentJob.join()
                }
            }
        }
    }

    /** Starts an upload for a document the user picked through the SAF. */
    fun send(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val transferId = UUID.randomUUID().toString()
        val (name, size) = readMetadata(uri)

        if (sender == null) {
            publish(FileProgress(transferId, name, Direction.UPLOAD, TransferStatus.FAILED, 0, size, error = "Not connected"))
            return
        }

        val offer = FileOffer(transferId, name, size, mimeOf(uri), "", Direction.UPLOAD)
        val entry = Live(offer, uri = uri)
        live[transferId] = entry
        TransferService.start(context)
        publish(FileProgress(transferId, name, Direction.UPLOAD, TransferStatus.PENDING, 0, size, startedAt = entry.startedAt))

        uploadQueue.trySend(transferId)
        ensureQueueWorker()
    }

    /** Queues multiple documents for upload. */
    fun sendAll(uris: List<Uri>) {
        uris.forEach { send(it) }
    }

    /** Recursively collects all file document URIs within a SAF tree URI. */
    fun collectFolderUris(treeUri: Uri): List<Uri> {
        val uris = mutableListOf<Uri>()
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return emptyList()

        fun traverse(parentDocId: String) {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )
            runCatching {
                context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    while (cursor.moveToNext()) {
                        val childId = cursor.getString(idIndex)
                        val mime = cursor.getString(mimeIndex)
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            traverse(childId)
                        } else {
                            uris.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, childId))
                        }
                    }
                }
            }
        }

        traverse(docId)
        return uris
    }

    /** Scans a picked folder and queues all contained files for upload. */
    fun sendFolder(treeUri: Uri): Int {
        runCatching {
            context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val uris = collectFolderUris(treeUri)
        sendAll(uris)
        return uris.size
    }

    private suspend fun upload(uri: Uri, entry: Live) {
        val id = entry.offer.transferId
        val emit = sender ?: error("Not connected")

        // The digest is computed before the offer so the receiver can verify
        // without a second pass over a file it may not be able to buffer.
        val sha = openStream(uri).use { TransferMath.sha256Hex(it) }
        emit(Actions.FILE_OFFER, entry.offer.copy(sha256 = sha), null)

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
                    FileChunk(transferId = id, offset = offset, last = offset + read >= size),
                    // Copied because the read buffer is reused on the next
                    // pass, and the frame is not serialised until the socket
                    // gets to it.
                    buffer.copyOf(read)
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
        // A re-offer of a transfer already known is the desktop resuming after
        // a dropped socket. The old handle goes; its .part file stays, and is
        // exactly what the offset below is read from.
        closeReceive(offer.transferId, keepPart = true)
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
            emit(
                Actions.FILE_ACCEPT,
                FileAccept(offer.transferId, 0, false, "No save folder chosen on the phone"),
                null
            )
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
        emit(Actions.FILE_ACCEPT, FileAccept(offer.transferId, held), null)
    }

    private fun onChunk(chunk: FileChunk, bytes: ByteArray) {
        val entry = live[chunk.transferId] ?: return
        val sink = entry.sink ?: return
        val emit = sender ?: return

        // The peer's offset is authoritative rather than a running local count,
        // so a resumed or re-ordered stream still lands in the right place.
        sink.seek(chunk.offset)
        sink.write(bytes)

        val received = chunk.offset + bytes.size
        entry.acked.value = received
        emit(Actions.FILE_ACK, FileAck(chunk.transferId, received), null)

        val elapsed = System.currentTimeMillis() - entry.startedAt
        update(chunk.transferId) {
            it.copy(transferred = received, bytesPerSec = TransferMath.bytesPerSec(received, elapsed))
        }

        if (chunk.last || received >= entry.offer.size) {
            completeReceive(entry, emit)
        }
    }

    private fun completeReceive(entry: Live, emit: (String, WirePayload, ByteArray?) -> Unit) {
        val id = entry.offer.transferId
        val part = entry.part
        entry.sink?.close()
        entry.sink = null

        val actual = part?.inputStream()?.use { TransferMath.sha256Hex(it) }.orEmpty()
        if (!TransferMath.digestMatches(entry.offer.sha256, actual)) {
            part?.delete()
            emit(Actions.FILE_COMPLETE, FileComplete(id, false, actual, "checksum mismatch"), null)
            live.remove(id)
            finish(id, TransferStatus.FAILED, "The file arrived corrupted and was discarded.")
            return
        }

        val published = runCatching { publishToSaveDirectory(entry, part!!) }
        part?.delete()
        live.remove(id)

        published.fold(
            onSuccess = {
                emit(Actions.FILE_COMPLETE, FileComplete(id, true, actual), null)
                finish(id, TransferStatus.COMPLETED, "")
            },
            onFailure = {
                emit(Actions.FILE_COMPLETE, FileComplete(id, false, actual, reasonOf(it)), null)
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
        sender?.invoke(Actions.FILE_CONTROL, FileControl(transferId, action), null)
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
                entry?.cancelled = true
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

    private fun handle(action: String, payload: JsonElement, blob: ByteArray?) {
        when (action) {
            Actions.FILE_OFFER ->
                onOffer(SwitchboardJson.decodeFromJsonElement(FileOffer.serializer(), payload))

            Actions.FILE_CHUNK -> onChunk(
                SwitchboardJson.decodeFromJsonElement(FileChunk.serializer(), payload),
                blob ?: ByteArray(0)
            )

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

    private fun sanitizeFileName(raw: String): String {
        var name = raw.substringAfterLast('/').substringAfterLast(':').trim()
        name = name.replace(Regex("""[/\\:*?"<>|\x00-\x1F]"""), "-")
        name = name.trim('.', ' ')
        return name.ifEmpty { "file_${System.currentTimeMillis()}" }
    }

    /**
     * Extracts a safe display name and accurate file size.
     * Falls back to openFileDescriptor when OpenableColumns.SIZE is missing or 0,
     * and sanitizes path prefixes and illegal filesystem characters.
     */
    private fun readMetadata(uri: Uri): Pair<String, Long> {
        var rawName: String? = null
        var size: Long = 0L

        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) rawName = cursor.getString(nameIndex)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }

        if (rawName.isNullOrEmpty()) {
            rawName = uri.lastPathSegment ?: "file"
        }

        if (size <= 0L) {
            runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    size = pfd.statSize
                }
            }
        }

        val name = sanitizeFileName(rawName)
        return name to maxOf(0L, size)
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
