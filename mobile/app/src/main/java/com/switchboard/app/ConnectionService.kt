package com.switchboard.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the process alive for as long as the user wants a desktop connected.
 *
 * The socket, the reconnect loop and every piece of session state live in
 * [SwitchboardConnection]; this service owns nothing but the notification.
 * Swiping the task away destroys the Activity, and without a foreground
 * service Android then freezes and reclaims the process -- which is what took
 * the connection down with it. Its only job is to exist while "stay connected"
 * is on, and to get out of the way the moment it is switched off.
 *
 * It is deliberately a `connectedDevice` service rather than `dataSync`:
 * dataSync is capped at a few hours a day from Android 15, which a setting
 * called "stay connected" would silently stop honouring.
 */
class ConnectionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var connection: SwitchboardConnection

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Foreground status is claimed before any other work. A service that
        // does not post within a few seconds of being started is killed, and
        // building the holder below touches the keystore.
        startForegroundCompat(buildNotification(null, false))

        // Building the holder is what restores the session after the system has
        // restarted this service on its own: its constructor redials the host
        // used last.
        connection = SwitchboardConnection.get(this)

        scope.launch {
            connection.state
                // Only the two things the notification shows. State arrives on
                // every host broadcast -- several times a second while media is
                // playing -- and reposting the same text that often is a binder
                // round trip per tick for no visible change.
                .map { it.activeHost?.hostName to (it.status == ConnectionStatus.Connected) }
                .distinctUntilChanged()
                .collect { (name, connected) ->
                    // startForeground posts the first one whatever the user has
                    // granted; a later repost without POST_NOTIFICATIONS throws
                    // instead of no-opping. The update is cosmetic, so the
                    // service must not die for a notification the user has
                    // already chosen not to see.
                    try {
                        NotificationManagerCompat.from(this@ConnectionService)
                            .notify(NOTIFICATION_ID, buildNotification(name, connected))
                    } catch (_: SecurityException) {
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Turning the setting off is what stops this service, via the
            // holder -- so the switch in Settings and the notification button
            // cannot disagree about whether always-on is on.
            connection.setAlwaysOn(false)
            return START_NOT_STICKY
        }
        // Restarted after a low-memory kill: onCreate has rebuilt the holder,
        // which redials on its own. Unless the user turned the setting off
        // while the process was gone, in which case this restart is stale and
        // the notification would outlive the thing it describes.
        if (!connection.state.value.alwaysOn) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(hostName: String?, connected: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ConnectionService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(
                if (connected && hostName != null) "Connected to $hostName" else "Switchboard"
            )
            .setContentText(
                if (connected) "Ready for controls and transfers" else "Waiting for your desktop"
            )
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Turn off", stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Desktop connection",
                // MIN rather than LOW: this notification is a permanent
                // fixture, so it belongs collapsed at the bottom of the shade
                // rather than competing with transfer progress above it.
                NotificationManager.IMPORTANCE_MIN
            ).apply { description = "Shown while Switchboard stays connected in the background." }
        )
    }

    companion object {
        private const val NOTIFICATION_ID = 4301
        private const val CHANNEL_ID = "connection.active"
        private const val ACTION_STOP = "com.switchboard.app.STOP_CONNECTION"

        /**
         * Android 12+ refuses a foreground service started from the background,
         * so a caller that is no longer visible gets a failure rather than a
         * crash. The setting stays on; the next launch starts it.
         */
        fun start(context: Context): Boolean = runCatching {
            context.startForegroundService(Intent(context, ConnectionService::class.java))
        }.isSuccess

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionService::class.java))
        }
    }
}
