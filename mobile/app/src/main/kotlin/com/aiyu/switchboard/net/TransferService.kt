package com.aiyu.switchboard.net

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
import androidx.core.content.ContextCompat
import com.aiyu.switchboard.MainActivity

/**
 * Keeps the app, and so the connection, alive while files are moving, with a
 * notification that says how far along they are.
 */
class TransferService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(TEXT) ?: "Moving files"
        val percent = intent?.getIntExtra(PERCENT, -1) ?: -1
        val n = notification(this, text, percent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, n)
        return START_NOT_STICKY
    }

    companion object {
        private const val ID = 4207
        private const val CHANNEL = "transfers"
        private const val TEXT = "text"
        private const val PERCENT = "percent"
        private var shown: Pair<String, Int>? = null
        private var shownAt = 0L

        private fun notification(context: Context, text: String, percent: Int): Notification {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) nm.createNotificationChannel(NotificationChannel(CHANNEL, "File transfers", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("Switchboard")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .setProgress(100, percent.coerceAtLeast(0), percent < 0)
                .build()
        }

        /** Starts or refreshes the notification; refreshes are at most one a second. */
        fun show(context: Context, live: List<Transfer>) {
            val total = live.sumOf { it.total }
            val done = live.sumOf { it.done }
            val percent = if (total > 0) (done * 100 / total).toInt() else -1
            val paused = live.any { it.state == Transfer.State.Paused }
            val text = when {
                paused -> "Waiting for the computer…"
                live.size == 1 -> (if (live[0].upload) "Sending " else "Receiving ") + live[0].name
                else -> "Moving ${live.size} files"
            }
            val key = text to percent
            val now = System.currentTimeMillis()
            if (key == shown || (shown?.first == text && now - shownAt < 1000)) return
            shown = key
            shownAt = now
            // Starting a service from the background can be refused; the transfer carries on regardless.
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, TransferService::class.java).putExtra(TEXT, text).putExtra(PERCENT, percent))
            }
        }

        fun stop(context: Context) {
            if (shown == null) return
            shown = null
            context.stopService(Intent(context, TransferService::class.java))
        }
    }
}
