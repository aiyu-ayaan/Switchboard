package com.switchboard.app.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Wire types mirroring backend/internal/protocol. */
object Actions {
    const val DISPLAY_LIST = "display.list"
    const val DISPLAY_BRIGHTNESS = "display.brightness.set"
    const val DISPLAY_CONTRAST = "display.contrast.set"
    const val VOLUME_SET = "system.volume.set"
    const val MIXER_LIST = "audio.mixer.list"
    const val MIXER_SET = "audio.mixer.set"
    const val OUTPUT_LIST = "audio.output.list"
    const val OUTPUT_SET = "audio.output.set"
    const val MEDIA_COMMAND = "media.playback.command"
    const val MEDIA_ARTWORK = "media.artwork"
    const val HOST_STATE = "host.state"
    const val SYSTEM_LOCK = "system.lock"

    // Remote unlock. The host issues a nonce, the phone signs it with a key
    // its keystore only releases behind a fingerprint, and the host checks the
    // signature. See docs/docs/remote-unlock.md.
    const val UNLOCK_ENROLL = "system.unlock.enroll"
    const val UNLOCK_CHALLENGE = "system.unlock.challenge"
    const val SYSTEM_UNLOCK = "system.unlock"

    // Air mouse. This app owns the gesture vocabulary; the desktop only
    // injects the intents resolved here.
    const val INPUT_MOVE = "input.move"
    const val INPUT_BUTTON = "input.button"
    const val INPUT_SCROLL = "input.scroll"
    const val INPUT_GESTURE = "input.gesture"

    // Wi-Fi camera. This device captures; the desktop is the sink, so
    // start/stop/control arrive here and frames go the other way.
    const val CAMERA_START = "camera.start"
    const val CAMERA_STOP = "camera.stop"
    const val CAMERA_CONTROL = "camera.control"
    const val CAMERA_FRAME = "camera.frame"
    const val CAMERA_STATE = "camera.state"

    // File transfer. Both directions use the same frames; the side holding
    // the file sends the offer and the receiver paces it with acks.
    const val FILE_OFFER = "file.offer"
    const val FILE_ACCEPT = "file.accept"
    const val FILE_CHUNK = "file.chunk"
    const val FILE_ACK = "file.ack"
    const val FILE_COMPLETE = "file.complete"
    const val FILE_CONTROL = "file.control"
    const val FILE_PROGRESS = "file.progress"
    const val FILE_LIST = "file.list"

    // Stream Deck Neo.
    const val DECK_GET = "deck.get"
    const val DECK_SET = "deck.set"
    const val DECK_ACTION = "deck.action"
    const val DECK_STATE = "deck.state"
    const val SYSTEM_APPS = "system.apps"
}

/**
 * Payload slice carried by one [Actions.FILE_CHUNK] frame, matching
 * `protocol.ChunkSize` on the host. Chunks stream off storage, so a multi-GB
 * file never sits in memory.
 */
const val CHUNK_SIZE = 256 * 1024

/** Transfer direction, named from this device's point of view. */
object Direction {
    const val UPLOAD = "upload"     // phone -> desktop
    const val DOWNLOAD = "download" // desktop -> phone
}

/** Transfer lifecycle states reported in [FileProgress.status]. */
object TransferStatus {
    const val PENDING = "pending"
    const val ACTIVE = "active"
    const val PAUSED = "paused"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"

    fun isTerminal(status: String): Boolean =
        status == COMPLETED || status == FAILED || status == CANCELLED
}

/** Actions carried by [Actions.FILE_CONTROL]. */
object Control {
    const val PAUSE = "pause"
    const val RESUME = "resume"
    const val CANCEL = "cancel"
}

/**
 * A payload that may be sent to the host.
 *
 * Sealed on purpose: [SwitchboardClient.send] dispatches on the concrete type
 * to pick a serializer, and sealing makes that `when` exhaustive. A new payload
 * type that nobody taught the encoder about is then a compile error rather than
 * an IllegalArgumentException the moment a user taps the control.
 */
/** Registers this phone's biometric-gated public key, base64 PKIX DER. */
@Serializable
data class UnlockEnroll(val publicKey: String) : WirePayload

/** The nonce the host wants signed, base64. Single use. */
@Serializable
data class UnlockChallenge(val challenge: String) : WirePayload

/** A nonce and its ASN.1 ECDSA signature, both base64. */
@Serializable
data class UnlockProof(val challenge: String, val signature: String) : WirePayload

/**
 * The exact bytes signed for an unlock, mirroring protocol.UnlockMessage on
 * the host. The label keeps an unlock signature from ever reading as one made
 * for another purpose, and the daemon ID stops a proof captured on one desktop
 * from opening a different one. Both are NUL-separated, which neither the
 * label nor a UUID contains.
 */
fun unlockMessage(daemonId: String, challenge: ByteArray): ByteArray =
    "switchboard-unlock-v1".toByteArray() + 0 + daemonId.toByteArray() + 0 + challenge

sealed interface WirePayload

val SwitchboardJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
data class Envelope(
    val id: String,
    val type: String,
    val action: String,
    val payload: JsonElement? = null,
    val timestamp: Long
)

@Serializable
data class Display(
    val id: String,
    val name: String,
    val internal: Boolean = false,
    val brightness: Int = 0,
    val minBrightness: Int = 0,
    val maxBrightness: Int = 100,
    val hasContrast: Boolean = false,
    val contrast: Int = 0,
    val minContrast: Int = 0,
    val maxContrast: Int = 100
) {
    /** Position within the panel's own range, for the slider readout. */
    val brightnessPercent: Int
        get() = percent(brightness, minBrightness, maxBrightness)

    val contrastPercent: Int
        get() = percent(contrast, minContrast, maxContrast)

    private fun percent(value: Int, min: Int, max: Int): Int =
        if (max > min) ((value - min) * 100f / (max - min)).toInt() else 0
}

@Serializable
data class Volume(val level: Int = 0, val muted: Boolean = false) : WirePayload

/**
 * One program's entry in the host mixer.
 *
 * [id] is the OS session identifier, not the process: a browser spans several
 * processes behind one mixer entry, and the PID that opened the stream does
 * not survive a restart. [active] is false for a session holding its entry
 * without playing — hiding those would make a paused player vanish mid-use.
 */
@Serializable
data class AudioSession(
    val id: String,
    val name: String = "",
    val pid: Int = 0,
    val level: Int = 0,
    val muted: Boolean = false,
    val active: Boolean = false
)

/**
 * One output endpoint the desktop can play through.
 *
 * [id] is the OS endpoint identifier, which survives a reboot and a re-plug;
 * the position in the list does not, so selection is always sent by id.
 */
@Serializable
data class AudioDevice(
    val id: String,
    val name: String = "",
    val default: Boolean = false
)

@Serializable
data class OutputSet(val deviceId: String) : WirePayload

@Serializable
data class MixerSet(val sessionId: String, val level: Int, val muted: Boolean = false) : WirePayload

/** Playback states the host reports in [MediaState.status]. */
object Playback {
    const val STOPPED = "stopped"
    const val PLAYING = "playing"
    const val PAUSED = "paused"
}

/**
 * What the desktop is playing right now, read from its OS media session.
 *
 * [artworkId] identifies the cover art without carrying it: the image is
 * fetched once per track over [Actions.MEDIA_ARTWORK] and cached against this
 * value, so a slider drag does not drag album art across the network with it.
 */
@Serializable
data class MediaState(
    val active: Boolean = false,
    val status: String = Playback.STOPPED,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val source: String = "",
    val artworkId: String = ""
) {
    val isPlaying: Boolean get() = status == Playback.PLAYING

    /** A one-line summary for the home list. */
    val summary: String
        get() = when {
            !active || title.isEmpty() -> "Nothing playing"
            artist.isEmpty() -> title
            else -> "$title - $artist"
        }
}

@Serializable
data class MediaArtwork(
    val artworkId: String = "",
    val mimeType: String = "",
    /** Base64 image bytes. Empty when the track has no cover art. */
    val data: String = ""
)

@Serializable
data class HostState(
    val hostName: String = "",
    val daemonId: String = "",
    val displays: List<Display> = emptyList(),
    val volume: Volume = Volume(),
    val mixer: List<AudioSession> = emptyList(),
    val outputs: List<AudioDevice> = emptyList(),
    val media: MediaState = MediaState(),
    val locked: Boolean = false,
    val capabilities: List<String> = emptyList()
)

@Serializable
data class DisplaySet(val displayId: String, val value: Int) : WirePayload

@Serializable
data class MediaCommand(val action: String) : WirePayload

// ---- Wi-Fi camera ----

/**
 * Quality presets. Resolution and JPEG quality move together because they
 * trade off against the same thing — bandwidth — and separate controls invite
 * combinations that make no sense.
 */
object CameraQuality {
    const val FULL = "full"
    const val BALANCED = "balanced"
    const val LOW = "low"
}

object CameraFacing {
    const val BACK = "back"
    const val FRONT = "front"
}

object CameraWhiteBalance {
    const val AUTO = "auto"
    const val INCANDESCENT = "incandescent"
    const val FLUORESCENT = "fluorescent"
    const val DAYLIGHT = "daylight"
    const val CLOUDY = "cloudy"
    const val SHADE = "shade"

    val all = listOf(AUTO, INCANDESCENT, FLUORESCENT, DAYLIGHT, CLOUDY, SHADE)
}

/**
 * The whole control surface, sent as one block rather than as patches: the
 * camera is reconfigured as a unit, and a partial update would need every
 * field nullable to tell "unset" from "set to zero".
 */
@Serializable
data class CameraSettings(
    val facing: String = CameraFacing.BACK,
    val quality: String = CameraQuality.FULL,
    val fps: Int = 30,
    /** Applied here before encoding, so the desktop never rotates a decoded frame. */
    val rotation: Int = 0,
    val mirror: Boolean = false,
    /** Normalised 0-1 across this lens's own range, not a ratio. */
    val zoom: Double = 0.0,
    val torch: Boolean = false,
    val autoFocus: Boolean = true,
    val focusDistance: Double = 0.0,
    val autoExposure: Boolean = true,
    val exposure: Int = 0,
    val whiteBalance: String = CameraWhiteBalance.AUTO,
    val autoFraming: Boolean = false
) : WirePayload

/**
 * What this device reports back: the settings in force plus what the lens can
 * actually honour, so the desktop hides a control the hardware lacks rather
 * than offering a dead slider.
 */
@Serializable
data class CameraState(
    val streaming: Boolean = false,
    val deviceId: String = "",
    val settings: CameraSettings = CameraSettings(),
    val width: Int = 0,
    val height: Int = 0,
    val maxZoomRatio: Double = 1.0,
    val minExposure: Int = 0,
    val maxExposure: Int = 0,
    val hasTorch: Boolean = false,
    val hasManualFocus: Boolean = false,
    val hasManualExposure: Boolean = false,
    val hasWhiteBalance: Boolean = false,
    /**
     * The white balance presets this lens will actually honour. A mode the
     * hardware does not list is silently ignored by the camera, so offering it
     * gives the user a control that does nothing.
     */
    val whiteBalanceModes: List<String> = emptyList(),
    val hasAutoFraming: Boolean = false,
    val hasFrontCamera: Boolean = false,
    /** Measured by the desktop, not here; sent as zero and overwritten there. */
    val fps: Double = 0.0,
    val bytesPerSec: Long = 0,
    val error: String = ""
) : WirePayload

/**
 * Frame codecs. Both tracks run at once: [H264] carries the desktop's live view
 * off this device's hardware encoder, and [JPEG] feeds the consumers that can
 * only take whole pictures - the virtual camera and the MJPEG endpoint.
 */
object CameraCodec {
    const val JPEG = "jpeg"
    const val H264 = "h264"
}

/**
 * Metadata for one encoded frame. The payload rides beside it as a blob: at
 * 30fps base64 would add a third to the bandwidth of the heaviest thing on the
 * wire.
 */
@Serializable
data class CameraFrame(
    val seq: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val ts: Long = 0,
    val codec: String = CameraCodec.JPEG,
    /** True when this access unit decodes on its own. H.264 only. */
    val key: Boolean = false,
    /**
     * Orientation the *video* track still needs applied, clockwise. The camera
     * writes straight into the encoder's input surface, so there is no pass in
     * which to bake it in - and a display transform costs nothing where a pixel
     * loop costs the frame rate. The JPEG track carries it already applied,
     * because a virtual camera has nowhere to put a transform.
     */
    val rotation: Int = 0,
    val mirror: Boolean = false
) : WirePayload

// ---- Air mouse ----

/** Mouse buttons the touchpad can press. */
object MouseButton {
    const val LEFT = "left"
    const val RIGHT = "right"
    const val MIDDLE = "middle"
}

/**
 * Button actions. [DOWN] and [UP] are separate so a tap-and-a-half or a
 * long-press drag can hold the button across a whole run of [InputMove].
 */
object ButtonAction {
    const val DOWN = "down"
    const val UP = "up"
    const val CLICK = "click"
    const val DOUBLE = "double"
}

/**
 * Named shell gestures. The desktop maps each to whatever its window manager
 * uses, and refuses anything outside this list — the air mouse deliberately
 * exposes no general keyboard.
 */
object ShellGesture {
    const val TASK_VIEW = "taskView"
    const val SHOW_DESKTOP = "showDesktop"
    const val DESKTOP_LEFT = "desktopLeft"
    const val DESKTOP_RIGHT = "desktopRight"
    const val BACK = "back"
    const val FORWARD = "forward"
}

/**
 * Relative pointer motion in desktop pixels. Fractional on purpose: a slow
 * drag moves well under a pixel per frame, and the host carries the remainder
 * rather than truncating it, so a careful finger still creeps the cursor.
 */
@Serializable
data class InputMove(val dx: Double, val dy: Double) : WirePayload

@Serializable
data class InputButton(val button: String, val action: String) : WirePayload

/**
 * Wheel motion in notches. Positive [dy] scrolls up and positive [dx] scrolls
 * right; this app applies the natural-scroll preference before sending, so the
 * desktop never has to know about it. [ctrl] asks for the wheel with control
 * held, which is how every desktop spells "zoom".
 */
@Serializable
data class InputScroll(val dx: Double, val dy: Double, val ctrl: Boolean = false) : WirePayload

@Serializable
data class InputGesture(val name: String) : WirePayload

/** The JSON encoded in the desktop's QR code. */
@Serializable
data class PairingPayload(
    val v: Int = 1,
    val daemonId: String = "",
    val hostName: String = "",
    val host: String,
    val port: Int,
    /** Empty when the user typed the code instead of scanning. */
    val hostKey: String = "",
    val code: String
)

// ---- Handshake frames (plaintext, before the channel is encrypted) ----

@Serializable
data class Hello(
    val type: String = "hello",
    val daemonId: String = "",
    val hostName: String = "",
    val identityKey: String,
    val ephemeralKey: String,
    val challenge: String
)

@Serializable
data class Auth(
    val mode: String,
    val identityKey: String,
    val ephemeralKey: String,
    @SerialName("deviceName") val deviceName: String,
    val proof: String
)

@Serializable
data class AuthResult(
    val type: String = "authResult",
    val ok: Boolean = false,
    val deviceId: String = "",
    val hostName: String = "",
    val proof: String = "",
    val error: String = ""
)


// ---- File transfer ----

@Serializable
data class FileOffer(
    val transferId: String,
    val name: String,
    val size: Long,
    val mimeType: String = "",
    /** SHA-256 of the whole file, hex; verified by the receiver. */
    val sha256: String = "",
    val direction: String
) : WirePayload

/**
 * The receiver's go-ahead. [offset] is how many bytes it already holds, so a
 * resumed transfer seeks instead of restarting.
 */
@Serializable
data class FileAccept(
    val transferId: String,
    val offset: Long = 0,
    val accepted: Boolean = true,
    val reason: String = ""
) : WirePayload

/**
 * Describes one slice. The bytes themselves ride beside this envelope as a
 * [Frame.KIND_BLOB] payload rather than base64 inside it: the frame is binary
 * on the wire either way, so encoding them would inflate every byte by a third
 * for nothing.
 *
 * [offset] is authoritative: the receiver writes at it, so a duplicated or
 * reordered frame cannot corrupt the file.
 */
@Serializable
data class FileChunk(
    val transferId: String,
    val offset: Long,
    val last: Boolean = false
) : WirePayload

@Serializable
data class FileAck(val transferId: String, val received: Long) : WirePayload

@Serializable
data class FileComplete(
    val transferId: String,
    val ok: Boolean = false,
    val sha256: String = "",
    val error: String = ""
) : WirePayload

@Serializable
data class FileControl(val transferId: String, val action: String) : WirePayload

@Serializable
data class FileProgress(
    val transferId: String,
    val name: String = "",
    val direction: String = Direction.UPLOAD,
    val status: String = TransferStatus.PENDING,
    val transferred: Long = 0,
    val size: Long = 0,
    val bytesPerSec: Long = 0,
    val error: String = "",
    val startedAt: Long = 0,
    val finishedAt: Long = 0
) {
    val fraction: Float get() = if (size > 0) (transferred.toFloat() / size).coerceIn(0f, 1f) else 0f
}

@Serializable
data class FileHistory(val transfers: List<FileProgress> = emptyList())

// ---- Stream Deck Neo Subsystem ----

@Serializable
data class DeckAction(
    val type: String = "url",
    val value: String = ""
)

@Serializable
data class DeckKey(
    val index: Int = 0,
    val title: String = "",
    val icon: String = "code",
    val bgColor: String? = null,
    val iconColor: String? = null,
    val badge: String? = null,
    /** data: URI of the launch target's real desktop icon, supplied by the host. */
    val iconData: String? = null,
    val action: DeckAction = DeckAction()
)

@Serializable
data class DeckInfobar(
    val mode: String = "clock",
    val customText: String = "",
    val format: String? = null
)

@Serializable
data class DeckPage(
    val id: String = "page-1",
    val name: String = "Page 1",
    val keys: List<DeckKey> = emptyList()
)

@Serializable
data class DeckConfig(
    val activePage: Int = 0,
    val infobar: DeckInfobar = DeckInfobar(),
    val pages: List<DeckPage> = emptyList()
) : WirePayload

@Serializable
data class DeckActionRequest(
    val pageId: String? = null,
    val keyIndex: Int = 0,
    val action: DeckAction = DeckAction()
) : WirePayload

@Serializable
data class InstalledApp(
    val name: String = "",
    val path: String = "",
    val icon: String = ""
)

@Serializable
data class InstalledAppsPayload(
    val apps: List<InstalledApp> = emptyList()
)

