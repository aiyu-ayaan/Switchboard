package com.switchboard.app.transfer

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
import com.switchboard.app.MainActivity
import com.switchboard.app.data.TransferPreferences
import com.switchboard.app.net.Control
import com.switchboard.app.net.Direction
import com.switchboard.app.net.FileProgress
import com.switchboard.app.net.TransferStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the process alive for the duration of a transfer.
 *
 * The socket and the transfer loops live in [TransferEngine]; this service
 * owns nothing but the notification. Android will freeze or kill a backgrounded
 * process mid-transfer without a foreground service, so its only job is to
 * exist while bytes are moving and to get out of the way the moment they stop.
 */
class TransferService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var engine: TransferEngine
    private lateinit var preferences: TransferPreferences

    /** Terminal states are notified once; the flow re-emits on every progress tick. */
    private val announced = mutableSetOf<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        engine = TransferEngine.get(this)
        preferences = TransferPreferences(this)
        createChannels()

        // Foreground status must be claimed before the first transfer tick, or
        // a service started while the app is backgrounded is killed for not
        // posting in time.
        startForegroundCompat(buildProgressNotification(null))

        scope.launch {
            engine.transfers.collectLatest { transfers ->
                val active = transfers.firstOrNull { !TransferStatus.isTerminal(it.status) }
                transfers.filter { TransferStatus.isTerminal(it.status) && announced.add(it.transferId) }
                    .forEach(::notifyTerminal)

                if (active == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    NotificationManagerCompat.from(this@TransferService)
                        .notify(PROGRESS_ID, buildProgressNotification(active))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val transferId = intent?.getStringExtra(EXTRA_TRANSFER_ID)
        val control = intent?.getStringExtra(EXTRA_CONTROL)
        if (transferId != null && control != null) engine.control(transferId, control)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(PROGRESS_ID, notification)
        }
    }

    private fun buildProgressNotification(progress: FileProgress?): Notification {
        val percent = ((progress?.fraction ?: 0f) * 100).toInt()
        val unit = preferences.config.value.rateUnit
        val verb = if (progress?.direction == Direction.DOWNLOAD) "Receiving" else "Sending"
        val paused = progress?.status == TransferStatus.PAUSED

        val builder = NotificationCompat.Builder(this, CHANNEL_ACTIVE)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(progress?.name ?: "Preparing transfer")
            .setContentText(
                when {
                    progress == null -> "Waiting for the desktop"
                    paused -> "Paused - $percent%"
                    else -> "$verb - $percent% - ${TransferMath.formatRate(progress.bytesPerSec, unit)}"
                }
            )
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            // The expressive wavy bar is drawn by the platform for this style
            // on releases that support it, and degrades to the plain
            // determinate bar everywhere else without a second code path.
            .setStyle(NotificationCompat.ProgressStyle().setProgress(percent))

        if (progress != null) {
            builder.addAction(
                if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (paused) "Resume" else "Pause",
                controlIntent(progress.transferId, if (paused) Control.RESUME else Control.PAUSE)
            )
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                controlIntent(progress.transferId, Control.CANCEL)
            )
        }
        return builder.build()
    }

    private fun notifyTerminal(progress: FileProgress) {
        val text = when (progress.status) {
            TransferStatus.COMPLETED -> TransferMath.formatBytes(progress.size) + " transferred"
            TransferStatus.CANCELLED -> "Cancelled"
            else -> progress.error.ifEmpty { "Transfer failed" }
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(
                if (progress.status == TransferStatus.COMPLETED) {
                    android.R.drawable.stat_sys_download_done
                } else {
                    android.R.drawable.stat_notify_error
                }
            )
            .setContentTitle(progress.name)
            .setContentText(text)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(this).notify(progress.transferId.hashCode(), notification)
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /**
     * Each action gets its own request code: two PendingIntents that differ
     * only in their extras are the same intent to the system, so pause and
     * cancel would otherwise collapse into one.
     */
    private fun controlIntent(transferId: String, control: String): PendingIntent = PendingIntent.getService(
        this,
        (transferId + control).hashCode(),
        Intent(this, TransferService::class.java)
            .putExtra(EXTRA_TRANSFER_ID, transferId)
            .putExtra(EXTRA_CONTROL, control),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ACTIVE, "File transfers", NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Transfer results", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    companion object {
        private const val PROGRESS_ID = 4201
        private const val CHANNEL_ACTIVE = "transfers.active"
        private const val CHANNEL_DONE = "transfers.done"
        private const val EXTRA_TRANSFER_ID = "transferId"
        private const val EXTRA_CONTROL = "control"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, TransferService::class.java))
        }
    }
}
