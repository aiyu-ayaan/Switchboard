package com.switchboard.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.switchboard.app.net.MetricPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ChartMetricCategory(val label: String) {
    CPU_GPU("CPU & GPU"),
    MEMORY("Memory"),
    NETWORK("Network")
}

object ChartMath {
    fun normalizeY(value: Double, min: Double, max: Double, height: Float): Float {
        if (max <= min) return height
        val clamped = value.coerceIn(min, max)
        val fraction = (clamped - min) / (max - min)
        return (height * (1.0 - fraction)).toFloat()
    }

    fun findNearestPoint(points: List<MetricPoint>, touchX: Float, width: Float): MetricPoint? {
        if (points.isEmpty() || width <= 0f) return null
        if (points.size == 1) return points.first()

        val fraction = (touchX / width).coerceIn(0f, 1f)
        val index = kotlin.math.round((fraction * (points.size - 1))).toInt().coerceIn(0, points.size - 1)
        return points[index]
    }

    fun formatTime(timestampSec: Long, range: String): String {
        if (timestampSec <= 0) return ""
        val millis = timestampSec * 1000L
        val pattern = when (range) {
            "1m" -> "HH:mm:ss"
            "1h" -> "HH:mm:ss"
            "12h", "24h" -> "HH:mm"
            "1w", "30d" -> "MMM d, HH:mm"
            else -> "HH:mm"
        }
        val sdf = SimpleDateFormat(pattern, Locale.getDefault())
        return sdf.format(Date(millis))
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.1f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            kb >= 1.0 -> String.format(Locale.US, "%.1f KB", kb)
            else -> "$bytes B"
        }
    }

    fun formatRate(bps: Long): String {
        return "${formatBytes(bps)}/s"
    }
}

@Composable
fun ResourceLineChart(
    points: List<MetricPoint>,
    rangeStr: String,
    category: ChartMetricCategory,
    modifier: Modifier = Modifier
) {
    var touchX by remember { mutableStateOf<Float?>(null) }
    val scrubPoint = remember(points, touchX) {
        touchX?.let { x -> ChartMath.findNearestPoint(points, x, 1000f) }
    }

    // Tokyo Night expressive colors
    val cpuColor = Color(0xFF9ECE6A)    // Level Green
    val gpuColor = Color(0xFF7AA2F7)    // Secondary Blue
    val ramColor = Color(0xFFBB9AF7)    // Tertiary Purple
    val netRxColor = Color(0xFF2AC3DE)  // Cyan
    val netTxColor = Color(0xFFFF9E64)  // Orange
    val gridColor = Color(0xFF282B3A)

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header stats or tooltip scrub value
            val activePoint = scrubPoint ?: points.lastOrNull()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (scrubPoint != null) {
                            "Scrubbing • ${ChartMath.formatTime(scrubPoint.timestamp, rangeStr)}"
                        } else {
                            "Current Overview"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (scrubPoint != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    when (category) {
                        ChartMetricCategory.CPU_GPU -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(8.dp).background(cpuColor, CircleShape))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "CPU ${String.format(Locale.US, "%.1f%%", activePoint?.cpu ?: 0.0)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace,
                                    color = cpuColor
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Box(modifier = Modifier.size(8.dp).background(gpuColor, CircleShape))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "GPU ${String.format(Locale.US, "%.1f%%", activePoint?.gpu ?: 0.0)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace,
                                    color = gpuColor
                                )
                            }
                        }
                        ChartMetricCategory.MEMORY -> {
                            val ramUsed = activePoint?.ramUsed ?: 0L
                            val ramTotal = activePoint?.ramTotal ?: 0L
                            val pct = if (ramTotal > 0) (ramUsed.toDouble() / ramTotal * 100.0) else 0.0
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(8.dp).background(ramColor, CircleShape))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "RAM ${String.format(Locale.US, "%.1f%%", pct)} (${ChartMath.formatBytes(ramUsed)} / ${ChartMath.formatBytes(ramTotal)})",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace,
                                    color = ramColor
                                )
                            }
                        }
                        ChartMetricCategory.NETWORK -> {
                            val rx = activePoint?.netRx ?: 0L
                            val tx = activePoint?.netTx ?: 0L
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(8.dp).background(netRxColor, CircleShape))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "↓ ${ChartMath.formatRate(rx)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace,
                                    color = netRxColor
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Box(modifier = Modifier.size(8.dp).background(netTxColor, CircleShape))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "↑ ${ChartMath.formatRate(tx)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace,
                                    color = netTxColor
                                )
                            }
                        }
                    }
                }

                if (activePoint != null && activePoint.timestamp > 0) {
                    Text(
                        text = ChartMath.formatTime(activePoint.timestamp, rangeStr),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Chart Canvas
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(points) {
                            detectTapGestures(
                                onPress = { offset ->
                                    touchX = offset.x
                                    tryAwaitRelease()
                                    touchX = null
                                }
                            )
                        }
                        .pointerInput(points) {
                            detectDragGestures(
                                onDragStart = { offset -> touchX = offset.x },
                                onDragEnd = { touchX = null },
                                onDragCancel = { touchX = null },
                                onDrag = { change, _ ->
                                    change.consume()
                                    touchX = change.position.x
                                }
                            )
                        }
                ) {
                    val w = size.width
                    val h = size.height

                    // 1. Draw horizontal grid guides
                    val gridLines = 4
                    for (i in 0..gridLines) {
                        val y = h * (i.toFloat() / gridLines)
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, y),
                            end = Offset(w, y),
                            strokeWidth = 1f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                        )
                    }

                    if (points.size < 2) {
                        return@Canvas
                    }

                    // Helper to draw a series line & gradient fill
                    fun drawSeries(
                        values: List<Double>,
                        minVal: Double,
                        maxVal: Double,
                        strokeColor: Color,
                        gradientColor: Color
                    ) {
                        val path = Path()
                        val fillPath = Path()

                        val stepX = w / (values.size - 1)

                        val firstY = ChartMath.normalizeY(values[0], minVal, maxVal, h)
                        path.moveTo(0f, firstY)
                        fillPath.moveTo(0f, h)
                        fillPath.lineTo(0f, firstY)

                        for (i in 0 until values.size - 1) {
                            val x0 = i * stepX
                            val y0 = ChartMath.normalizeY(values[i], minVal, maxVal, h)
                            val x1 = (i + 1) * stepX
                            val y1 = ChartMath.normalizeY(values[i + 1], minVal, maxVal, h)

                            val midX = (x0 + x1) / 2f
                            path.cubicTo(midX, y0, midX, y1, x1, y1)
                            fillPath.cubicTo(midX, y0, midX, y1, x1, y1)
                        }

                        fillPath.lineTo(w, h)
                        fillPath.close()

                        // Draw area fill
                        drawPath(
                            path = fillPath,
                            brush = Brush.verticalGradient(
                                colors = listOf(gradientColor.copy(alpha = 0.35f), Color.Transparent),
                                startY = 0f,
                                endY = h
                            )
                        )

                        // Draw stroke curve
                        drawPath(
                            path = path,
                            color = strokeColor,
                            style = Stroke(width = 2.5f, cap = StrokeCap.Round)
                        )
                    }

                    when (category) {
                        ChartMetricCategory.CPU_GPU -> {
                            drawSeries(points.map { it.cpu }, 0.0, 100.0, cpuColor, cpuColor)
                            drawSeries(points.map { it.gpu }, 0.0, 100.0, gpuColor, gpuColor)
                        }
                        ChartMetricCategory.MEMORY -> {
                            val maxRam = points.maxOfOrNull { it.ramTotal }?.toDouble() ?: 1.0
                            val usedList = points.map { it.ramUsed.toDouble() }
                            drawSeries(usedList, 0.0, maxRam, ramColor, ramColor)
                        }
                        ChartMetricCategory.NETWORK -> {
                            val maxRx = points.maxOfOrNull { it.netRx }?.toDouble() ?: 1024.0
                            val maxTx = points.maxOfOrNull { it.netTx }?.toDouble() ?: 1024.0
                            val maxNet = maxOf(maxRx, maxTx, 1024.0)
                            drawSeries(points.map { it.netRx.toDouble() }, 0.0, maxNet, netRxColor, netRxColor)
                            drawSeries(points.map { it.netTx.toDouble() }, 0.0, maxNet, netTxColor, netTxColor)
                        }
                    }

                    // 2. Draw scrubber indicator if active
                    touchX?.let { tx ->
                        val clampedX = tx.coerceIn(0f, w)
                        drawLine(
                            color = Color.White.copy(alpha = 0.7f),
                            start = Offset(clampedX, 0f),
                            end = Offset(clampedX, h),
                            strokeWidth = 1.5f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)
                        )

                        // Highlight point on curves
                        val idx = ((clampedX / w) * (points.size - 1)).toInt().coerceIn(0, points.size - 1)
                        val pt = points[idx]

                        when (category) {
                            ChartMetricCategory.CPU_GPU -> {
                                val cy = ChartMath.normalizeY(pt.cpu, 0.0, 100.0, h)
                                val gy = ChartMath.normalizeY(pt.gpu, 0.0, 100.0, h)
                                drawCircle(Color.White, radius = 5f, center = Offset(clampedX, cy))
                                drawCircle(cpuColor, radius = 3.5f, center = Offset(clampedX, cy))
                                drawCircle(Color.White, radius = 5f, center = Offset(clampedX, gy))
                                drawCircle(gpuColor, radius = 3.5f, center = Offset(clampedX, gy))
                            }
                            ChartMetricCategory.MEMORY -> {
                                val maxRam = points.maxOfOrNull { it.ramTotal }?.toDouble() ?: 1.0
                                val my = ChartMath.normalizeY(pt.ramUsed.toDouble(), 0.0, maxRam, h)
                                drawCircle(Color.White, radius = 5f, center = Offset(clampedX, my))
                                drawCircle(ramColor, radius = 3.5f, center = Offset(clampedX, my))
                            }
                            ChartMetricCategory.NETWORK -> {
                                val maxNet = maxOf(
                                    points.maxOfOrNull { it.netRx }?.toDouble() ?: 1024.0,
                                    points.maxOfOrNull { it.netTx }?.toDouble() ?: 1024.0,
                                    1024.0
                                )
                                val rxy = ChartMath.normalizeY(pt.netRx.toDouble(), 0.0, maxNet, h)
                                val txy = ChartMath.normalizeY(pt.netTx.toDouble(), 0.0, maxNet, h)
                                drawCircle(Color.White, radius = 5f, center = Offset(clampedX, rxy))
                                drawCircle(netRxColor, radius = 3.5f, center = Offset(clampedX, rxy))
                                drawCircle(Color.White, radius = 5f, center = Offset(clampedX, txy))
                                drawCircle(netTxColor, radius = 3.5f, center = Offset(clampedX, txy))
                            }
                        }
                    }
                }
            }

            // X-Axis time boundary labels
            if (points.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = ChartMath.formatTime(points.first().timestamp, rangeStr),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = ChartMath.formatTime(points.last().timestamp, rangeStr),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
