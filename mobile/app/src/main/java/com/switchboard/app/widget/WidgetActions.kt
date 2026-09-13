package com.switchboard.app.widget

import android.content.Context
import com.switchboard.app.ConnectionStatus
import com.switchboard.app.R
import com.switchboard.app.SwitchboardConnection
import com.switchboard.app.net.Actions
import com.switchboard.app.net.MediaCommand
import com.switchboard.app.net.PowerCommand
import com.switchboard.app.net.Volume
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One button a widget can carry.
 *
 * [id] is what a widget stores, so it outlives a reinstall and an icon change;
 * nothing else about an entry is persisted. Adding an entry here is all a new
 * widget button needs -- the widget renders the user's selection from this
 * list and the settings screen offers the rest of it.
 */
data class WidgetAction(
    val id: String,
    val label: String,
    val iconRes: Int,
    /** Shown in the settings list under the label. */
    val description: String,
    /**
     * Whether a mis-tap costs the user something they cannot undo from the
     * phone. Those are off by default and marked in the settings list.
     */
    val disruptive: Boolean = false
)

/** Every button the widget knows how to render, in the order settings lists them. */
val WIDGET_ACTIONS: List<WidgetAction> = listOf(
    WidgetAction("media_prev", "Previous", R.drawable.ic_widget_prev, "Previous track"),
    WidgetAction("media_toggle", "Play / Pause", R.drawable.ic_widget_play, "Toggle playback"),
    WidgetAction("media_next", "Next", R.drawable.ic_widget_next, "Next track"),
    WidgetAction("volume_down", "Volume down", R.drawable.ic_widget_volume_down, "Lower the desktop volume by 5"),
    WidgetAction("volume_up", "Volume up", R.drawable.ic_widget_volume_up, "Raise the desktop volume by 5"),
    WidgetAction("volume_mute", "Mute", R.drawable.ic_widget_volume_off, "Toggle the desktop's mute"),
    WidgetAction("lock", "Lock", R.drawable.ic_widget_lock, "Lock the workstation"),
    WidgetAction("display_off", "Screen off", R.drawable.ic_widget_display_off, "Turn the desktop's displays off"),
    WidgetAction("sleep", "Sleep", R.drawable.ic_widget_sleep, "Suspend the desktop", disruptive = true),
    WidgetAction("shutdown", "Shut down", R.drawable.ic_widget_power, "Shut the desktop down", disruptive = true)
)

/**
 * What a freshly placed widget shows: the controls that are reached most often
 * and cost nothing to press by accident.
 */
val DEFAULT_WIDGET_ACTION_IDS: List<String> =
    listOf("media_prev", "media_toggle", "media_next", "volume_down", "volume_up", "lock")

fun widgetActionById(id: String): WidgetAction? = WIDGET_ACTIONS.firstOrNull { it.id == id }

/** How a command finished, so the widget can say something useful. */
enum class WidgetCommandResult { Sent, Unreachable, UnknownHost }

/** Volume step one tap moves, matching the desktop's own keyboard step. */
private const val VOLUME_STEP = 5

/**
 * How long a tap waits for a desktop that is not currently connected.
 *
 * The ceiling is the broadcast this runs inside: a widget click gives the app
 * a foreground slot for roughly ten seconds, and being killed mid-handshake
 * would leave the user staring at a button that did nothing. A LAN handshake
 * is well under a second, so this only ever elapses when the desktop is off.
 */
private const val CONNECT_TIMEOUT_MS = 7_000L

/**
 * Runs one widget button against one paired desktop.
 *
 * The app holds a single session, so a tap on a desktop that is not the active
 * one moves the session to it -- the same thing the host switcher in the app
 * does. That is the cost of the one-connection model, and it is what makes a
 * widget listing every paired machine work at all.
 */
suspend fun runWidgetAction(
    context: Context,
    daemonId: String,
    actionId: String
): WidgetCommandResult {
    val connection = SwitchboardConnection.get(context)
    val host = connection.hosts().firstOrNull { it.daemonId == daemonId }
        ?: return WidgetCommandResult.UnknownHost

    val live = connection.state.value
    if (live.status != ConnectionStatus.Connected || live.activeHost?.daemonId != daemonId) {
        connection.connect(host)
        withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            connection.state.first {
                it.status == ConnectionStatus.Connected && it.activeHost?.daemonId == daemonId
            }
        } ?: return WidgetCommandResult.Unreachable

        // A volume step needs the level it is stepping from, and that arrives
        // in the host's first broadcast rather than in the handshake. Cheap to
        // wait for and only the volume buttons pay it.
        if (actionId.startsWith("volume_")) {
            withTimeoutOrNull(2_000L) {
                connection.state.first { it.host.hostName.isNotEmpty() }
            }
        }
    }

    when (actionId) {
        "media_prev" -> connection.send(Actions.MEDIA_COMMAND, MediaCommand("prev"))
        "media_toggle" -> connection.send(Actions.MEDIA_COMMAND, MediaCommand("toggle"))
        "media_next" -> connection.send(Actions.MEDIA_COMMAND, MediaCommand("next"))
        "volume_down" -> stepVolume(connection, -VOLUME_STEP)
        "volume_up" -> stepVolume(connection, VOLUME_STEP)
        "volume_mute" -> {
            val current = connection.state.value.host.volume
            connection.send(Actions.VOLUME_SET, Volume(current.level, !current.muted))
        }
        "lock" -> connection.send(Actions.SYSTEM_LOCK)
        "display_off" -> connection.send(Actions.SYSTEM_POWER, PowerCommand("display_off"))
        "sleep" -> connection.send(Actions.SYSTEM_POWER, PowerCommand("sleep"))
        "shutdown" -> connection.send(Actions.SYSTEM_POWER, PowerCommand("shutdown"))
        else -> return WidgetCommandResult.UnknownHost
    }

    // The socket write is queued, and nothing in this process is keeping it
    // alive once the broadcast returns. A beat is enough for OkHttp's writer
    // to drain the frame.
    delay(250)
    return WidgetCommandResult.Sent
}

private fun stepVolume(connection: SwitchboardConnection, delta: Int) {
    val current = connection.state.value.host.volume
    val level = steppedVolume(current.level, delta)
    connection.patchHost { it.copy(volume = Volume(level, current.muted)) }
    connection.send(Actions.VOLUME_SET, Volume(level, current.muted))
}

/** The level a step lands on, split out so the clamp can be exercised. */
internal fun steppedVolume(level: Int, delta: Int): Int = (level + delta).coerceIn(0, 100)
