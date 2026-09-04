package com.switchboard.app.transfer

import com.switchboard.app.net.CHUNK_SIZE
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

/** How the transfer rate is written out. Networks are quoted in bits, storage in bytes. */
enum class RateUnit(val label: String) {
    BYTES("MB/s"),
    BITS("Mb/s")
}

/**
 * The arithmetic behind a transfer, kept free of Android types so the offset
 * and digest rules can be tested on the JVM. Every value here decides where
 * bytes land on disk, so an off-by-one is silent corruption rather than a
 * crash.
 */
object TransferMath {

    /**
     * Bytes to read for the chunk starting at [offset]. The final chunk is
     * short, and an offset at or past the end yields zero so the sender's loop
     * terminates instead of emitting an empty frame forever.
     */
    fun chunkLength(size: Long, offset: Long, chunk: Int = CHUNK_SIZE): Int {
        if (offset < 0 || offset >= size) return 0
        return minOf(chunk.toLong(), size - offset).toInt()
    }

    fun chunkCount(size: Long, chunk: Int = CHUNK_SIZE): Long =
        if (size <= 0) 0 else (size + chunk - 1) / chunk

    /**
     * Where a resumed transfer restarts. A receiver that reports more bytes
     * than the file holds — a stale `.part` from a different file, or a host
     * that miscounted — would otherwise make the sender seek past the end and
     * ship a truncated file, so anything out of range restarts from zero.
     */
    fun resumeOffset(reported: Long, size: Long): Long =
        if (reported <= 0 || reported > size) 0 else reported

    /** Guards against the divide-by-zero on the first progress tick. */
    fun bytesPerSec(bytes: Long, elapsedMs: Long): Long =
        if (elapsedMs <= 0 || bytes <= 0) 0 else bytes * 1000 / elapsedMs

    /**
     * Hex SHA-256 read in fixed blocks: the file may be larger than the heap,
     * so it is never materialised.
     */
    fun sha256Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(CHUNK_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * An empty [expected] passes: the peer is allowed to omit the digest, and
     * refusing those transfers would break interop for no security gain — a
     * hash it never sent proves nothing either way.
     */
    fun digestMatches(expected: String, actual: String): Boolean =
        expected.isEmpty() || expected.equals(actual, ignoreCase = true)

    /** Decimal units throughout, so MB/s and Mb/s stay comparable. */
    fun formatRate(bytesPerSec: Long, unit: RateUnit): String {
        val value = when (unit) {
            RateUnit.BYTES -> bytesPerSec / 1_000_000.0
            RateUnit.BITS -> bytesPerSec * 8 / 1_000_000.0
        }
        return String.format(Locale.US, "%.1f %s", value, unit.label)
    }

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format(Locale.US, "%.1f KB", bytes / 1_000.0)
        else -> "$bytes B"
    }
}
