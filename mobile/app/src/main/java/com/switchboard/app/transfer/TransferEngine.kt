package com.switchboard.app.transfer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    private val queueWorkers = mutableListOf<Job>()

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
    ) {
        /**
         * Receive-side counters. Only the inbound coroutine touches these, so
         * they are plain fields: [acked] stays a flow because the *upload* path
         * has a pump waiting on it from another coroutine.
         */
        var ackedTo: Long = 0
        var emittedAt: Long = 0
        var rateMark: Long = 0
        var rateAt: Long = 0
        var bps: Long = 0
    }

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

    /**
     * Keeps [MAX_PARALLEL_UPLOADS] workers draining the queue.
     *
     * Sending was strictly one file at a time, which left the link idle through
     * every digest pass and every handshake round trip — picking a folder meant
     * watching files go one by one with a gap between each. Several workers on
     * one channel overlap that dead time. The count is fixed rather than tuned
     * from the observed rate: the window per transfer already adapts to a slow
     * link, and a controller that guesses at concurrency is a second thing to
     * get wrong.
     */
    private fun ensureQueueWorker() {
        synchronized(this) {
            queueWorkers.removeAll { !it.isActive }
            repeat(MAX_PARALLEL_UPLOADS - queueWorkers.size) {
                queueWorkers += scope.launch {
                    for (transferId in uploadQueue) {
                        val entry = live[transferId] ?: continue
                        if (entry.parked || entry.cancelled) continue
                        val uri = entry.uri ?: continue

                        update(transferId) { it.copy(status = TransferStatus.ACTIVE) }
                        // A child job rather than the worker itself: cancelling
                        // one file must free this worker for the next, not kill
                        // it and shrink the pool.
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
        val job = currentCoroutineContext()[Job]

        // The digest is computed before the offer so the receiver can verify
        // without a second pass over a file it may not be able to buffer.
        val sha = openStream(uri).use {
            TransferMath.sha256Hex(it) { job?.ensureActive() }
        }
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
            // Seeded at the start rather than zero: publishing after the first
            // chunk would measure a few hundred kilobytes over a few
            // milliseconds and open the row with an invented headline speed.
            var emittedAt = markerAt

            while (offset < size) {
                // Reading and sealing a chunk are both blocking and neither is
                // a suspension point, so without this a cancelled upload runs
                // to the end of the file before it notices.
                job?.ensureActive()
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

                // Publishing every chunk meant rebuilding and recomposing the
                // whole transfer list a hundred times a second on a fast link,
                // which cost more than the transfer it was reporting on and
                // made the list stutter under its own updates.
                val now = System.currentTimeMillis()
                if (now - emittedAt >= PROGRESS_INTERVAL_MS || offset >= size) {
                    entry.bps = TransferMath.smoothRate(
                        entry.bps,
                        TransferMath.bytesPerSec(offset - marker, now - markerAt)
                    )
                    marker = offset
                    markerAt = now
                    emittedAt = now
                    val rate = entry.bps
                    update(id) { it.copy(transferred = offset, bytesPerSec = rate) }
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

        val part = File(partDir(), "${offer.transferId}.part")
        // A part file left by an interrupted attempt is the resume point; the
        // peer is told how much we hold so it seeks instead of restarting.
        val held = TransferMath.resumeOffset(part.length(), offer.size)
        if (held != part.length()) part.delete()

        entry.part = part
        entry.sink = RandomAccessFile(part, "rw")
        entry.acked.value = held
        entry.ackedTo = held
        entry.rateMark = held
        entry.rateAt = System.currentTimeMillis()
        entry.emittedAt = entry.rateAt
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
        val last = chunk.last || received >= entry.offer.size

        // Confirming every chunk doubled the frame count for nothing: the host
        // runs a 2 MB window, so an ack every 512 KB keeps it saturated at a
        // quarter of the encryptions — and those acks share the socket with the
        // chunks they are pacing.
        if (last || received - entry.ackedTo >= ACK_INTERVAL) {
            entry.ackedTo = received
            entry.acked.value = received
            emit(Actions.FILE_ACK, FileAck(chunk.transferId, received), null)
        }

        publishReceiveProgress(entry, received, force = last)

        if (last) {
            // Verifying the digest and copying the file into the user's folder
            // are two more full passes over it. Done here they block the
            // inbound channel — where the *next* file's chunks are already
            // queued — so a batch arrived in bursts with a long stall at every
            // file boundary. The handle is closed first so the publish sees a
            // fully flushed file.
            live.remove(chunk.transferId)
            runCatching { sink.close() }
            entry.sink = null
            scope.launch { completeReceive(entry, emit) }
        }
    }

    /**
     * Reports a receive at most every [PROGRESS_INTERVAL_MS].
     *
     * The rate was previously the average since the transfer started, which
     * only ever drifts towards a number and never shows the link recovering or
     * stalling; this is a smoothed window, matching what the host reports for
     * the same file.
     */
    private fun publishReceiveProgress(entry: Live, received: Long, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - entry.emittedAt < PROGRESS_INTERVAL_MS) return
        entry.bps = TransferMath.smoothRate(
            entry.bps,
            TransferMath.bytesPerSec(received - entry.rateMark, now - entry.rateAt)
        )
        entry.rateMark = received
        entry.rateAt = now
        entry.emittedAt = now
        val rate = entry.bps
        update(entry.offer.transferId) { it.copy(transferred = received, bytesPerSec = rate) }
    }

    private fun completeReceive(entry: Live, emit: (String, WirePayload, ByteArray?) -> Unit) {
        val id = entry.offer.transferId
        val part = entry.part
        runCatching { entry.sink?.close() }
        entry.sink = null

        val actual = part?.inputStream()?.use { TransferMath.sha256Hex(it) }.orEmpty()
        if (!TransferMath.digestMatches(entry.offer.sha256, actual)) {
            part?.delete()
            emit(Actions.FILE_COMPLETE, FileComplete(id, false, actual, "checksum mismatch"), null)
            live.remove(id)
            finish(id, TransferStatus.FAILED, "The file arrived corrupted and was discarded.")
            return
        }

        val published = runCatching { publishReceivedFile(entry, part!!) }
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
     * Moves the verified file into storage: prefers the user's custom folder chosen in Settings,
     * but falls back seamlessly to Downloads/Switchboard if none is configured or SAF write fails.
     */
    private fun publishReceivedFile(entry: Live, part: File) {
        val customDir = prefs.saveDirectory
        if (customDir.isNotEmpty()) {
            val result = runCatching { publishToSaveDirectory(entry, part, customDir) }
            if (result.isSuccess) return
            Log.w(TAG, "Custom save directory failed ($customDir), falling back to Downloads: ${result.exceptionOrNull()?.message}")
        }
        val downloadsResult = runCatching { publishToDownloads(entry, part) }
        if (downloadsResult.isSuccess) return
        Log.w(TAG, "MediaStore downloads failed, falling back to app external files dir: ${downloadsResult.exceptionOrNull()?.message}")

        publishToAppExternalDir(entry, part)
    }

    private fun publishToSaveDirectory(entry: Live, part: File, treeUriStr: String) {
        val tree = Uri.parse(treeUriStr)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val fileName = sanitizeFileName(entry.offer.name)
        val target = DocumentsContract.createDocument(
            context.contentResolver,
            parent,
            entry.offer.mimeType.ifEmpty { "application/octet-stream" },
            fileName
        ) ?: error("Cannot write to the chosen save folder")

        context.contentResolver.openOutputStream(target)?.use { out ->
            part.inputStream().use { it.copyTo(out, CHUNK_SIZE) }
        } ?: error("Cannot write to the chosen save folder")
    }

    private fun publishToAppExternalDir(entry: Live, part: File) {
        val fileName = sanitizeFileName(entry.offer.name)
        val targetDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        targetDir.mkdirs()
        val target = File(targetDir, fileName)
        part.copyTo(target, overwrite = true)
    }

    private fun publishToDownloads(entry: Live, part: File) {
        val fileName = sanitizeFileName(entry.offer.name)
        val mimeType = entry.offer.mimeType.ifEmpty { "application/octet-stream" }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/Switchboard")
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Failed to create download record in MediaStore")
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    part.inputStream().use { it.copyTo(out, CHUNK_SIZE) }
                } ?: error("Failed to write download stream")
                values.clear()
                values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            } catch (e: Throwable) {
                context.contentResolver.delete(uri, null, null)
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val baseDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            val switchboardDir = File(baseDir, "Switchboard").apply { mkdirs() }
            val target = File(switchboardDir, fileName)
            part.copyTo(target, overwrite = true)
        }
    }

    // ---- Control ----

    fun control(transferId: String, action: String) {
        val entry = live[transferId]
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
                live.remove(transferId)
                finish(transferId, TransferStatus.CANCELLED, "")
            }
        }
        // Sealing a frame contends with a pump that is holding the socket lock
        // for every chunk it sends, and deleting a half-received file is disk
        // work. This is called straight off a tap, so neither belongs on the
        // main thread — the row above has already moved.
        scope.launch {
            sender?.invoke(Actions.FILE_CONTROL, FileControl(transferId, action), null)
            if (action == Control.CANCEL) closeReceive(entry, keepPart = false)
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

    private fun closeReceive(transferId: String, keepPart: Boolean) =
        closeReceive(live[transferId], keepPart)

    /** Takes the entry directly, for callers that have already unlisted it. */
    private fun closeReceive(entry: Live?, keepPart: Boolean) {
        if (entry == null) return
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

        /** Files streaming out at once. Matches the cap the host applies to its own sends. */
        private const val MAX_PARALLEL_UPLOADS = 4

        /** Bytes received before the peer is confirmed. Must stay under [SEND_WINDOW]. */
        private const val ACK_INTERVAL = 512L * 1024

        /** Floor on how often a transfer republishes itself to the UI. */
        private const val PROGRESS_INTERVAL_MS = 250L

        @Volatile
        private var instance: TransferEngine? = null

        fun get(context: Context): TransferEngine = instance ?: synchronized(this) {
            instance ?: TransferEngine(
                context.applicationContext,
                TransferPreferences.get(context.applicationContext)
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
