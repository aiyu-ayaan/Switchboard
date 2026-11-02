package com.aiyu.switchboard

import com.aiyu.switchboard.data.normaliseCode
import com.aiyu.switchboard.data.parsePairUri
import com.aiyu.switchboard.net.Cmd
import com.aiyu.switchboard.net.Event
import com.aiyu.switchboard.net.FILE_CHUNK
import com.aiyu.switchboard.net.ServerHello
import com.aiyu.switchboard.net.lost
import com.aiyu.switchboard.net.WireJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WireTest {
    // A real URI from the dev daemon (backend/src/app.rs pair_uri).
    private val uri = "switchboard://pair?v=1&id=bQh_hqE5LDkDn940&n=Ayaan%27s%20PC&k=moMjmEH45-WJGkOxpEb0_WfMbeF1XkTsRJh6MMkZ6TM" +
        "&t=nhlShUARwvxmHq5p0QaMJSyBxhtOo0w-sXW4ZE0UcUg&a=172.16.0.2,192.168.1.15&p=9428"

    @Test fun parsesPairUri() {
        val o = parsePairUri(uri)!!
        assertEquals("bQh_hqE5LDkDn940", o.target.id)
        assertEquals("Ayaan's PC", o.target.name)
        assertEquals(listOf("172.16.0.2", "192.168.1.15"), o.target.addrs)
        assertEquals(9428, o.target.port)
        assertEquals(32, o.secret.bytes.size)
    }

    @Test fun rejectsOtherUris() {
        assertNull(parsePairUri("https://example.com"))
        assertNull(parsePairUri(uri.replace("v=1", "v=2")))
        assertNull(parsePairUri(uri.replace("&t=nhlShUARwvxmHq5p0QaMJSyBxhtOo0w-sXW4ZE0UcUg", "")))
    }

    @Test fun codeNormalisation() {
        assertEquals("AB3D9KQ2", normaliseCode("ab3d-9kq2"))
        // 0, O, 1, I and L are not in the alphabet.
        assertEquals("AB", normaliseCode("a0O1ILb"))
    }

    @Test fun decodesEvents() {
        val s = WireJson.decodeFromString(
            Event.serializer(),
            """{"type":"state","volume":{"level":40,"muted":false},"displays":[{"brightness":6,"contrast":50,"id":"ddc:i2c-8","kind":"external","name":"EK240Y P6"}],"media":null,"stats":{"battery":null,"cpu":3.5,"mem_total":10,"mem_used":5,"uptime":9}}""",
        ) as Event.State
        assertEquals(40, s.volume!!.level)
        assertTrue(s.displays.single().external)
        assertEquals(Event.Revoked, WireJson.decodeFromString(Event.serializer(), """{"type":"revoked"}"""))
        val r = WireJson.decodeFromString(Event.serializer(), """{"type":"result","id":3,"ok":false,"error":"x"}""") as Event.Result
        assertEquals("x", r.error)
        val rej = WireJson.decodeFromString(ServerHello.serializer(), """{"type":"reject","reason":"unknown"}""")
        assertEquals(ServerHello.Reject("unknown"), rej)
    }

    @Test fun decodesMixer() {
        val s = WireJson.decodeFromString(
            Event.serializer(),
            """{"type":"state","volume":null,"outputs":[{"id":"a","name":"Speakers","default":true}],"apps":[{"id":"202","name":"Firefox","detail":"Radio","level":40,"muted":false}],"displays":[],"media":null,"stats":{"cpu":0.0,"mem_used":0,"mem_total":0,"uptime":0,"battery":null}}""",
        ) as Event.State
        assertTrue(s.outputs.single().default)
        assertEquals(40, s.apps.single().level)
        assertEquals("""{"type":"set_app_volume","app":"202","level":100}""", Cmd.setAppVolume("202", 150).toString())
        assertEquals("""{"type":"set_output","output":"a"}""", Cmd.setOutput("a").toString())
    }

    @Test fun fileMessages() {
        val offer = WireJson.decodeFromString(Event.serializer(), """{"type":"file_offer","xfer":"abc","name":"a.txt","size":12}""")
        assertEquals(Event.FileOffer("abc", "a.txt", 12), offer)
        val r = WireJson.decodeFromString(Event.serializer(), """{"type":"result","id":4,"ok":true,"data":"AAE"}""") as Event.Result
        assertEquals("AAE", r.data)
        assertEquals("""{"type":"file_chunk","xfer":"x","offset":196608,"data":"AAE"}""", Cmd.fileChunk("x", 196608, "AAE").toString())
        assertEquals("""{"type":"file_read","xfer":"x","offset":0,"len":196608}""", Cmd.fileRead("x", 0, FILE_CHUNK).toString())
    }

    @Test fun lostLinksAreWaitedForButRefusalsAreNot() {
        assertTrue(Event.Result(1, false, "Disconnected").lost())
        assertTrue(Event.Result(1, false, "Not connected").lost())
        assertTrue(Event.Result(1, false, "AiyuWithLinux didn't answer").lost())
        assertEquals(false, Event.Result(1, false, "unknown transfer").lost())
        assertEquals(false, Event.Result(1, true).lost())
    }

    @Test fun commandShape() {
        assertEquals("""{"type":"set_brightness","display":"ddc:i2c-8","level":100}""", Cmd.setBrightness("ddc:i2c-8", 140).toString())
    }
}
