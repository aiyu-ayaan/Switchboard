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
    fun displayPowerSet_matchesTheHostFieldNames() {
        assertEquals(
            """{"displayId":"\\\\.\\DISPLAY1","on":true}""",
            SwitchboardJson.encodeToString(DisplayPowerSet("\\\\.\\DISPLAY1", true))
        )
        assertEquals(
            """{"displayId":"\\\\.\\DISPLAY2","on":false}""",
            SwitchboardJson.encodeToString(DisplayPowerSet("\\\\.\\DISPLAY2", false))
        )
    }

    @Test
    fun display_readsPowerFlag() {
        val displayWithPower = SwitchboardJson.decodeFromString(
            Display.serializer(),
            """{"id":"d1","name":"Main","power":false}"""
        )
        assertEquals(false, displayWithPower.power)

        val displayDefaultPower = SwitchboardJson.decodeFromString(
            Display.serializer(),
            """{"id":"d2","name":"Secondary"}"""
        )
        assertEquals(true, displayDefaultPower.power)
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

    @Test
    fun powerCommand_matchesHostFieldNames() {
        assertEquals(
            """{"action":"display_off","seconds":0}""",
            SwitchboardJson.encodeToString(PowerCommand("display_off", 0))
        )
        assertEquals(
            """{"action":"shutdown","seconds":900}""",
            SwitchboardJson.encodeToString(PowerCommand("shutdown", 900))
        )
    }

    @Test
    fun inputText_matchesHostFieldName() {
        assertEquals(
            """{"text":"Hello, Switchboard!"}""",
            SwitchboardJson.encodeToString(InputText("Hello, Switchboard!"))
        )
    }

    @Test
    fun clipboardSet_matchesHostFieldName() {
        assertEquals(
            """{"text":"Clipboard content"}""",
            SwitchboardJson.encodeToString(ClipboardSet("Clipboard content"))
        )
    }

    @Test
    fun hostState_readsMicAndInputs() {
        val state = SwitchboardJson.decodeFromString(
            HostState.serializer(),
            """{
                "mic":{"level":65,"muted":false},
                "inputs":[{"id":"mic-1","name":"USB Microphone","default":true}],
                "capabilities":["power","mic","inputs"]
            }"""
        )
        assertEquals(65, state.mic.level)
        assertEquals(false, state.mic.muted)
        assertEquals(1, state.inputs.size)
        assertEquals("USB Microphone", state.inputs.first().name)
        assertEquals(true, state.inputs.first().default)
        assertEquals(listOf("power", "mic", "inputs"), state.capabilities)
    }
}
