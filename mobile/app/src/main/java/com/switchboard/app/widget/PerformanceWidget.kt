package com.switchboard.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.components.CircleIconButton
import androidx.glance.appwidget.components.Scaffold
import androidx.glance.appwidget.components.TitleBar
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.switchboard.app.ConnectionStatus
import com.switchboard.app.MainActivity
import com.switchboard.app.R
import com.switchboard.app.SwitchboardConnection

/**
 * Live load for the desktop this phone is talking to: CPU, memory, GPU and
 * network, with temperatures when the host reports them.
 *
 * Telemetry only flows over an open session, and a widget is mostly read while
 * the app is closed, so the numbers come from the snapshot
 * [WidgetTelemetry] persists rather than from a live socket. Every card is
 * stamped with how old the reading is: a stale number presented as current is
 * worse than no number, and "4 min ago" is the whole difference.
 *
 * Like the controls widget, rendering never constructs
 * [SwitchboardConnection] -- that holder dials on construction, and a launcher
 * redraw must not open a socket.
 */
class PerformanceWidget : GlanceAppWidget() {

    /** The card grid reflows against the real width rather than the smallest. */
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = WidgetTelemetry.read(context)
        val connected = SwitchboardConnection.peek()?.state?.value
            ?.let { it.status == ConnectionStatus.Connected } ?: false
        val now = System.currentTimeMillis()

        provideContent {
            GlanceTheme {
                Body(snapshot, connected, now)
            }
        }
    }

    @Composable
    private fun Body(snapshot: TelemetrySnapshot?, connected: Boolean, now: Long) {
        Scaffold(
            titleBar = {
                TitleBar(
                    startIcon = ImageProvider(R.drawable.ic_widget_speed),
                    title = snapshot?.hostName?.takeIf { it.isNotEmpty() } ?: "Performance",
                    iconColor = GlanceTheme.colors.primary,
                    textColor = GlanceTheme.colors.onSurface,
                    actions = {
                        CircleIconButton(
                            imageProvider = ImageProvider(R.drawable.ic_widget_refresh),
                            contentDescription = "Refresh",
                            backgroundColor = null,
                            contentColor = GlanceTheme.colors.onSurfaceVariant,
                            onClick = actionRunCallback<RefreshPerformanceCallback>()
                        )
                    }
                )
            }
        ) {
            if (snapshot == null) {
                Empty()
            } else {
                Stats(snapshot, connected, now)
            }
        }
    }

    @Composable
    private fun Empty() {
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .clickable(actionStartActivity<MainActivity>()),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No readings yet.\nTap to connect a desktop.",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp)
            )
        }
    }

    @Composable
    private fun Stats(snapshot: TelemetrySnapshot, connected: Boolean, now: Long) {
        Column(modifier = GlanceModifier.fillMaxSize()) {
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                Meter(
                    label = "CPU",
                    value = "${snapshot.cpu.toInt()}%",
                    fraction = snapshot.cpu / 100f,
                    detail = snapshot.cpuTemp?.let { "${it.toInt()}°C" },
                    accent = GlanceTheme.colors.primary,
                    modifier = GlanceModifier.defaultWeight()
                )
                Spacer(GlanceModifier.width(8.dp))
                Meter(
                    label = "RAM",
                    value = "${snapshot.ramPercent.toInt()}%",
                    fraction = snapshot.ramPercent / 100f,
                    detail = if (snapshot.ramTotal > 0)
                        "${formatBytes(snapshot.ramUsed)} / ${formatBytes(snapshot.ramTotal)}"
                    else null,
                    accent = GlanceTheme.colors.tertiary,
                    modifier = GlanceModifier.defaultWeight()
                )
            }
            Spacer(GlanceModifier.height(8.dp))
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                Meter(
                    label = "GPU",
                    value = "${snapshot.gpu.toInt()}%",
                    fraction = snapshot.gpu / 100f,
                    detail = snapshot.gpuTemp?.let { "${it.toInt()}°C" }
                        ?: if (snapshot.gpuMemTotal > 0) "${snapshot.gpuMemPercent.toInt()}% VRAM" else null,
                    accent = GlanceTheme.colors.secondary,
                    modifier = GlanceModifier.defaultWeight()
                )
                Spacer(GlanceModifier.width(8.dp))
                NetworkCard(snapshot, modifier = GlanceModifier.defaultWeight())
            }

            Spacer(GlanceModifier.defaultWeight())
            Row(
                modifier = GlanceModifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (connected) "Live · ${relativeAge(snapshot.capturedAt, now)}"
                    else relativeAge(snapshot.capturedAt, now).replaceFirstChar { it.uppercase() },
                    maxLines = 1,
                    style = TextStyle(
                        color = if (connected) GlanceTheme.colors.primary
                        else GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 11.sp
                    ),
                    modifier = GlanceModifier.defaultWeight()
                )
                Text(
                    text = "Open",
                    style = TextStyle(
                        color = GlanceTheme.colors.primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    modifier = GlanceModifier.clickable(actionStartActivity<MainActivity>())
                )
            }
        }
    }

    /** One load figure with the bar that makes it readable without being read. */
    @Composable
    private fun Meter(
        label: String,
        value: String,
        fraction: Double,
        detail: String?,
        accent: ColorProvider,
        modifier: GlanceModifier
    ) {
        StatCard(modifier) {
            Text(
                text = label,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            )
            Text(
                text = value,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            Spacer(GlanceModifier.height(4.dp))
            LinearProgressIndicator(
                progress = fraction.coerceIn(0.0, 1.0).toFloat(),
                color = accent,
                backgroundColor = GlanceTheme.colors.surface,
                modifier = GlanceModifier.fillMaxWidth().height(4.dp).cornerRadius(2.dp)
            )
            if (detail != null) {
                Spacer(GlanceModifier.height(4.dp))
                Text(
                    text = detail,
                    maxLines = 1,
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 10.sp)
                )
            }
        }
    }

    /**
     * Throughput has no ceiling to fill a bar against, so it is shown as two
     * rates rather than a meter -- a progress bar with an invented maximum
     * would be a decoration, not a reading.
     */
    @Composable
    private fun NetworkCard(snapshot: TelemetrySnapshot, modifier: GlanceModifier) {
        StatCard(modifier) {
            Text(
                text = "NETWORK",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            )
            Spacer(GlanceModifier.height(2.dp))
            Text(
                text = "↓ ${formatRate(snapshot.netRx)}",
                maxLines = 1,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            )
            Text(
                text = "↑ ${formatRate(snapshot.netTx)}",
                maxLines = 1,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            )
        }
    }

    @Composable
    private fun StatCard(modifier: GlanceModifier, content: @Composable () -> Unit) {
        Column(
            modifier = modifier
                .background(GlanceTheme.colors.surfaceVariant)
                .cornerRadius(20.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .clickable(actionStartActivity<MainActivity>())
        ) { content() }
    }
}

class PerformanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PerformanceWidget()
}

/**
 * Re-reads the snapshot.
 *
 * It does not dial the desktop: connecting to refresh a number would make a
 * glance at the home screen cost a handshake and a session hand-over. The
 * numbers go live again the moment the app or "stay connected" brings the
 * session up.
 */
class RefreshPerformanceCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: androidx.glance.action.ActionParameters
    ) {
        PerformanceWidget().update(context, glanceId)
    }
}
