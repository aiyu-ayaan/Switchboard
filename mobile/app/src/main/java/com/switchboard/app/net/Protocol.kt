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
    const val MEDIA_COMMAND = "media.playback.command"
    const val MEDIA_ARTWORK = "media.artwork"
    const val HOST_STATE = "host.state"

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
data class Volume(val level: Int = 0, val muted: Boolean = false)

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

@Serializable
data class MixerSet(val sessionId: String, val level: Int, val muted: Boolean = false)

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
    val media: MediaState = MediaState(),
    val capabilities: List<String> = emptyList()
)

@Serializable
data class DisplaySet(val displayId: String, val value: Int)

@Serializable
data class MediaCommand(val action: String)

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
)

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
)

/** One slice. [offset] is authoritative: the receiver writes at it. */
@Serializable
data class FileChunk(
    val transferId: String,
    val offset: Long,
    val data: String,
    val last: Boolean = false
)

@Serializable
data class FileAck(val transferId: String, val received: Long)

@Serializable
data class FileComplete(
    val transferId: String,
    val ok: Boolean = false,
    val sha256: String = "",
    val error: String = ""
)

@Serializable
data class FileControl(val transferId: String, val action: String)

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
