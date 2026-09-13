package com.switchboard.app

import com.switchboard.app.net.AboutSystemResponse
import com.switchboard.app.net.BatteryInfo
import com.switchboard.app.net.CpuInfo
import com.switchboard.app.net.DriveItem
import com.switchboard.app.net.MetricPoint
import com.switchboard.app.net.OsInfo
import com.switchboard.app.net.ProcessItem
import com.switchboard.app.net.ResourcesLivePush
import com.switchboard.app.net.ResourcesResponse
import com.switchboard.app.net.SwitchboardJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryViewModelTest {

    @Test
    fun testTelemetrySerialization() {
        val pt = MetricPoint(
            timestamp = 1700000000L,
            cpu = 42.5,
            ramUsed = 8589934592L,
            ramTotal = 17179869184L,
            gpu = 30.0,
            netRx = 1048576L,
            netTx = 524288L,
            cpuTemp = 48.0
        )
        val response = ResourcesResponse(
            range = "1h",
            points = listOf(pt)
        )

        val jsonStr = SwitchboardJson.encodeToString(ResourcesResponse.serializer(), response)
        val decoded = SwitchboardJson.decodeFromString(ResourcesResponse.serializer(), jsonStr)

        assertEquals("1h", decoded.range)
        assertEquals(1, decoded.points.size)
        assertEquals(42.5, decoded.points[0].cpu, 0.001)
        assertEquals(48.0, decoded.points[0].cpuTemp ?: 0.0, 0.001)

        val live = ResourcesLivePush(
            current = pt,
            topProcesses = listOf(ProcessItem(name = "chrome.exe", pid = 1234, cpu = 12.0, ramBytes = 500000000L)),
            drives = listOf(DriveItem(device = "C:", label = "Windows", totalBytes = 1000000000L, freeBytes = 400000000L))
        )
        val liveStr = SwitchboardJson.encodeToString(ResourcesLivePush.serializer(), live)
        val decodedLive = SwitchboardJson.decodeFromString(ResourcesLivePush.serializer(), liveStr)
        assertEquals(1, decodedLive.topProcesses.size)
        assertEquals("chrome.exe", decodedLive.topProcesses[0].name)
        assertEquals("C:", decodedLive.drives[0].device)

        val about = AboutSystemResponse(
            os = OsInfo(name = "Windows 11", build = "22631", uptimeSeconds = 7200L),
            cpu = CpuInfo(model = "Intel Core i7", cores = 8, threads = 16, baseClockMhz = 2500),
            battery = BatteryInfo(present = true, charging = true, percent = 95, cycleCount = 15)
        )
        val aboutStr = SwitchboardJson.encodeToString(AboutSystemResponse.serializer(), about)
        val decodedAbout = SwitchboardJson.decodeFromString(AboutSystemResponse.serializer(), aboutStr)
        assertEquals("Windows 11", decodedAbout.os.name)
        assertEquals(8, decodedAbout.cpu.cores)
        assertTrue(decodedAbout.battery.present)
        assertEquals(15, decodedAbout.battery.cycleCount)
    }

    @Test
    fun testTelemetryStateDefaults() {
        val state = TelemetryState()
        assertEquals("1h", state.selectedRange)
        assertTrue(state.points.isEmpty())
        assertTrue(state.topProcesses.isEmpty())
        assertTrue(state.drives.isEmpty())
    }
}
