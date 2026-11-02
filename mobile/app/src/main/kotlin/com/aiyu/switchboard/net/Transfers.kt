package com.aiyu.switchboard.net

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.aiyu.switchboard.data.B64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    enum class State { Running, Done, Failed }

    val fraction get() = if (total <= 0) 1f else (done.toFloat() / total).coerceIn(0f, 1f)
}

/**
 * Files in flight, both ways. They move one chunk at a time over the
 * connection, so they only run while the app is open and connected.
 */
class Transfers(private val context: Context, private val scope: CoroutineScope) {
    private val _items = MutableStateFlow<List<Transfer>>(emptyList())
    val items: StateFlow<List<Transfer>> = _items.asStateFlow()

    private fun add(t: Transfer) = _items.update { (listOf(t) + it).take(30) }

    private fun change(id: String, f: (Transfer) -> Transfer) = _items.update { l -> l.map { if (it.id == id) f(it) else it } }

    fun clearFinished() = _items.update { l -> l.filter { it.state == Transfer.State.Running } }

    /** Sends one file the user picked to the desktop. */
    fun upload(conn: HostConnection, hostId: String, uri: Uri) {
        scope.launch {
            val (name, size) = describe(uri)
            val xfer = UUID.randomUUID().toString()
            add(Transfer(xfer, hostId, name, true, 0, size ?: 0))
            runCatching {
                if (size == null) error("Can't read this file's size")
                step(conn.send(Cmd.fileSend(xfer, name, size)))
                val input = context.contentResolver.openInputStream(uri) ?: error("Can't open this file")
                input.use { sendAll(conn, xfer, it, size) { sent -> change(xfer) { t -> t.copy(done = sent) } } }
                step(conn.send(Cmd.fileDone(xfer)))
            }.fold(
                onSuccess = { change(xfer) { it.copy(done = it.total, state = Transfer.State.Done) } },
                onFailure = { e ->
                    conn.send(Cmd.fileCancel(xfer))
                    change(xfer) { it.copy(state = Transfer.State.Failed, error = e.message ?: "Failed") }
                },
            )
        }
    }

    private suspend fun sendAll(conn: HostConnection, xfer: String, input: InputStream, size: Long, progress: (Long) -> Unit) {
        val buf = ByteArray(FILE_CHUNK)
        var sent = 0L
        while (true) {
            val n = fill(input, buf)
            if (n == 0) break
            step(conn.send(Cmd.fileChunk(xfer, sent, B64.encode(buf.copyOf(n)))))
            sent += n
            progress(sent)
        }
        if (sent != size) error("The file changed while sending")
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

    /** Pulls a file the desktop offered into the Downloads folder. */
    fun download(conn: HostConnection, hostId: String, offer: Event.FileOffer) {
        conn.dismissOffer(offer.xfer)
        scope.launch {
            add(Transfer(offer.xfer, hostId, offer.name, false, 0, offer.size))
            var sink: Sink? = null
            runCatching {
                val s = openSink(offer.name).also { sink = it }
                var offset = 0L
                while (offset < offer.size) {
                    val r = conn.send(Cmd.fileRead(offer.xfer, offset, FILE_CHUNK))
                    step(r)
                    val bytes = B64.decode(r.data.orEmpty()) ?: error("The computer sent bad data")
                    if (bytes.isEmpty()) error("The computer stopped sending")
                    s.write(bytes)
                    offset += bytes.size
                    change(offer.xfer) { it.copy(done = offset) }
                }
                s.commit()
                step(conn.send(Cmd.fileDone(offer.xfer)))
            }.fold(
                onSuccess = { change(offer.xfer) { it.copy(done = it.total, state = Transfer.State.Done) } },
                onFailure = { e ->
                    sink?.discard()
                    conn.send(Cmd.fileCancel(offer.xfer))
                    change(offer.xfer) { it.copy(state = Transfer.State.Failed, error = e.message ?: "Failed") }
                },
            )
        }
    }

    /** Turns the offer down; the desktop is told so it can stop waiting. */
    fun decline(conn: HostConnection, offer: Event.FileOffer) {
        conn.dismissOffer(offer.xfer)
        conn.post(Cmd.fileCancel(offer.xfer))
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

    /** Downloads/Switchboard through MediaStore on Android 10+, which needs no permission. */
    private fun openSink(name: String): Sink {
        val safe = name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "file" }
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
}
