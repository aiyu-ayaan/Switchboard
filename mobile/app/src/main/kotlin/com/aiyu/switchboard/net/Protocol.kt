package com.aiyu.switchboard.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Mirrors core/src/protocol.rs. Keep the two in step.

const val PROTOCOL_VERSION = 1

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
    data class Result(val id: Long, val ok: Boolean, val error: String? = null) : Event

    @Serializable @SerialName("state")
    data class State(
        val volume: Volume? = null,
        val outputs: List<AudioOutput> = emptyList(),
        val apps: List<AudioApp> = emptyList(),
        val displays: List<Display> = emptyList(),
        val media: Media? = null,
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

@Serializable data class Media(val player: String, val title: String, val artist: String, val playing: Boolean)

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
    fun power(action: String) = obj("power") { put("action", action) }
    fun unpair() = obj("unpair")

    private fun obj(type: String, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): JsonObject =
        buildJsonObject { put("type", type); body() }
}
