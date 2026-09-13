package com.switchboard.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity as actionStartActivityIntent
import androidx.glance.appwidget.components.CircleIconButton
import androidx.glance.appwidget.components.Scaffold
import androidx.glance.appwidget.components.TitleBar
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.switchboard.app.ConnectionStatus
import com.switchboard.app.MainActivity
import com.switchboard.app.R
import com.switchboard.app.SwitchboardConnection
import com.switchboard.app.data.HostStore
import com.switchboard.app.data.KnownHost
import com.switchboard.app.net.HostState

/**
 * Home-screen control surface for every paired desktop.
 *
 * The list is not configured: it is whatever [HostStore] holds, so pairing a
 * machine in the app makes it appear here and forgetting one removes it. What
 * *is* configured is the row of buttons each machine carries, chosen per widget
 * in [WidgetConfigActivity] and stored in this widget's own Glance state.
 *
 * Rendering deliberately never builds a [SwitchboardConnection]: constructing
 * that holder dials the last host, and a widget redraw -- which the launcher
 * asks for on rotation, on resize and after every reboot -- must not open a
 * socket. [SwitchboardConnection.peek] returns the live holder when this
 * process already has one and null otherwise, which is exactly the difference
 * between "connected" and "unknown until you press something".
 */
class SwitchboardWidget : GlanceAppWidget() {

    /**
     * Exact, because the button row lays itself out against the real width:
     * how many 48dp targets fit is the whole question, and the default mode
     * would answer it with the smallest size the widget could ever be.
     */
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Read outside the composition: this touches the keystore, and Glance
        // may recompose several times for one update.
        val hosts = HostStore(context).hosts().sortedByDescending { it.lastConnected }
        val live = SwitchboardConnection.peek()?.state?.value
            ?.takeIf { it.status == ConnectionStatus.Connected }
        val connectedId = live?.activeHost?.daemonId
        // Only the connected desktop has one: the session reports the machine
        // it is attached to, and nothing else.
        val liveHost = live?.host
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)

        provideContent {
            GlanceTheme {
                WidgetBody(hosts, connectedId, liveHost, appWidgetId)
            }
        }
    }

    @Composable
    private fun WidgetBody(
        hosts: List<KnownHost>,
        connectedId: String?,
        liveHost: HostState?,
        appWidgetId: Int
    ) {
        val context = LocalContext.current
        val selected = selectedActionIds(currentState())
        val density = densityFor(LocalSize.current.height)

        Scaffold(
            // First thing dropped on a short widget: the title bar costs about
            // 48dp and the desktops are what the widget is for. The settings
            // screen stays reachable by long-pressing the widget.
            titleBar = if (!density.showsTitleBar) null else {
                {
                    TitleBar(
                        startIcon = ImageProvider(R.drawable.ic_widget_computer),
                        title = "Switchboard",
                        iconColor = GlanceTheme.colors.primary,
                        textColor = GlanceTheme.colors.onSurface,
                        actions = {
                            CircleIconButton(
                                imageProvider = ImageProvider(R.drawable.ic_widget_refresh),
                                contentDescription = "Refresh",
                                backgroundColor = null,
                                contentColor = GlanceTheme.colors.onSurfaceVariant,
                                onClick = actionRunCallback<RefreshWidgetCallback>()
                            )
                            CircleIconButton(
                                imageProvider = ImageProvider(R.drawable.ic_widget_settings),
                                contentDescription = "Choose controls",
                                backgroundColor = null,
                                contentColor = GlanceTheme.colors.onSurfaceVariant,
                                onClick = actionStartActivityIntent(
                                    WidgetConfigActivity.reconfigureIntent(context, appWidgetId)
                                )
                            )
                        }
                    )
                }
            }
        ) {
            if (hosts.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                    items(hosts, itemId = { it.daemonId.hashCode().toLong() }) { host ->
                        val connected = host.daemonId == connectedId
                        HostCard(host, connected, selected, liveHost.takeIf { connected }, density)
                    }
                }
            }
        }
    }

    @Composable
    private fun EmptyState() {
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .clickable(actionStartActivity<MainActivity>()),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No paired desktops yet.\nTap to open Switchboard and pair one.",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 13.sp
                )
            )
        }
    }

    @Composable
    private fun HostCard(
        host: KnownHost,
        connected: Boolean,
        selected: List<WidgetAction>,
        liveHost: HostState?,
        density: WidgetDensity
    ) {
        // Connection is carried by the container's tone rather than a coloured
        // dot: on a widget read at arm's length a filled card is legible and an
        // 8dp dot is not.
        val container =
            if (connected) GlanceTheme.colors.primaryContainer
            else GlanceTheme.colors.surfaceVariant
        val onContainer =
            if (connected) GlanceTheme.colors.onPrimaryContainer
            else GlanceTheme.colors.onSurfaceVariant

        Column(modifier = GlanceModifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Column(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .background(container)
                    .cornerRadius(CARD_CORNER)
                    .padding(
                        horizontal = 12.dp,
                        vertical = if (density == WidgetDensity.Tiny) 6.dp else 10.dp
                    )
            ) {
                Row(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        // Opening the app on the machine you were about to
                        // control is the one action no button list can cover.
                        .clickable(actionStartActivity<MainActivity>()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = host.hostName.ifEmpty { host.host },
                        maxLines = 1,
                        style = TextStyle(
                            color = onContainer,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        modifier = GlanceModifier.defaultWeight()
                    )
                    StatusPill(connected, fitsLongStatus(LocalSize.current.width))
                }

                // The live readout earns its place only when the widget is tall
                // enough to hold it without pushing the buttons out of reach.
                // A short placement stays a row of controls.
                if (liveHost != null && fitsHostDetail(LocalSize.current.height)) {
                    Spacer(GlanceModifier.height(8.dp))
                    LiveDetail(liveHost)
                }

                if (selected.isNotEmpty()) {
                    Spacer(GlanceModifier.height(if (density == WidgetDensity.Tiny) 6.dp else 10.dp))
                    ActionRows(host, selected, connected)
                }
            }
        }
    }

    /**
     * What the connected desktop is doing right now: what it is playing, and
     * how loud.
     *
     * Both come from the session's own broadcast, which the app is already
     * receiving; nothing here asks the desktop for anything. On a widget with
     * room to spare this is the difference between a remote and a readout.
     */
    @Composable
    private fun LiveDetail(host: HostState) {
        Column(modifier = GlanceModifier.fillMaxWidth()) {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    provider = ImageProvider(
                        if (host.media.isPlaying) R.drawable.ic_widget_play
                        else R.drawable.ic_widget_pause
                    ),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimaryContainer),
                    modifier = GlanceModifier.size(14.dp)
                )
                Spacer(GlanceModifier.width(6.dp))
                Text(
                    text = host.media.summary,
                    maxLines = 1,
                    style = TextStyle(
                        color = GlanceTheme.colors.onPrimaryContainer,
                        fontSize = 12.sp
                    ),
                    modifier = GlanceModifier.defaultWeight()
                )
            }
            Spacer(GlanceModifier.height(6.dp))
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    provider = ImageProvider(
                        if (host.volume.muted) R.drawable.ic_widget_volume_off
                        else R.drawable.ic_widget_volume_up
                    ),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimaryContainer),
                    modifier = GlanceModifier.size(14.dp)
                )
                Spacer(GlanceModifier.width(6.dp))
                LinearProgressIndicator(
                    progress = if (host.volume.muted) 0f else host.volume.level / 100f,
                    color = GlanceTheme.colors.primary,
                    backgroundColor = GlanceTheme.colors.surface,
                    modifier = GlanceModifier.defaultWeight().height(4.dp).cornerRadius(2.dp)
                )
                Spacer(GlanceModifier.width(8.dp))
                Text(
                    text = if (host.volume.muted) "Muted" else host.volume.level.toString() + "%",
                    maxLines = 1,
                    style = TextStyle(
                        color = GlanceTheme.colors.onPrimaryContainer,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
            }
        }
    }

    @Composable
    private fun StatusPill(connected: Boolean, spellItOut: Boolean) {
        Box(
            modifier = GlanceModifier
                .background(
                    if (connected) GlanceTheme.colors.primary
                    else GlanceTheme.colors.surface
                )
                .cornerRadius(8.dp)
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text(
                text = when {
                    connected -> "Live"
                    spellItOut -> "Tap to control"
                    else -> "Idle"
                },
                maxLines = 1,
                style = TextStyle(
                    color = if (connected) GlanceTheme.colors.onPrimary
                    else GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            )
        }
    }

    /**
     * Lays the chosen buttons out over as many rows as the widget's real width
     * needs.
     *
     * A widget is resized by the user, and a row of six 48dp targets does not
     * fit a four-cell placement. Squeezing them below Material's minimum target
     * is the wrong answer -- they wrap instead, which is why this widget
     * declares [SizeMode.Exact] and reads [LocalSize].
     */
    @Composable
    private fun ActionRows(host: KnownHost, selected: List<WidgetAction>, connected: Boolean) {
        val available = LocalSize.current.width - CARD_HORIZONTAL_INSET
        val perRow = buttonsPerRow(available, selected.size)
        selected.chunked(perRow).forEachIndexed { index, row ->
            if (index > 0) Spacer(GlanceModifier.height(4.dp))
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                row.forEach { action ->
                    Box(
                        modifier = GlanceModifier.defaultWeight(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircleIconButton(
                            imageProvider = ImageProvider(action.iconRes),
                            contentDescription = "${action.label} on ${host.hostName}",
                            backgroundColor =
                                if (connected) GlanceTheme.colors.surface
                                else GlanceTheme.colors.background,
                            contentColor =
                                if (action.disruptive) GlanceTheme.colors.error
                                else GlanceTheme.colors.primary,
                            onClick = actionRunCallback<RunActionCallback>(
                                actionParametersOf(
                                    HOST_PARAM to host.daemonId,
                                    ACTION_PARAM to action.id
                                )
                            )
                        )
                    }
                }
            }
        }
    }

    companion object {
        private val CARD_CORNER = 20.dp

        /** The widget's own horizontal padding plus the card's, both sides. */
        private val CARD_HORIZONTAL_INSET = 48.dp

        /** Material's minimum touch target, which is also the button's width. */
        private const val TOUCH_TARGET_DP = 48f

        /**
         * How many buttons fit one row at this width, balanced so two rows
         * carry roughly the same number rather than five and one.
         */
        internal fun buttonsPerRow(available: Dp, count: Int): Int {
            if (count <= 0) return 1
            val fits = (available.value / TOUCH_TARGET_DP).toInt().coerceAtLeast(1)
            if (count <= fits) return count
            val rows = (count + fits - 1) / fits
            return (count + rows - 1) / rows
        }

        /**
         * Action ids this widget shows. A set rather than a list because that
         * is what Preferences stores natively; the display order comes from
         * [WIDGET_ACTIONS] so every widget orders its buttons the same way.
         */
        val ACTIONS_KEY = stringSetPreferencesKey("widget-actions")

        val HOST_PARAM = ActionParameters.Key<String>("daemonId")
        val ACTION_PARAM = ActionParameters.Key<String>("actionId")

        /**
         * Absent state means a widget placed before this key existed, or one
         * whose configuration never completed. Both want the defaults rather
         * than an empty row of buttons.
         */
        fun selectedActionIds(prefs: Preferences): List<WidgetAction> {
            val stored = prefs[ACTIONS_KEY]
                ?: return DEFAULT_WIDGET_ACTION_IDS.mapNotNull(::widgetActionById)
            return WIDGET_ACTIONS.filter { it.id in stored }
        }

        /** Writes a widget's button selection and redraws it. */
        suspend fun saveSelection(context: Context, glanceId: GlanceId, ids: Set<String>) {
            updateAppWidgetState(context, glanceId) { it[ACTIONS_KEY] = ids }
            SwitchboardWidget().update(context, glanceId)
        }
    }
}

class SwitchboardWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SwitchboardWidget()
}

/** Runs one button, then redraws so the connection state catches up. */
class RunActionCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val daemonId = parameters[SwitchboardWidget.HOST_PARAM] ?: return
        val actionId = parameters[SwitchboardWidget.ACTION_PARAM] ?: return
        runWidgetAction(context, daemonId, actionId)
        SwitchboardWidget().update(context, glanceId)
    }
}

/** Re-reads the host list and the live status without sending anything. */
class RefreshWidgetCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        SwitchboardWidget().update(context, glanceId)
    }
}

/**
 * Redraws every placed widget of both kinds.
 *
 * Called from the connection holder when the paired list, the live session or
 * the telemetry snapshot changes -- all three are things a widget shows.
 */
suspend fun updateAllSwitchboardWidgets(context: Context) {
    SwitchboardWidget().updateAll(context)
    PerformanceWidget().updateAll(context)
}
