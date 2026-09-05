package com.switchboard.app.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.switchboard.app.net.Actions
import com.switchboard.app.net.CameraSettings
import com.switchboard.app.net.CameraState
import com.switchboard.app.net.SwitchboardJson
import com.switchboard.app.net.WirePayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement

/**
 * Holds what the camera *should* be doing, and owns the lifecycle it is bound
 * to.
 *
 * CameraX will only keep a camera open while some [LifecycleOwner] is at least
 * STARTED, and it closes the camera the moment that owner stops. Every owner
 * the app already had is the wrong shape for a webcam: an Activity or a screen
 * stops when the user leaves it, and `ProcessLifecycleOwner` stops when the app
 * is backgrounded or the phone is locked — which is precisely when a phone
 * propped up as a webcam is most useful, and why the stream used to die a
 * moment after the app was minimised.
 *
 * So this owns a lifecycle of its own, held RESUMED for exactly as long as the
 * stream is meant to be running, with [CameraService] keeping the process alive
 * and the user informed while it is. Nothing about where the user is in the app
 * can end the stream; only stopping it can.
 */
class CameraController private constructor(private val context: Context) : LifecycleOwner {

    private val streamer = CameraStreamer(context)

    /**
     * Driven only from [onMain]: [LifecycleRegistry] asserts the main thread,
     * and so does `bindToLifecycle`. Commands arrive on the WebSocket's thread,
     * where CameraX calls used to fail silently inside a `runCatching`.
     */
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    private val mainThread = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(CameraState())
    val state: StateFlow<CameraState> = _state.asStateFlow()

    private val _settings = MutableStateFlow(CameraSettings())
    val settings: StateFlow<CameraSettings> = _settings.asStateFlow()

    /** True once someone — the desktop or this screen — has asked for capture. */
    private val _requested = MutableStateFlow(false)
    val requested: StateFlow<Boolean> = _requested.asStateFlow()

    /**
     * Set while the desktop is waiting for a stream the app cannot start yet.
     * The screen shows it as a prompt rather than leaving the desktop waiting
     * on a phone that looks idle.
     */
    private val _pendingFromDesktop = MutableStateFlow(false)
    val pendingFromDesktop: StateFlow<Boolean> = _pendingFromDesktop.asStateFlow()

    private var send: ((String, WirePayload?, ByteArray?) -> Unit)? = null

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainThread.post(block)
    }

    // ---- Session wiring ----

    fun bind(send: (String, WirePayload?, ByteArray?) -> Unit) {
        this.send = send
    }

    fun unbind() {
        send = null
        // The frames have nowhere to go, and an open camera costs battery and
        // a privacy indicator for nothing.
        stop()
    }

    /** Applies a `camera.*` command from the desktop. */
    fun onCommand(action: String, payload: JsonElement?) {
        when (action) {
            Actions.CAMERA_START -> {
                payload?.let {
                    _settings.value = SwitchboardJson.decodeFromJsonElement(
                        CameraSettings.serializer(), it
                    )
                }
                request()
            }

            Actions.CAMERA_STOP -> stop()

            Actions.CAMERA_CONTROL -> payload?.let {
                update(SwitchboardJson.decodeFromJsonElement(CameraSettings.serializer(), it))
            }
        }
    }

    // ---- Control ----

    /** Starts capture, or records the intent until permission allows it. */
    fun request() {
        _requested.value = true
        startIfPossible()
    }

    fun stop() {
        _requested.value = false
        _pendingFromDesktop.value = false
        onMain {
            streamer.stop()
            // Below STARTED, so CameraX releases the camera and the privacy
            // indicator goes out. Never DESTROYED: that is terminal, and this
            // controller outlives every stream it runs.
            registry.currentState = Lifecycle.State.CREATED
            CameraService.stop(context)
        }
        publish(CameraState(streaming = false, settings = _settings.value))
    }

    fun update(settings: CameraSettings) {
        _settings.value = settings
        onMain { streamer.apply(this, settings) }
        publish(_state.value.copy(settings = settings))
    }

    private fun startIfPossible() {
        if (!hasPermission) {
            _pendingFromDesktop.value = true
            publish(
                CameraState(
                    streaming = false,
                    settings = _settings.value,
                    error = "Camera permission not granted on the phone"
                )
            )
            return
        }
        _pendingFromDesktop.value = false

        onMain {
            // A start for a stream already running is a settings change: the
            // desktop sends the whole block with camera.start, and rebinding
            // would blank the picture for no reason.
            if (streamer.isStreaming) {
                streamer.apply(this, _settings.value)
                return@onMain
            }

            // The service first: Android requires a camera foreground service
            // to already be running before the camera is opened, and starting
            // it afterwards is too late.
            if (!CameraService.start(context)) {
                // Refused because the app is in the background, which the
                // platform does not allow for a camera service. The desktop's
                // request stands; the screen shows it as waiting for a tap.
                _pendingFromDesktop.value = true
                publish(
                    CameraState(
                        streaming = false,
                        settings = _settings.value,
                        error = "Open Switchboard on the phone to start the camera"
                    )
                )
                return@onMain
            }

            registry.currentState = Lifecycle.State.RESUMED
            streamer.start(
                owner = this,
                settings = _settings.value,
                sink = { meta, jpeg -> send?.invoke(Actions.CAMERA_FRAME, meta, jpeg) },
                onState = { publish(it) }
            )
        }
    }

    /** Mirrors state locally and to the desktop, which drives its controls from it. */
    private fun publish(state: CameraState) {
        _state.update { state }
        send?.invoke(Actions.CAMERA_STATE, state, null)
    }

    companion object {
        @Volatile
        private var instance: CameraController? = null

        fun get(context: Context): CameraController =
            instance ?: synchronized(this) {
                instance ?: CameraController(context.applicationContext).also { instance = it }
            }
    }
}
