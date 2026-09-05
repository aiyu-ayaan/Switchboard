package com.switchboard.app.net

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The host reads these payloads by field name, so a rename on either side is a
 * silent no-op rather than a failure. These pin the wire shape.
 *
 * The related hazard — a payload type the client can construct but its encoder
 * does not know — is handled by the compiler instead: [WirePayload] is sealed
 * and `SwitchboardClient.encodePayload` is exhaustive over it, so a missing
 * branch fails the build. That gap is what crashed the app when
 * `audio.output.set` shipped without one.
 */
class WirePayloadTest {

    @Test
    fun outputSet_matchesTheHostFieldName() {
        assertEquals(
            """{"deviceId":"{0.0.0.00000000}.{87039890}"}""",
            SwitchboardJson.encodeToString(OutputSet("{0.0.0.00000000}.{87039890}"))
        )
    }

    @Test
    fun mixerSet_matchesTheHostFieldNames() {
        assertEquals(
            """{"sessionId":"chrome","level":80,"muted":false}""",
            SwitchboardJson.encodeToString(MixerSet("chrome", 80, false))
        )
    }

    @Test
    fun displaySet_matchesTheHostFieldNames() {
        assertEquals(
            """{"displayId":"\\\\.\\DISPLAY1","value":70}""",
            SwitchboardJson.encodeToString(DisplaySet("\\\\.\\DISPLAY1", 70))
        )
    }

    @Test
    fun hostState_readsTheOutputsTheHostBroadcasts() {
        val state = SwitchboardJson.decodeFromString(
            HostState.serializer(),
            """{"outputs":[{"id":"a","name":"Speakers","default":true},
               {"id":"b","name":"Headphones","default":false}],
               "capabilities":["outputs"]}"""
        )
        assertEquals(2, state.outputs.size)
        assertEquals("Speakers", state.outputs.first { it.default }.name)
        assertEquals(listOf("outputs"), state.capabilities)
    }
}
