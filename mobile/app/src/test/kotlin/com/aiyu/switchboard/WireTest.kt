package com.aiyu.switchboard

import com.aiyu.switchboard.data.normaliseCode
import com.aiyu.switchboard.data.parsePairUri
import com.aiyu.switchboard.net.Cmd
import com.aiyu.switchboard.net.Event
import com.aiyu.switchboard.net.ServerHello
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

    @Test fun commandShape() {
        assertEquals("""{"type":"set_brightness","display":"ddc:i2c-8","level":100}""", Cmd.setBrightness("ddc:i2c-8", 140).toString())
    }
}
