package com.switchboard.app.ui

import com.switchboard.app.net.MetricPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ResourceChartMathTest {

    @Test
    fun testNormalizeY() {
        val yZero = ChartMath.normalizeY(0.0, 0.0, 100.0, 200f)
        assertEquals(200f, yZero, 0.001f) // Y=0 in chart is bottom of canvas

        val yHalf = ChartMath.normalizeY(50.0, 0.0, 100.0, 200f)
        assertEquals(100f, yHalf, 0.001f)

        val yFull = ChartMath.normalizeY(100.0, 0.0, 100.0, 200f)
        assertEquals(0f, yFull, 0.001f) // Y=100 in chart is top of canvas
    }

    @Test
    fun testFindNearestPoint() {
        val points = listOf(
            MetricPoint(timestamp = 1000L, cpu = 10.0),
            MetricPoint(timestamp = 2000L, cpu = 20.0),
            MetricPoint(timestamp = 3000L, cpu = 30.0)
        )

        // Width 300: points are at x=0, x=150, x=300
        val p1 = ChartMath.findNearestPoint(points, 20f, 300f)
        assertNotNull(p1)
        assertEquals(1000L, p1?.timestamp)

        val p2 = ChartMath.findNearestPoint(points, 160f, 300f)
        assertNotNull(p2)
        assertEquals(2000L, p2?.timestamp)

        val p3 = ChartMath.findNearestPoint(points, 290f, 300f)
        assertNotNull(p3)
        assertEquals(3000L, p3?.timestamp)

        // Empty list
        val emptyNearest = ChartMath.findNearestPoint(emptyList(), 50f, 300f)
        assertNull(emptyNearest)
    }

    @Test
    fun testFormatBytes() {
        assertEquals("0 B", ChartMath.formatBytes(0))
        assertEquals("1.0 KB", ChartMath.formatBytes(1024))
        assertEquals("5.0 MB", ChartMath.formatBytes(5 * 1024 * 1024))
        assertEquals("16.0 GB", ChartMath.formatBytes(16L * 1024 * 1024 * 1024))
    }
}
