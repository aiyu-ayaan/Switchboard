package com.switchboard.app.widget

import com.switchboard.app.net.MetricPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetTelemetryTest {

    /**
     * The age stamp is the whole reason a cached reading is honest rather than
     * misleading, so the boundaries it crosses are worth pinning.
     */
    @Test
    fun age_readsAtAGlance() {
        val now = 1_000_000_000L
        assertEquals("just now", relativeAge(now - 5_000, now))
        assertEquals("45s ago", relativeAge(now - 45_000, now))
        assertEquals("3 min ago", relativeAge(now - 200_000, now))
        assertEquals("2 h ago", relativeAge(now - 7_400_000, now))
        assertEquals("1 d ago", relativeAge(now - 90_000_000, now))
    }

    /** A snapshot that was never written has no age to report. */
    @Test
    fun age_isBlankWithoutAReading() {
        assertEquals("", relativeAge(0L, 1_000L))
    }

    /** A clock that moved backwards must not render a negative age. */
    @Test
    fun age_survivesAClockGoingBackwards() {
        assertEquals("just now", relativeAge(2_000L, 1_000L))
    }

    @Test
    fun rates_pickTheRightUnit() {
        assertEquals("0 B/s", formatRate(0))
        assertEquals("999 B/s", formatRate(999))
        assertEquals("1.0 KB/s", formatRate(1_000))
        assertEquals("1.5 MB/s", formatRate(1_500_000))
        assertEquals("0 B/s", formatRate(-5))
    }

    @Test
    fun bytes_pickTheRightUnit() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("2 KB", formatBytes(2_048))
        assertEquals("1.5 GB", formatBytes(1_610_612_736))
    }

    /**
     * Percentages are derived, not sent: a host that reports no memory total
     * must read as 0 rather than dividing by zero.
     */
    @Test
    fun percentages_handleAMissingTotal() {
        val point = MetricPoint(cpu = 42.0, ramUsed = 4L, ramTotal = 0L, gpuMemUsed = 1L, gpuMemTotal = 4L)
        val snapshot = WidgetTelemetry.snapshotOf("PC", point, now = 7L)
        assertEquals(0.0, snapshot.ramPercent, 0.001)
        assertEquals(25.0, snapshot.gpuMemPercent, 0.001)
        assertEquals(42.0, snapshot.cpu, 0.001)
        assertEquals(7L, snapshot.capturedAt)
        assertNull(snapshot.cpuTemp)
    }
}
