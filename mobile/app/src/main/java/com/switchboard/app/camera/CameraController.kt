package com.switchboard.app.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
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
 * Holds what the camera *should* be doing, separately from whether it can be
 * doing it yet.
 *
 * The desktop can ask for a stream at any moment, but CameraX needs a
 * lifecycle owner and the camera permission, and neither is available until
 * the user is looking at the camera screen. So the desktop's request is
 * recorded as intent here, and capture starts when the screen attaches — which
 * also means the screen can start a stream on its own, from the phone, without
 * a second path.
 *
 * Streaming is deliberately foreground-only. Capturing from the background
 * would need a `camera` foreground service and a permanent notification for a
 * picture nobody is looking at, and a phone propped up as a webcam is on its
 * camera screen anyway.
 */
class CameraController private constructor(private val context: Context) {

    private val streamer = CameraStreamer(context)

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

    private var owner: LifecycleOwner? = null
    private var send: ((String, WirePayload?, ByteArray?) -> Unit)? = null

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

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
                val updated = SwitchboardJson.decodeFromJsonElement(CameraSettings.serializer(), it)
                _settings.value = updated
                val activeOwner = owner ?: androidx.lifecycle.ProcessLifecycleOwner.get()
                streamer.apply(activeOwner, updated)
            }
        }
    }

    // ---- Screen wiring ----

    /** Called by the camera screen once it has a lifecycle and permission. */
    fun attach(owner: LifecycleOwner) {
        this.owner = owner
        if (_requested.value && !streamer.isStreaming) startIfPossible()
    }

    fun detach(owner: LifecycleOwner) {
        if (this.owner !== owner) return
        this.owner = null
        // If capture is actively streaming, keep it alive in the background
        // via CameraService rather than shutting down the camera.
        if (!streamer.isStreaming) {
            streamer.stop()
            CameraService.stop(context)
            publish(_state.value.copy(streaming = false))
        }
    }

    /** Starts capture, or records the intent until the screen can honour it. */
    fun request() {
        _requested.value = true
        startIfPossible()
    }

    fun stop() {
        _requested.value = false
        _pendingFromDesktop.value = false
        streamer.stop()
        CameraService.stop(context)
        publish(CameraState(streaming = false, settings = _settings.value))
    }

    fun update(settings: CameraSettings) {
        _settings.value = settings
        val activeOwner = owner ?: androidx.lifecycle.ProcessLifecycleOwner.get()
        streamer.apply(activeOwner, settings)
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
        // Bind to ProcessLifecycleOwner so camera capture persists across
        // screen locks, display-off lock mode, and backgrounding.
        val lifecycleOwner = androidx.lifecycle.ProcessLifecycleOwner.get()
        CameraService.start(context)
        streamer.start(
            owner = lifecycleOwner,
            settings = _settings.value,
            sink = { meta, jpeg -> send?.invoke(Actions.CAMERA_FRAME, meta, jpeg) },
            onState = { publish(it) }
        )
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
