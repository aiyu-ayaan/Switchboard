package com.aiyu.switchboard.quick

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.aiyu.switchboard.App
import com.aiyu.switchboard.net.Cmd
import com.aiyu.switchboard.net.Link
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** One-tap actions for the tiles and the widget: they act on the computer last used. */
enum class Quick(val tileLabel: String) {
    Mute("Mute computer"),
    PlayPause("Play or pause"),
    Lock("Lock computer"),
}

object QuickActions {
    /** Runs [kind] on the selected computer and says what happened, for a toast. */
    suspend fun run(context: Context, kind: Quick): String {
        val connections = (context.applicationContext as App).connections
        val data = connections.vault.data.value
        val host = data.hosts.find { it.id == data.selected } ?: data.hosts.firstOrNull() ?: return "No computer is paired yet."
        val foreground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        connections.start()
        try {
            val conn = withTimeoutOrNull(5_000) { connections.live.first { host.id in it }[host.id] } ?: return "Couldn't reach ${host.name}."
            if (withTimeoutOrNull(8_000) { conn.link.first { it is Link.Online } } == null) return "Couldn't reach ${host.name}."
            // The first state push comes right behind the welcome; mute needs it to know which way to flip.
            if (kind == Quick.Mute) withTimeoutOrNull(3_000) { conn.state.first { it != null } }
            val result = when (kind) {
                Quick.Mute -> {
                    val muted = conn.state.value?.volume?.muted ?: false
                    conn.send(Cmd.setMute(!muted)).also { if (it.ok) return if (muted) "${host.name} unmuted." else "${host.name} muted." }
                }
                Quick.PlayPause -> conn.send(Cmd.media("play_pause")).also { if (it.ok) return "Play or pause sent to ${host.name}." }
                Quick.Lock -> conn.send(Cmd.power("lock")).also { if (it.ok) return "${host.name} locked." }
            }
            return result.error ?: "That did not work."
        } finally {
            // Let the connection go again unless the app is open or files are still moving.
            if (!foreground) {
                delay(1_500)
                if (!connections.transfers.busy.value) connections.stop()
            }
        }
    }
}
