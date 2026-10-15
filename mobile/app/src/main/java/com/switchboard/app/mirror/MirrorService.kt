package com.switchboard.app.mirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import androidx.core.app.NotificationCompat
import com.switchboard.app.MainActivity
import com.switchboard.app.camera.VideoEncoder
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Holds the screen capture: a media-projection foreground service, a virtual
 * display mirroring the screen, and the hardware H.264 encoder that display
 * draws into.
 *
 * Pixels never touch the CPU on this path. The projection renders the screen
 * straight into the encoder's input surface, exactly as the camera does, so
 * a 60 fps mirror costs about what playing a video does.
 *
 * Rotation is followed by resizing the one virtual display rather than making
 * a new one: from Android 14 a projection may create a single virtual display,
 * and a second attempt ends the projection.
 */
class MirrorService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val controller by lazy { MirrorController.get(this) }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private val encoder = VideoEncoder { unit, key, pts ->
        controller.onUnit(unit, key, pts, width, height)
    }

    @Volatile
    private var width = 0

    @Volatile
    private var height = 0

    private val displays by lazy { getSystemService(DisplayManager::class.java) }

    private val rotation = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val (w, h) = targetSize(controller.settings.maxSize)
            // Only an orientation flip matters; a refresh-rate change or a
            // brightness tweak also fires this.
            if ((w > h) != (width > height)) configure()
        }

        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            controller.stop()
            return START_NOT_STICKY
        }

        // The service type must be in force before the projection is fetched;
        // Android 14 refuses getMediaProjection from anything else.
        startForegroundCompat(buildNotification())

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (data == null || projection != null) return START_NOT_STICKY

        val manager = getSystemService(MediaProjectionManager::class.java)
        val projection = runCatching { manager.getMediaProjection(resultCode, data) }.getOrNull()
        if (projection == null) {
            controller.onStopped("Android did not grant screen capture")
            stopSelf()
            return START_NOT_STICKY
        }
        this.projection = projection
        // Registered before the virtual display, which Android 14 requires.
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // The user ended it from the status bar or the cast chip.
                main.post { shutDown(null) }
            }
        }, main)

        active = this
        displays.registerDisplayListener(rotation, main)
        configure()
        return START_NOT_STICKY
    }

    /** Re-applies the controller's settings, as when the desktop asks again. */
    fun reconfigure() = main.post { configure() }

    fun requestKeyFrame() = encoder.requestKeyFrame()

    /**
     * Sizes the encoder to the screen as it is now and points the virtual
     * display at it. Sizes are tried from the requested one down, because an
     * encoder's limits are per device and not always the obvious ones -- some
     * accept 1920 wide but not 1920 tall.
     */
    private fun configure() {
        val projection = projection ?: return
        val settings = controller.settings

        // Detach first: restarting the encoder releases the surface the
        // display is drawing into.
        display?.surface = null

        val dpi = resources.displayMetrics.densityDpi
        var surface: android.view.Surface? = null
        for (edge in listOf(settings.maxSize, 1600, 1280, 960).distinct().filter { it <= settings.maxSize }) {
            val (w, h) = targetSize(edge)
            surface = encoder.start(
                width = w,
                height = h,
                fps = settings.fps,
                bitrate = settings.bitrate,
                repeatAfterUs = REPEAT_AFTER_US,
                iFrameIntervalSeconds = 2
            )
            if (surface != null) {
                width = w
                height = h
                break
            }
        }
        if (surface == null) {
            shutDown("This phone has no video encoder that can share its screen")
            return
        }

        val existing = display
        if (existing == null) {
            display = runCatching {
                projection.createVirtualDisplay(
                    "Switchboard mirror", width, height, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface, null, main
                )
            }.onFailure { Log.w(TAG, "virtual display refused", it) }.getOrNull()
            if (display == null) {
                shutDown("Android refused to share the screen")
                return
            }
        } else {
            existing.resize(width, height, dpi)
            existing.surface = surface
        }
        controller.onStreaming(width, height)
    }

    /**
     * The screen's real size scaled so its long edge is at most [maxEdge],
     * rounded to multiples of 16 -- the macroblock size, which some encoders
     * insist on and every encoder is happiest with.
     */
    private fun targetSize(maxEdge: Int): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        displays.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        val scale = minOf(1f, maxEdge / max(w, h))
        fun align(v: Float) = max(16, ((v * scale) / 16f).roundToInt() * 16)
        return align(w) to align(h)
    }

    private fun shutDown(error: String?) {
        displays.unregisterDisplayListener(rotation)
        display?.release()
        display = null
        encoder.stop()
        projection?.let { p -> runCatching { p.stop() } }
        projection = null
        if (active === this) active = null
        controller.onStopped(error)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (projection != null || display != null) shutDown(null)
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Screen sharing", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while this phone's screen is shared to a desktop"
                setShowBadge(false)
            }
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, MirrorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Sharing your screen")
            .setContentText("Your desktop can see this phone's screen")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop sharing", stop)
            .build()
    }

    companion object {
        private const val TAG = "MirrorService"
        private const val CHANNEL_ID = "mirror.stream"
        private const val NOTIFICATION_ID = 4311
        private const val ACTION_STOP = "com.switchboard.app.mirror.STOP"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_DATA = "data"

        /**
         * Re-send the last frame after this long without a new one. A still
         * screen produces nothing at all, and a desktop window opened then
         * would wait on a keyframe that never came.
         */
        private const val REPEAT_AFTER_US = 250_000L

        @Volatile
        var active: MirrorService? = null
            private set

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, MirrorService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val service = active
            if (service != null) {
                service.main.post { service.shutDown(null) }
            } else {
                context.stopService(Intent(context, MirrorService::class.java))
            }
        }
    }
}
