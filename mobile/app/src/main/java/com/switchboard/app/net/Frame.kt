package com.switchboard.app.net

import kotlinx.serialization.json.JsonElement

/**
 * Frame codec, mirroring `backend/internal/protocol/frame.go` byte for byte.
 *
 * Bulk payloads — file chunks, camera frames — travel *beside* the JSON
 * envelope rather than base64 inside it. The sealed frame is written as a
 * WebSocket binary message either way, so encoding them would inflate every
 * byte by a third and buy an encode on this device's CPU and a decode on the
 * desktop's to arrive at the same place.
 *
 * Layout:
 * ```
 * 0x00 | envelope JSON                                  (control frame)
 * 0x01 | uint32 big-endian metaLen | envelope JSON | raw bytes
 * ```
 */
object Frame {
    const val KIND_JSON: Byte = 0x00
    const val KIND_BLOB: Byte = 0x01

    /** One decoded frame: the envelope, and the bytes that rode with it. */
    data class Decoded(val envelope: Envelope, val blob: ByteArray?) {
        // Generated equals/hashCode would compare the ByteArray by identity,
        // which is a trap rather than a feature. Nothing compares these.
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    fun encode(envelope: Envelope, blob: ByteArray? = null): ByteArray {
        val meta = SwitchboardJson
            .encodeToString(Envelope.serializer(), envelope)
            .encodeToByteArray()

        if (blob == null || blob.isEmpty()) {
            return ByteArray(1 + meta.size).also {
                it[0] = KIND_JSON
                meta.copyInto(it, 1)
            }
        }

        return ByteArray(5 + meta.size + blob.size).also {
            it[0] = KIND_BLOB
            it[1] = (meta.size ushr 24).toByte()
            it[2] = (meta.size ushr 16).toByte()
            it[3] = (meta.size ushr 8).toByte()
            it[4] = meta.size.toByte()
            meta.copyInto(it, 5)
            blob.copyInto(it, 5 + meta.size)
        }
    }

    /** Throws on a malformed frame; the caller drops it. */
    fun decode(frame: ByteArray): Decoded {
        require(frame.isNotEmpty()) { "empty frame" }

        return when (frame[0]) {
            KIND_JSON -> Decoded(parse(frame, 1, frame.size), null)

            KIND_BLOB -> {
                require(frame.size >= 5) { "truncated blob header" }
                // Read as a Long: a hostile length with the high bit set would
                // otherwise arrive as a negative Int and slip past the bounds
                // check that is supposed to catch it.
                val metaLen = ((frame[1].toLong() and 0xFF) shl 24) or
                    ((frame[2].toLong() and 0xFF) shl 16) or
                    ((frame[3].toLong() and 0xFF) shl 8) or
                    (frame[4].toLong() and 0xFF)
                require(5 + metaLen <= frame.size) { "blob metadata overruns the frame" }
                val end = (5 + metaLen).toInt()
                Decoded(parse(frame, 5, end), frame.copyOfRange(end, frame.size))
            }

            else -> throw IllegalArgumentException("unknown frame kind ${frame[0]}")
        }
    }

    private fun parse(frame: ByteArray, from: Int, to: Int): Envelope =
        SwitchboardJson.decodeFromString(
            Envelope.serializer(),
            frame.decodeToString(from, to)
        )
}

/** A control frame's payload, or null when it carried none. */
val Frame.Decoded.payload: JsonElement? get() = envelope.payload
