package com.switchboard.app.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frame codec has to agree with `backend/internal/protocol/frame.go` byte
 * for byte, so these pin the layout rather than only the round trip. A codec
 * that is merely self-consistent talks to nothing.
 */
class FrameTest {

    private fun envelope(action: String) = Envelope(
        id = "req-1",
        type = "command",
        action = action,
        payload = null,
        timestamp = 1_700_000_000_000
    )

    @Test
    fun `a control frame is a kind byte followed by the envelope JSON`() {
        val frame = Frame.encode(envelope(Actions.HOST_STATE))

        assertEquals(Frame.KIND_JSON, frame[0])
        assertTrue(
            "the envelope must follow the kind byte verbatim",
            frame.decodeToString(1, frame.size).startsWith("{")
        )

        val decoded = Frame.decode(frame)
        assertEquals(Actions.HOST_STATE, decoded.envelope.action)
        assertNull("a control frame carries no blob", decoded.blob)
    }

    @Test
    fun `a blob frame carries a big-endian length then the envelope then the bytes`() {
        // Every byte value, so a codec that mangles high bytes or treats the
        // payload as text is caught rather than passing on ASCII alone.
        val payload = ByteArray(256) { it.toByte() }
        val frame = Frame.encode(envelope(Actions.FILE_CHUNK), payload)

        assertEquals(Frame.KIND_BLOB, frame[0])
        val metaLen = ((frame[1].toInt() and 0xFF) shl 24) or
            ((frame[2].toInt() and 0xFF) shl 16) or
            ((frame[3].toInt() and 0xFF) shl 8) or
            (frame[4].toInt() and 0xFF)
        assertEquals(
            "the frame must be exactly header + envelope + payload",
            5 + metaLen + payload.size,
            frame.size
        )

        val decoded = Frame.decode(frame)
        assertEquals(Actions.FILE_CHUNK, decoded.envelope.action)
        assertArrayEquals("the payload did not survive the round trip", payload, decoded.blob)
    }

    /**
     * The whole reason bytes moved out of the JSON: base64 inside the envelope
     * costs a third of the wire for nothing, since the frame is binary anyway.
     */
    @Test
    fun `a binary payload costs no expansion`() {
        val payload = ByteArray(64 * 1024)
        val frame = Frame.encode(envelope(Actions.FILE_CHUNK), payload)
        assertTrue(
            "a 64 KiB chunk carried ${frame.size - payload.size} bytes of overhead",
            frame.size - payload.size < 256
        )
    }

    @Test
    fun `malformed frames are rejected rather than half-read`() {
        val hostile = listOf(
            ByteArray(0),
            byteArrayOf(Frame.KIND_BLOB, 0, 0),
            byteArrayOf(0x7f, 'x'.code.toByte()),
            // A length that overruns the buffer: the one input that turns a
            // slice into a crash if it is not bounds-checked.
            byteArrayOf(Frame.KIND_BLOB, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), '{'.code.toByte()),
            // The high bit set, which read as a signed Int would come back
            // negative and slip straight past that same check.
            byteArrayOf(Frame.KIND_BLOB, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), '{'.code.toByte())
        )
        hostile.forEach { frame ->
            runCatching { Frame.decode(frame) }
                .onSuccess { error("a malformed frame of ${frame.size} bytes was accepted") }
        }
    }
}
