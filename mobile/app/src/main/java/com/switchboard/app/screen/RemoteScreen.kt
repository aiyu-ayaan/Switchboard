package com.switchboard.app.screen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.switchboard.app.net.Actions
import com.switchboard.app.net.ButtonAction
import com.switchboard.app.net.InputButton
import com.switchboard.app.net.InputKey
import com.switchboard.app.net.InputMove
import com.switchboard.app.net.InputPoint
import com.switchboard.app.net.InputScroll
import com.switchboard.app.net.InputText
import com.switchboard.app.net.ScreenActions
import com.switchboard.app.net.ScreenDisplay
import com.switchboard.app.net.ScreenFrameMeta
import com.switchboard.app.net.ScreenSettings
import com.switchboard.app.net.ScreenState
import com.switchboard.app.net.SwitchboardJson
import com.switchboard.app.net.WirePayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The desktop's screen, as this phone sees it.
 *
 * Frames arrive as JPEGs and are decoded here, off the main thread, newest
 * only: the channel is conflated, so a phone that cannot decode as fast as the
 * desktop sends skips to the latest picture instead of falling ever further
 * behind it. Decoding alternates between two bitmaps so the one on screen is
 * never the one being written into.
 *
 * Input goes back as absolute positions ([InputPoint]) followed by the ordinary
 * air mouse button and scroll frames, so the desktop needs nothing new beyond
 * "put the pointer here".
 */
class RemoteScreen private constructor() {

    sealed interface Status {
        data object Idle : Status
        data object Starting : Status
        data object Streaming : Status
        data class Failed(val message: String) : Status
    }

    /** One decoded picture of the desktop. */
    class Picture(
        val image: ImageBitmap,
        val seq: Long,
        val screenWidth: Int,
        val screenHeight: Int
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _picture = MutableStateFlow<Picture?>(null)
    val picture: StateFlow<Picture?> = _picture.asStateFlow()

    /** The desktop pointer as fractions of the picture, or null when off it. */
    private val _cursor = MutableStateFlow<Pair<Float, Float>?>(null)
    val cursor: StateFlow<Pair<Float, Float>?> = _cursor.asStateFlow()

    /** The desktop's monitors, and which one (or "all") is being shown. */
    private val _displays = MutableStateFlow<List<ScreenDisplay>>(emptyList())
    val displays: StateFlow<List<ScreenDisplay>> = _displays.asStateFlow()

    private val _display = MutableStateFlow<String?>(null)
    val display: StateFlow<String?> = _display.asStateFlow()

    private var send: ((String, WirePayload?, ByteArray?) -> Unit)? = null
    private var settings = ScreenSettings()

    private val pending = Channel<Pair<ScreenFrameMeta, ByteArray>>(Channel.CONFLATED)
    private val buffers = arrayOfNulls<Bitmap>(2)
    private var nextBuffer = 0

    init {
        scope.launch {
            for ((meta, jpeg) in pending) decode(meta, jpeg)
        }
    }

    // ---- Session wiring ----

    fun bind(send: (String, WirePayload?, ByteArray?) -> Unit) {
        this.send = send
        // A reconnect drops the desktop's stream with the socket; asking again
        // resumes the view the user was looking at.
        if (_status.value == Status.Streaming || _status.value == Status.Starting) {
            start(settings)
        }
    }

    fun unbind() {
        send = null
        if (_status.value == Status.Streaming) _status.value = Status.Starting
    }

    fun onCommand(action: String, payload: JsonElement?, blob: ByteArray?) {
        when (action) {
            ScreenActions.SCREEN_FRAME -> {
                val meta = payload?.let {
                    SwitchboardJson.decodeFromJsonElement(ScreenFrameMeta.serializer(), it)
                } ?: return
                if (_status.value == Status.Idle) return // stopped; frames in flight
                _cursor.value = if (meta.cursorX >= 0 && meta.cursorY >= 0) {
                    meta.cursorX.toFloat() to meta.cursorY.toFloat()
                } else {
                    null
                }
                if (blob != null && blob.isNotEmpty()) pending.trySend(meta to blob)
            }

            ScreenActions.SCREEN_START, ScreenActions.SCREEN_STOP, ScreenActions.SCREEN_STATE -> {
                // An error reply carries the request's action and a message,
                // such as a desktop that cannot capture its screen.
                val message = (payload as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                if (message != null) {
                    _status.value = Status.Failed(message)
                    return
                }
                val state = payload?.let {
                    runCatching {
                        SwitchboardJson.decodeFromJsonElement(ScreenState.serializer(), it)
                    }.getOrNull()
                }
                if (state != null && state.displays.isNotEmpty()) _displays.value = state.displays
                if (state?.display != null) _display.value = state.display
                when {
                    state?.error != null -> _status.value = Status.Failed(state.error)
                    state?.streaming == true && _status.value == Status.Starting -> Unit
                    action == ScreenActions.SCREEN_STATE && state?.streaming == false ->
                        _status.value = Status.Failed("The desktop stopped sharing")
                }
            }
        }
    }

    // ---- Control ----

    fun start(settings: ScreenSettings) {
        this.settings = settings
        if (_status.value !is Status.Streaming) _status.value = Status.Starting
        send?.invoke(ScreenActions.SCREEN_START, settings, null)
    }

    fun stop() {
        _status.value = Status.Idle
        send?.invoke(ScreenActions.SCREEN_STOP, null, null)
        _picture.value = null
        _cursor.value = null
        buffers.fill(null)
    }

    fun pointTo(x: Float, y: Float) =
        send?.invoke(ScreenActions.INPUT_POINT, InputPoint(x.toDouble(), y.toDouble()), null)

    /** Relative motion in desktop pixels, for trackpad mode. */
    fun move(dx: Float, dy: Float) =
        send?.invoke(Actions.INPUT_MOVE, InputMove(dx.toDouble(), dy.toDouble()), null)

    fun button(button: String, action: String) =
        send?.invoke(Actions.INPUT_BUTTON, InputButton(button, action), null)

    fun click(button: String) = button(button, ButtonAction.CLICK)

    fun scroll(dx: Double, dy: Double) =
        send?.invoke(Actions.INPUT_SCROLL, InputScroll(dx, dy), null)

    fun key(name: String) = send?.invoke(ScreenActions.INPUT_KEY, InputKey(name), null)

    fun text(text: String) {
        if (text.isNotEmpty()) send?.invoke(Actions.INPUT_TEXT, InputText(text), null)
    }

    // ---- Decoding ----

    private fun decode(meta: ScreenFrameMeta, jpeg: ByteArray) {
        val index = nextBuffer
        val reuse = buffers[index]?.takeIf {
            it.width == meta.width && it.height == meta.height && it.isMutable
        }
        val options = BitmapFactory.Options().apply {
            inMutable = true
            inBitmap = reuse
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = runCatching { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options) }
            .recoverCatching {
                // inBitmap is a hint some decoders refuse; a fresh one always works.
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            }
            .onFailure { Log.w(TAG, "undecodable frame ${meta.seq}", it) }
            .getOrNull() ?: return

        buffers[index] = bitmap
        nextBuffer = 1 - index
        if (_status.value == Status.Idle) return
        _status.value = Status.Streaming
        _picture.value = Picture(bitmap.asImageBitmap(), meta.seq, meta.screenWidth, meta.screenHeight)
    }

    companion object {
        private const val TAG = "RemoteScreen"

        @Volatile
        private var instance: RemoteScreen? = null

        fun get(): RemoteScreen = instance ?: synchronized(this) {
            instance ?: RemoteScreen().also { instance = it }
        }
    }
}
