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
    const val MEDIA_COMMAND = "media.playback.command"
    const val MEDIA_ARTWORK = "media.artwork"
    const val HOST_STATE = "host.state"
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
