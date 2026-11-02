package com.aiyu.switchboard.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Mirrors core/src/protocol.rs. Keep the two in step.

const val PROTOCOL_VERSION = 1

/** Bytes per file chunk before base64; core/src/protocol.rs FILE_CHUNK. */
const val FILE_CHUNK = 192 * 1024

val WireJson = Json {
    ignoreUnknownKeys = true
    classDiscriminator = "type"
    explicitNulls = false
    encodeDefaults = true
}

@Serializable
data class ClientHello(
    val v: Int = PROTOCOL_VERSION,
    val mode: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String,
    @SerialName("static_pk") val staticPk: String,
    @SerialName("eph_pk") val ephPk: String,
)

@Serializable
sealed interface ServerHello {
    @Serializable @SerialName("accept")
    data class Accept(val v: Int, @SerialName("host_id") val hostId: String, @SerialName("host_name") val hostName: String, @SerialName("eph_pk") val ephPk: String) : ServerHello

    @Serializable @SerialName("reject")
    data class Reject(val reason: String) : ServerHello
}

@Serializable
sealed interface Event {
    @Serializable @SerialName("welcome")
    data class Welcome(@SerialName("host_id") val hostId: String, @SerialName("host_name") val hostName: String, val os: String) : Event

    @Serializable @SerialName("result")
    data class Result(val id: Long, val ok: Boolean, val error: String? = null, val data: String? = null) : Event

    /** The desktop wants to send this phone a file. */
    @Serializable @SerialName("file_offer")
    data class FileOffer(val xfer: String, val name: String, val size: Long) : Event

    @Serializable @SerialName("state")
    data class State(
        val volume: Volume? = null,
        val outputs: List<AudioOutput> = emptyList(),
        val apps: List<AudioApp> = emptyList(),
        val displays: List<Display> = emptyList(),
        val media: Media? = null,
        val players: List<MediaPlayer> = emptyList(),
        val stats: Stats = Stats(),
    ) : Event

    @Serializable @SerialName("revoked")
    data object Revoked : Event
}

typealias HostState = Event.State

@Serializable data class Volume(val level: Int, val muted: Boolean)

@Serializable data class AudioOutput(val id: String, val name: String, val default: Boolean)

@Serializable data class AudioApp(val id: String, val name: String, val detail: String = "", val level: Int, val muted: Boolean)

@Serializable
data class Display(val id: String, val name: String, val kind: String, val brightness: Int? = null, val contrast: Int? = null) {
    val external get() = kind == "external"
}

@Serializable data class MediaPlayer(val id: String, val name: String, val playing: Boolean)

@Serializable
data class Media(
    val id: String = "",
    val player: String,
    val title: String,
    val artist: String,
    val playing: Boolean,
    /** Milliseconds into the track and its length, when the player says. */
    val position: Long? = null,
    val length: Long? = null,
    @SerialName("can_seek") val canSeek: Boolean = false,
    val shuffle: Boolean? = null,
    /** "off", "track" or "all". */
    val repeat: String? = null,
    /** Changes with the cover; fetch the picture with [Cmd.mediaArt]. */
    val art: String? = null,
)

@Serializable
data class Stats(
    val cpu: Float = 0f,
    @SerialName("mem_used") val memUsed: Long = 0,
    @SerialName("mem_total") val memTotal: Long = 0,
    val uptime: Long = 0,
    val battery: Battery? = null,
)

@Serializable data class Battery(val percent: Int, val charging: Boolean)

/** Phone-to-desktop commands, built as the flat objects serde expects. */
object Cmd {
    fun hello() = obj("hello")
    fun setVolume(level: Int) = obj("set_volume") { put("level", level.coerceIn(0, 100)) }
    fun setMute(muted: Boolean) = obj("set_mute") { put("muted", muted) }
    fun setOutput(output: String) = obj("set_output") { put("output", output) }
    fun setAppVolume(app: String, level: Int) = obj("set_app_volume") { put("app", app); put("level", level.coerceIn(0, 100)) }
    fun setAppMute(app: String, muted: Boolean) = obj("set_app_mute") { put("app", app); put("muted", muted) }
    fun setBrightness(display: String, level: Int) = obj("set_brightness") { put("display", display); put("level", level.coerceIn(0, 100)) }
    fun setContrast(display: String, level: Int) = obj("set_contrast") { put("display", display); put("level", level.coerceIn(0, 100)) }
    fun media(action: String) = obj("media") { put("action", action) }
    fun seek(positionMs: Long) = obj("seek") { put("position", positionMs.coerceAtLeast(0)) }
    fun setShuffle(on: Boolean) = obj("set_shuffle") { put("on", on) }
    fun setRepeat(mode: String) = obj("set_repeat") { put("mode", mode) }
    fun selectPlayer(id: String) = obj("select_player") { put("player", id) }
    fun mediaArt() = obj("media_art")
    fun power(action: String) = obj("power") { put("action", action) }
    fun unpair() = obj("unpair")
    fun fileSend(xfer: String, name: String, size: Long) = obj("file_send") { put("xfer", xfer); put("name", name); put("size", size) }
    fun fileChunk(xfer: String, offset: Long, data: String) = obj("file_chunk") { put("xfer", xfer); put("offset", offset); put("data", data) }
    fun fileDone(xfer: String) = obj("file_done") { put("xfer", xfer) }
    fun fileCancel(xfer: String) = obj("file_cancel") { put("xfer", xfer) }
    fun fileRead(xfer: String, offset: Long, len: Int) = obj("file_read") { put("xfer", xfer); put("offset", offset); put("len", len) }

    private fun obj(type: String, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): JsonObject =
        buildJsonObject { put("type", type); body() }
}
