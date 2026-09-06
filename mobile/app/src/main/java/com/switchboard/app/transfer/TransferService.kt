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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /**
     * Posting a notification is four binder round trips to the system server —
     * one per PendingIntent plus the notify itself. The transfer flow ticks
     * four times a second per file, so with several in flight that was over
     * sixty synchronous IPCs a second on the main thread, which is what froze
     * the UI while bytes were moving. The intents are built once and the repost
     * is rate-limited to [NOTIFY_INTERVAL_MS]; a status change still goes
     * straight through, because that is when the action buttons change.
     */
    private val intents = mutableMapOf<String, PendingIntent>()
    private var lastPostAt = 0L
    private var lastPostKey = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        engine = TransferEngine.get(this)
        preferences = TransferPreferences.get(this)
        createChannels()

        // Foreground status must be claimed before the first transfer tick, or
        // a service started while the app is backgrounded is killed for not
        // posting in time.
        startForegroundCompat(buildProgressNotification(null))

        scope.launch {
            engine.transfers.collect { transfers ->
                val active = transfers.firstOrNull { !TransferStatus.isTerminal(it.status) }
                // `announced` is only ever read and written here, on the main
                // thread, so the set needs no synchronisation of its own.
                val finished = transfers
                    .filter { TransferStatus.isTerminal(it.status) && announced.add(it.transferId) }

                if (active == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                val repost = active != null && dueForRepost(active)
                if (finished.isEmpty() && !repost) return@collect

                // Building and posting are the expensive half and neither needs
                // the main thread. The collector stays on it only for the
                // service lifecycle calls above.
                withContext(Dispatchers.Default) {
                    val manager = NotificationManagerCompat.from(this@TransferService)
                    finished.forEach { notifyTerminal(manager, it) }
                    if (repost) manager.postOrSkip(PROGRESS_ID, buildProgressNotification(active))
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

    /**
     * Whether the ongoing notification is worth reposting yet.
     *
     * A progress bar redrawn four times a second is not four times as useful as
     * one redrawn once, and the platform coalesces rapid posts anyway — the
     * cost of the ones it drops is paid before it ever sees them.
     */
    private fun dueForRepost(progress: FileProgress): Boolean {
        val key = progress.transferId + progress.status
        val now = android.os.SystemClock.uptimeMillis()
        if (key == lastPostKey && now - lastPostAt < NOTIFY_INTERVAL_MS) return false
        lastPostKey = key
        lastPostAt = now
        return true
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

    private fun notifyTerminal(manager: NotificationManagerCompat, progress: FileProgress) {
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

        manager.postOrSkip(progress.transferId.hashCode(), notification)
    }

    /**
     * startForeground posts the first notification whatever the user has
     * granted; every later post needs POST_NOTIFICATIONS and throws without it
     * rather than no-opping. Losing the service to that would fail the transfer
     * it exists to protect, and the notification is the part the user has
     * already chosen not to see.
     */
    private fun NotificationManagerCompat.postOrSkip(id: Int, notification: Notification) {
        try {
            notify(id, notification)
        } catch (_: SecurityException) {
        }
    }

    /**
     * Resolving a PendingIntent is a binder call into the system server, so the
     * handful this service needs are resolved once and reused rather than on
     * every progress tick.
     */
    private fun openApp(): PendingIntent = intents.getOrPut("open") {
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Each action gets its own request code: two PendingIntents that differ
     * only in their extras are the same intent to the system, so pause and
     * cancel would otherwise collapse into one.
     */
    private fun controlIntent(transferId: String, control: String): PendingIntent =
        intents.getOrPut(transferId + control) {
            PendingIntent.getService(
                this,
                (transferId + control).hashCode(),
                Intent(this, TransferService::class.java)
                    .putExtra(EXTRA_TRANSFER_ID, transferId)
                    .putExtra(EXTRA_CONTROL, control),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

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

        /** Floor on how often the ongoing notification is reposted. */
        private const val NOTIFY_INTERVAL_MS = 1_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, TransferService::class.java))
        }
    }
}
