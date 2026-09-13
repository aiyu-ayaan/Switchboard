package com.switchboard.app.widget

import android.content.Context
import com.switchboard.app.net.MetricPoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The last live reading a connected desktop sent, kept so the performance
 * widget has something to draw when the app is not running.
 *
 * Telemetry only flows over an open session, and a home-screen widget is
 * mostly looked at when the app is closed. Without a snapshot the widget would
 * be blank exactly when it is being read, so the last reading is persisted and
 * stamped -- a number with "2 min ago" under it is useful, a blank card is not.
 *
 * Plain preferences rather than the keystore-backed store: these are load
 * percentages and byte counters, not credentials, and the widget reads them on
 * every redraw.
 */
@Serializable
data class TelemetrySnapshot(
    val hostName: String = "",
    val capturedAt: Long = 0L,
    val cpu: Double = 0.0,
    val ramUsed: Long = 0L,
    val ramTotal: Long = 0L,
    val gpu: Double = 0.0,
    val gpuMemUsed: Long = 0L,
    val gpuMemTotal: Long = 0L,
    val netRx: Long = 0L,
    val netTx: Long = 0L,
    val cpuTemp: Double? = null,
    val gpuTemp: Double? = null
) {
    val ramPercent: Double get() = percent(ramUsed, ramTotal)
    val gpuMemPercent: Double get() = percent(gpuMemUsed, gpuMemTotal)

    private fun percent(used: Long, total: Long): Double =
        if (total > 0) used * 100.0 / total else 0.0
}

/** Reads and writes [TelemetrySnapshot] for the widget. */
object WidgetTelemetry {

    private const val PREFS = "switchboard-widget"
    private const val KEY_SNAPSHOT = "telemetry-snapshot"

    /**
     * How often a live reading is written through.
     *
     * Readings arrive every couple of seconds while a phone is connected, and
     * a widget can only be redrawn so usefully: each write is a preferences
     * commit plus a binder round trip per placed widget. Fifteen seconds keeps
     * the card current without making the session pay for the widget.
     */
    const val WRITE_INTERVAL_MS = 15_000L

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun read(context: Context): TelemetrySnapshot? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SNAPSHOT, null) ?: return null
        return runCatching { json.decodeFromString(TelemetrySnapshot.serializer(), raw) }.getOrNull()
    }

    fun write(context: Context, snapshot: TelemetrySnapshot) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SNAPSHOT, json.encodeToString(TelemetrySnapshot.serializer(), snapshot))
            .apply()
    }

    /** Drops the snapshot, so a forgotten desktop stops being shown. */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_SNAPSHOT).apply()
    }

    fun snapshotOf(hostName: String, point: MetricPoint, now: Long): TelemetrySnapshot =
        TelemetrySnapshot(
            hostName = hostName,
            capturedAt = now,
            cpu = point.cpu,
            ramUsed = point.ramUsed,
            ramTotal = point.ramTotal,
            gpu = point.gpu,
            gpuMemUsed = point.gpuMemUsed,
            gpuMemTotal = point.gpuMemTotal,
            netRx = point.netRx,
            netTx = point.netTx,
            cpuTemp = point.cpuTemp,
            gpuTemp = point.gpuTemp
        )
}

/**
 * How stale a reading is, in the words a glance needs.
 *
 * Deliberately coarse: a widget is read at arm's length, and "3 min ago" says
 * everything "3 min 12 s ago" would.
 */
internal fun relativeAge(capturedAt: Long, now: Long): String {
    if (capturedAt <= 0L) return ""
    val seconds = ((now - capturedAt) / 1000).coerceAtLeast(0)
    return when {
        seconds < 20 -> "just now"
        seconds < 60 -> "${seconds}s ago"
        seconds < 3600 -> "${seconds / 60} min ago"
        seconds < 86_400 -> "${seconds / 3600} h ago"
        else -> "${seconds / 86_400} d ago"
    }
}

/** A byte-rate in the shortest form that still reads correctly. */
internal fun formatRate(bytesPerSecond: Long): String {
    val units = listOf("B/s", "KB/s", "MB/s", "GB/s")
    var value = bytesPerSecond.coerceAtLeast(0).toDouble()
    var unit = 0
    while (value >= 1000 && unit < units.lastIndex) {
        value /= 1000
        unit++
    }
    return if (unit == 0 || value >= 100) "${value.toInt()} ${units[unit]}"
    else String.format("%.1f %s", value, units[unit])
}

/** Bytes as the widget's stat cards show them: one figure, no decimals past GB. */
internal fun formatBytes(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.coerceAtLeast(0).toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (value >= 100 || unit <= 1) "${value.toInt()} ${units[unit]}"
    else String.format("%.1f %s", value, units[unit])
}
