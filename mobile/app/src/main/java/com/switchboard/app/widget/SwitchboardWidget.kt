package com.switchboard.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity as actionStartActivityIntent
import androidx.glance.appwidget.updateAll
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.switchboard.app.ConnectionStatus
import com.switchboard.app.MainActivity
import com.switchboard.app.R
import com.switchboard.app.SwitchboardConnection
import com.switchboard.app.data.HostStore
import com.switchboard.app.data.KnownHost

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

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Read outside the composition: this touches the keystore, and Glance
        // may recompose several times for one update.
        val hosts = HostStore(context).hosts().sortedByDescending { it.lastConnected }
        val live = SwitchboardConnection.peek()?.state?.value
        val connectedId = live
            ?.takeIf { it.status == ConnectionStatus.Connected }
            ?.activeHost
            ?.daemonId
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)

        provideContent {
            GlanceTheme {
                WidgetBody(hosts, connectedId, appWidgetId)
            }
        }
    }

    @Composable
    private fun WidgetBody(hosts: List<KnownHost>, connectedId: String?, appWidgetId: Int) {
        val context = LocalContext.current
        val selected = selectedActionIds(currentState())

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(16.dp)
                .padding(12.dp)
        ) {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Switchboard",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    modifier = GlanceModifier.defaultWeight()
                )
                IconButton(
                    iconRes = R.drawable.ic_widget_refresh,
                    tint = GlanceTheme.colors.onSurfaceVariant,
                    onClick = actionRunCallback<RefreshWidgetCallback>()
                )
                Spacer(GlanceModifier.width(4.dp))
                IconButton(
                    iconRes = R.drawable.ic_widget_settings,
                    tint = GlanceTheme.colors.onSurfaceVariant,
                    onClick = actionStartActivityIntent(
                        WidgetConfigActivity.reconfigureIntent(context, appWidgetId)
                    )
                )
            }
            Spacer(GlanceModifier.size(8.dp))

            if (hosts.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn {
                    items(hosts, itemId = { it.daemonId.hashCode().toLong() }) { host ->
                        HostCard(host, host.daemonId == connectedId, selected)
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
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp)
            )
        }
    }

    @Composable
    private fun HostCard(host: KnownHost, connected: Boolean, selected: List<WidgetAction>) {
        Column(
            modifier = GlanceModifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        ) {
            Column(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .background(GlanceTheme.colors.secondaryContainer)
                    .cornerRadius(16.dp)
                    .padding(10.dp)
            ) {
                Row(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        // Opening the app on the machine you were about to
                        // control is the one action no button list can cover.
                        .clickable(actionStartActivity<MainActivity>()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_widget_computer),
                        contentDescription = null,
                        colorFilter = androidx.glance.ColorFilter.tint(
                            if (connected) GlanceTheme.colors.primary
                            else GlanceTheme.colors.onSurfaceVariant
                        ),
                        modifier = GlanceModifier.size(16.dp)
                    )
                    Spacer(GlanceModifier.width(8.dp))
                    Text(
                        text = host.hostName.ifEmpty { host.host },
                        maxLines = 1,
                        style = TextStyle(
                            color = GlanceTheme.colors.onSecondaryContainer,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        modifier = GlanceModifier.defaultWeight()
                    )
                    Text(
                        text = if (connected) "Connected" else "Tap to control",
                        maxLines = 1,
                        style = TextStyle(
                            color = if (connected) GlanceTheme.colors.primary
                            else GlanceTheme.colors.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                    )
                }

                if (selected.isNotEmpty()) {
                    Spacer(GlanceModifier.size(8.dp))
                    Row(modifier = GlanceModifier.fillMaxWidth()) {
                        selected.forEach { action ->
                            Box(
                                modifier = GlanceModifier.defaultWeight(),
                                contentAlignment = Alignment.Center
                            ) {
                                IconButton(
                                    iconRes = action.iconRes,
                                    tint = GlanceTheme.colors.onSecondaryContainer,
                                    contentDescription = "${action.label} on ${host.hostName}",
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
        }
    }

    @Composable
    private fun IconButton(
        iconRes: Int,
        tint: ColorProvider,
        onClick: androidx.glance.action.Action,
        contentDescription: String? = null
    ) {
        Box(
            modifier = GlanceModifier
                .size(36.dp)
                .cornerRadius(18.dp)
                .clickable(onClick),
            contentAlignment = Alignment.Center
        ) {
            Image(
                provider = ImageProvider(iconRes),
                contentDescription = contentDescription,
                colorFilter = androidx.glance.ColorFilter.tint(tint),
                modifier = GlanceModifier.size(20.dp)
            )
        }
    }

    companion object {
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
            val stored = prefs[ACTIONS_KEY] ?: return DEFAULT_WIDGET_ACTION_IDS.mapNotNull(::widgetActionById)
            return WIDGET_ACTIONS.filter { it.id in stored }
        }

        /** Writes a widget's button selection and redraws it. */
        suspend fun saveSelection(context: Context, glanceId: GlanceId, ids: Set<String>) {
            updateAppWidgetState(context, glanceId) { it[ACTIONS_KEY] = ids }
            SwitchboardWidget().update(context, glanceId)
        }

        /** Redraws every placed widget, after something they all show changed. */
        suspend fun updateAll(context: Context) {
            SwitchboardWidget().updateAll(context)
        }
    }
}

class SwitchboardWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SwitchboardWidget()
}

/** Runs one button, then redraws so the connection dot catches up. */
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
