package com.switchboard.app.mirror

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.switchboard.app.net.MirrorApp
import com.switchboard.app.net.MirrorApps
import com.switchboard.app.net.MirrorFrame
import com.switchboard.app.net.MirrorInput
import com.switchboard.app.net.MirrorLaunch
import com.switchboard.app.net.MirrorSettings
import com.switchboard.app.net.MirrorState
import com.switchboard.app.net.ScreenActions
import com.switchboard.app.net.SwitchboardJson
import com.switchboard.app.net.WirePayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * This phone's screen, shared to the desktop.
 *
 * The parts are split by what Android lets each one do. Capture needs the
 * user's consent through the system's own prompt ([MirrorConsentActivity]) and
 * a media-projection foreground service to hold it ([MirrorService]). Acting on
 * the desktop's clicks needs an accessibility service ([MirrorControlService]),
 * which only the user can switch on, in system settings. This controller holds
 * what the desktop asked for and what is actually running, and is the one
 * place the desktop's `mirror.*` commands arrive.
 *
 * Capture and control are independent on purpose: a user who never enables the
 * accessibility service still gets a working, view-only mirror.
 */
class MirrorController private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(MirrorState())
    val state: StateFlow<MirrorState> = _state.asStateFlow()

    @Volatile
    var settings = MirrorSettings()
        private set

    private var send: ((String, WirePayload?, ByteArray?) -> Unit)? = null
    private var queuedBytes: () -> Long = { 0L }

    private val sequence = AtomicLong(0)

    /** Set after a dropped frame: deltas are useless until the next keyframe. */
    @Volatile
    private var needKey = false

    // ---- Session wiring ----

    fun bind(send: (String, WirePayload?, ByteArray?) -> Unit, queuedBytes: () -> Long) {
        this.send = send
        this.queuedBytes = queuedBytes
        // The desktop learns the state of a mirror that outlived a reconnect.
        publish(_state.value)
    }

    fun unbind() {
        send = null
        // Nobody left to show the screen to. Holding the projection would keep
        // the cast indicator lit and the encoder running for nothing.
        stop()
    }

    /** A `mirror.*` command from the desktop. */
    fun onCommand(action: String, payload: JsonElement?) {
        when (action) {
            ScreenActions.MIRROR_START -> {
                payload?.let {
                    settings = runCatching {
                        SwitchboardJson.decodeFromJsonElement(MirrorSettings.serializer(), it)
                    }.getOrDefault(MirrorSettings())
                }
                request()
            }

            ScreenActions.MIRROR_STOP -> stop()

            ScreenActions.MIRROR_KEYFRAME -> MirrorService.active?.requestKeyFrame()

            ScreenActions.MIRROR_INPUT -> payload?.let {
                val input = runCatching {
                    SwitchboardJson.decodeFromJsonElement(MirrorInput.serializer(), it)
                }.getOrNull() ?: return
                val control = MirrorControlService.instance
                if (control == null) {
                    // The desktop shows "view only" from this; repeating it
                    // on every click keeps that honest if the service died.
                    publish(_state.value.copy(control = false))
                } else {
                    control.perform(input)
                }
            }

            ScreenActions.MIRROR_APPS -> scope.launch {
                val apps = runCatching { loadApps() }
                    .onFailure { Log.w(TAG, "listing apps failed", it) }
                    .getOrDefault(emptyList())
                send?.invoke(ScreenActions.MIRROR_APPS, MirrorApps(apps), null)
            }

            ScreenActions.MIRROR_LAUNCH -> payload?.let {
                val launch = runCatching {
                    SwitchboardJson.decodeFromJsonElement(MirrorLaunch.serializer(), it)
                }.getOrNull() ?: return
                launch(launch.`package`)
            }
        }
    }

    // ---- Control ----

    /**
     * Starts sharing, or re-applies settings to a share already running.
     * Starting always goes through Android's consent prompt; nothing can skip
     * it, and the desktop is told it is waiting on the user.
     */
    fun request() {
        if (MirrorService.active != null) {
            MirrorService.active?.reconfigure()
            return
        }
        publish(MirrorState(awaitingConsent = true, control = controlEnabled, settings = settings))
        askForConsent()
    }

    fun stop() {
        MirrorService.stop(context)
        cancelConsentNotification()
        if (_state.value.streaming || _state.value.awaitingConsent) {
            publish(MirrorState(control = controlEnabled, settings = settings))
        }
    }

    val controlEnabled: Boolean get() = MirrorControlService.instance != null

    // ---- Called by the consent activity, the service and the accessibility service ----

    internal fun onConsent(granted: Boolean, resultCode: Int, data: Intent?) {
        cancelConsentNotification()
        if (!granted || data == null) {
            publish(
                MirrorState(
                    control = controlEnabled,
                    settings = settings,
                    error = "Screen sharing was declined on the phone"
                )
            )
            return
        }
        MirrorService.start(context, resultCode, data)
    }

    internal fun onStreaming(width: Int, height: Int) {
        sequence.set(0)
        needKey = false
        publish(
            MirrorState(
                streaming = true,
                width = width,
                height = height,
                control = controlEnabled,
                foreground = MirrorControlService.foreground,
                settings = settings
            )
        )
    }

    internal fun onStopped(error: String? = null) {
        publish(MirrorState(control = controlEnabled, settings = settings, error = error))
    }

    internal fun onControlChanged() = publish(_state.value.copy(control = controlEnabled))

    internal fun onForeground(packageName: String) {
        if (_state.value.foreground == packageName) return
        publish(_state.value.copy(foreground = packageName))
    }

    /**
     * One encoded access unit from [MirrorService].
     *
     * A slow link must make the desktop skip ahead, not fall behind: OkHttp
     * queues everything it is handed and closes the socket outright past
     * 16 MiB. So once the queue is deep, deltas are dropped and a keyframe is
     * asked for, which the desktop can resume from cleanly.
     */
    internal fun onUnit(unit: ByteArray, key: Boolean, presentationTimeUs: Long, width: Int, height: Int) {
        val sink = send ?: return
        if (!key) {
            if (needKey) return
            if (queuedBytes() > MAX_QUEUED_BYTES) {
                needKey = true
                MirrorService.active?.requestKeyFrame()
                return
            }
        }
        needKey = false
        val meta = MirrorFrame(
            seq = sequence.incrementAndGet(),
            width = width,
            height = height,
            ts = presentationTimeUs,
            key = key
        )
        sink(ScreenActions.MIRROR_FRAME, meta, unit)
    }

    private fun publish(state: MirrorState) {
        _state.value = state
        send?.invoke(ScreenActions.MIRROR_STATE, state, null)
    }

    // ---- Consent ----

    /**
     * Brings up Android's screen-capture prompt.
     *
     * An app in the background may not start activities, with exceptions; one
     * is holding a bound accessibility service, which is why the launch goes
     * through it when it is on. Either way a notification is posted too, so a
     * request the platform blocked is one tap away rather than silently lost.
     */
    private fun askForConsent() {
        val intent = Intent(context, MirrorConsentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        val launcher: Context = MirrorControlService.instance ?: context
        runCatching { launcher.startActivity(intent) }
            .onFailure { Log.i(TAG, "consent prompt could not open directly", it) }
        postConsentNotification(intent)
    }

    private fun postConsentNotification(intent: Intent) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                REQUEST_CHANNEL,
                "Screen sharing requests",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Shown when a desktop asks to see this phone's screen" }
        )
        val pending = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, REQUEST_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Your desktop wants to see this screen")
            .setContentText("Tap to choose whether to share it")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()
        runCatching { manager.notify(REQUEST_NOTIFICATION, notification) }
    }

    private fun cancelConsentNotification() {
        context.getSystemService(NotificationManager::class.java).cancel(REQUEST_NOTIFICATION)
    }

    // ---- Apps ----

    /** The launcher's list: everything a user could open from the home screen. */
    private fun loadApps(): List<MirrorApp> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0)
            .asSequence()
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { info ->
                MirrorApp(
                    `package` = info.activityInfo.packageName,
                    label = info.loadLabel(pm).toString(),
                    icon = runCatching { encodeIcon(info.loadIcon(pm).toBitmap(ICON_PX, ICON_PX)) }.getOrNull()
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    private fun encodeIcon(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * Brings an app to the front, which is what the desktop shows next.
     *
     * Started from the accessibility service when it is on, because that is
     * what exempts the launch from Android's block on background apps opening
     * activities; without it the launch works only while Switchboard itself
     * is on screen.
     */
    fun launch(packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        if (intent == null) {
            publish(_state.value.copy(error = "That app is no longer installed"))
            return
        }
        val launcher: Context = MirrorControlService.instance ?: context
        runCatching { launcher.startActivity(intent) }
            .onFailure {
                Log.w(TAG, "launch $packageName refused", it)
                publish(_state.value.copy(error = "Android blocked opening the app; turn on desktop control"))
            }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    companion object {
        private const val TAG = "MirrorController"
        private const val REQUEST_CHANNEL = "mirror.request"
        private const val REQUEST_NOTIFICATION = 4310
        private const val ICON_PX = 72

        /** A second or so of video at the default bitrate. */
        private const val MAX_QUEUED_BYTES = 1L shl 20

        @Volatile
        private var instance: MirrorController? = null

        fun get(context: Context): MirrorController = instance ?: synchronized(this) {
            instance ?: MirrorController(context.applicationContext).also { instance = it }
        }
    }
}
