package com.aiyu.switchboard.net

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.aiyu.switchboard.data.B64
import com.aiyu.switchboard.data.Vault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

data class Transfer(
    val id: String,
    val hostId: String,
    val name: String,
    val upload: Boolean,
    val done: Long,
    val total: Long,
    val state: State = State.Running,
    val error: String? = null,
) {
    /** Queued waits its turn; Paused waits for the connection to come back. */
    enum class State { Queued, Running, Paused, Done, Failed }

    val fraction get() = if (total <= 0) 1f else (done.toFloat() / total).coerceIn(0f, 1f)
    val busy get() = state == State.Queued || state == State.Running || state == State.Paused
}

/** What the computer answers while its user has not yet accepted the file. */
private const val WAITING = "waiting for approval"
private const val APPROVAL_WAIT = 125_000L

/** The connection dropped or never answered: worth waiting for, not a refusal. */
fun Event.Result.lost() = !ok && (error == "Not connected" || error == "Disconnected" || error?.endsWith("didn't answer") == true)

/**
 * Files in flight, both ways. They move one chunk at a time over the
 * connection. A transfer that loses it waits for it to come back and carries
 * on from the byte it reached; a foreground service keeps the app alive
 * meanwhile.
 */
class Transfers(private val context: Context, private val scope: CoroutineScope, private val vault: Vault) {
    private val _items = MutableStateFlow<List<Transfer>>(emptyList())
    val items: StateFlow<List<Transfer>> = _items.asStateFlow()

    /** True while anything is moving or waiting; keeps connections and the service up. */
    val busy: StateFlow<Boolean> = items.map { l -> l.any { it.busy } }.stateIn(scope, SharingStarted.Eagerly, false)

    /** The desktop keeps a few uploads open per phone; the rest queue here. */
    private val slots = Semaphore(2)

    init {
        scope.launch {
            items.collect { l ->
                val live = l.filter { it.busy }
                if (live.isEmpty()) TransferService.stop(context) else TransferService.show(context, live)
            }
        }
    }

    private fun add(t: Transfer) = _items.update { (listOf(t) + it).take(60) }

    private fun change(id: String, f: (Transfer) -> Transfer) = _items.update { l -> l.map { if (it.id == id) f(it) else it } }

    fun clearFinished() = _items.update { l -> l.filter { it.busy } }

    /** How long a paused transfer waits for the connection before it gives up. */
    private suspend fun awaitOnline(conn: HostConnection, id: String): Boolean {
        change(id) { if (it.state == Transfer.State.Running) it.copy(state = Transfer.State.Paused) else it }
        val back = withTimeoutOrNull(10 * 60_000L) { conn.link.first { it is Link.Online } } != null
        if (back) change(id) { it.copy(state = Transfer.State.Running) }
        return back
    }

    /** True if online now, or once the connection returns. */
    private suspend fun awaitOnlineIfNeeded(conn: HostConnection, id: String): Boolean =
        if (conn.link.value is Link.Online) true else awaitOnline(conn, id)

    // --- Upload -------------------------------------------------------------------

    /** Sends one file to the desktop. [path] keeps its folder (`Trip/day1/a.jpg`) on the other side. */
    fun upload(conn: HostConnection, hostId: String, uri: Uri, path: String? = null) {
        scope.launch {
            val (display, size) = describe(uri)
            val name = path ?: display
            val xfer = UUID.randomUUID().toString()
            add(Transfer(xfer, hostId, display, true, 0, size ?: 0, Transfer.State.Queued))
            val result = runCatching {
                if (size == null) error("Can't read this file's size")
                slots.withPermit {
                    change(xfer) { it.copy(state = Transfer.State.Running) }
                    sendFile(conn, xfer, uri, name, size) { sent -> change(xfer) { t -> t.copy(done = sent) } }
                }
            }
            finish(conn, xfer, result)
        }
    }

    /** A link that is up but keeps timing out would otherwise loop forever. */
    private fun giveUpAfter(misses: Int) {
        if (misses > 20) error("The computer stopped answering")
    }

    private suspend fun sendFile(conn: HostConnection, xfer: String, uri: Uri, name: String, size: Long, progress: (Long) -> Unit) {
        var misses = 0
        val askedAt = System.currentTimeMillis()
        while (true) {
            giveUpAfter(misses)
            if (!awaitOnlineIfNeeded(conn, xfer)) error("Not connected to the computer")
            // The desktop says how much it already has, so a retry carries on instead of starting over.
            val start = conn.send(Cmd.fileSend(xfer, name, size))
            if (start.lost()) {
                misses++
                continue
            }
            // The computer asks its user first; keep asking, for as long as it keeps the request open.
            if (start.error?.contains(WAITING) == true) {
                if (System.currentTimeMillis() - askedAt > APPROVAL_WAIT) error("Not accepted on the computer in time")
                change(xfer) { it.copy(state = Transfer.State.Paused) }
                delay(1_000)
                continue
            }
            step(start)
            change(xfer) { if (it.state == Transfer.State.Paused) it.copy(state = Transfer.State.Running) else it }
            var sent = start.data?.toLongOrNull() ?: 0L
            val input = context.contentResolver.openInputStream(uri) ?: error("Can't open this file")
            val dropped = input.use {
                skip(it, sent)
                progress(sent)
                val buf = ByteArray(FILE_CHUNK)
                var lost = false
                while (true) {
                    val n = fill(it, buf)
                    if (n == 0) break
                    val r = conn.send(Cmd.fileChunk(xfer, sent, B64.encode(buf.copyOf(n))))
                    if (r.lost()) {
                        lost = true
                        break
                    }
                    step(r)
                    misses = 0
                    sent += n
                    progress(sent)
                }
                lost
            }
            if (dropped) {
                misses++
                continue
            }
            if (sent != size) error("The file changed while sending")
            val done = conn.send(Cmd.fileDone(xfer))
            if (done.lost()) {
                misses++
                continue
            }
            // "unknown transfer" after a retry means the first Done got through.
            if (!done.ok && done.error?.contains("unknown transfer") != true) step(done)
            return
        }
    }

    private fun skip(input: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val s = input.skip(left)
            if (s <= 0) error("The file changed while sending")
            left -= s
        }
    }

    /** Reads until the buffer is full or the stream ends, so every chunk but the last is full-sized. */
    private fun fill(input: InputStream, buf: ByteArray): Int {
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r <= 0) break
            n += r
        }
        return n
    }

    /** Sends every file under a folder the user picked, keeping the folder structure. */
    fun uploadFolder(conn: HostConnection, hostId: String, tree: Uri) {
        scope.launch {
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            val rootName = describe(DocumentsContract.buildDocumentUriUsingTree(tree, rootId)).first
            walk(tree, rootId, rootName).forEach { (uri, path) -> upload(conn, hostId, uri, path) }
        }
    }

    private fun walk(tree: Uri, parentId: String, prefix: String, depth: Int = 0): List<Pair<Uri, String>> {
        if (depth > 6) return emptyList()
        val out = mutableListOf<Pair<Uri, String>>()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        context.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val name = c.getString(1) ?: continue
                if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    out += walk(tree, id, "$prefix/$name", depth + 1)
                } else {
                    out += DocumentsContract.buildDocumentUriUsingTree(tree, id) to "$prefix/$name"
                }
            }
        }
        return out
    }

    // --- Download -----------------------------------------------------------------

    /** Pulls a file the desktop offered into the chosen folder, or Downloads/Switchboard. */
    fun download(conn: HostConnection, hostId: String, offer: Event.FileOffer) {
        conn.dismissOffer(offer.xfer)
        OfferNotifier.cancel(context, offer.xfer)
        // The same offer can arrive again after a reconnect; one transfer per offer.
        if (_items.value.any { it.id == offer.xfer && it.busy }) return
        scope.launch {
            add(Transfer(offer.xfer, hostId, offer.name, false, 0, offer.size))
            var sink: Sink? = null
            val result = runCatching {
                val s = openSink(offer.name).also { sink = it }
                var offset = 0L
                var misses = 0
                while (offset < offer.size) {
                    giveUpAfter(misses)
                    if (!awaitOnlineIfNeeded(conn, offer.xfer)) error("Not connected to the computer")
                    val r = conn.send(Cmd.fileRead(offer.xfer, offset, FILE_CHUNK))
                    if (r.lost()) {
                        misses++
                        continue
                    }
                    misses = 0
                    step(r)
                    val bytes = B64.decode(r.data.orEmpty()) ?: error("The computer sent bad data")
                    if (bytes.isEmpty()) error("The computer stopped sending")
                    s.write(bytes)
                    offset += bytes.size
                    change(offer.xfer) { it.copy(done = offset) }
                }
                s.commit()
                while (awaitOnlineIfNeeded(conn, offer.xfer)) {
                    if (!conn.send(Cmd.fileDone(offer.xfer)).lost()) break
                }
            }
            if (result.isFailure) sink?.discard()
            finish(conn, offer.xfer, result)
        }
    }

    /** Turns the offer down; the desktop is told so it can stop waiting. */
    fun decline(conn: HostConnection, offer: Event.FileOffer) {
        conn.dismissOffer(offer.xfer)
        OfferNotifier.cancel(context, offer.xfer)
        conn.post(Cmd.fileCancel(offer.xfer))
    }

    private suspend fun finish(conn: HostConnection, xfer: String, result: Result<Unit>) {
        result.fold(
            onSuccess = { change(xfer) { it.copy(done = it.total, state = Transfer.State.Done) } },
            onFailure = { e ->
                conn.send(Cmd.fileCancel(xfer))
                change(xfer) { it.copy(state = Transfer.State.Failed, error = e.message ?: "Failed") }
            },
        )
    }

    private fun step(r: Event.Result) {
        if (!r.ok) error(r.error ?: "The computer refused")
    }

    private fun describe(uri: Uri): Pair<String, Long?> {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0)?.takeIf { it.isNotBlank() } ?: "file"
                return name to (if (c.isNull(1)) null else c.getLong(1))
            }
        }
        return (uri.lastPathSegment ?: "file") to null
    }

    private interface Sink {
        fun write(b: ByteArray)
        fun commit()
        fun discard()
    }

    /** The folder the user chose, else Downloads/Switchboard through MediaStore on Android 10+, which needs no permission. */
    private fun openSink(name: String): Sink {
        val safe = name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "file" }
        vault.data.value.saveFolder?.let { return openFolderSink(Uri.parse(it), safe) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safe)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Switchboard")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Can't create the file in Downloads")
            val out: OutputStream = resolver.openOutputStream(uri) ?: error("Can't write to Downloads")
            return object : Sink {
                override fun write(b: ByteArray) = out.write(b)
                override fun commit() {
                    out.close()
                    resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                }
                override fun discard() {
                    runCatching { out.close() }
                    resolver.delete(uri, null, null)
                }
            }
        }
        // Before Android 10 there is no permission-free Downloads: use the app's own folder.
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Switchboard").apply { mkdirs() }
        var file = File(dir, safe)
        var n = 1
        while (file.exists()) file = File(dir, "${safe.substringBeforeLast('.')} (${n++}).${safe.substringAfterLast('.', "")}".trimEnd('.'))
        val out = file.outputStream()
        return object : Sink {
            override fun write(b: ByteArray) = out.write(b)
            override fun commit() = out.close()
            override fun discard() {
                runCatching { out.close() }
                file.delete()
            }
        }
    }

    /** A folder picked with the system folder chooser; the provider renames on a clash. */
    private fun openFolderSink(tree: Uri, name: String): Sink {
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
        val doc = runCatching { DocumentsContract.createDocument(resolver, parent, mime, name) }.getOrNull()
            ?: error("Can't save to the folder you chose. Choose it again in Files.")
        val out = resolver.openOutputStream(doc) ?: error("Can't write to the folder you chose")
        return object : Sink {
            override fun write(b: ByteArray) = out.write(b)
            override fun commit() = out.close()
            override fun discard() {
                runCatching { out.close() }
                runCatching { DocumentsContract.deleteDocument(resolver, doc) }
            }
        }
    }
}
