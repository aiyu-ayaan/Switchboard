package com.aiyu.switchboard.net

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.aiyu.switchboard.data.PairSecret
import com.aiyu.switchboard.data.PairTarget
import com.aiyu.switchboard.data.SavedHost
import com.aiyu.switchboard.data.Vault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request

sealed interface PairState {
    data object Connecting : PairState
    data class Done(val host: SavedHost) : PairState
    data class Failed(val message: String) : PairState
}

/**
 * Every paired desktop stays connected while the app is in the foreground, so
 * switching between them is instant. Pairing hands its live connection over
 * once the desktop has accepted.
 */
class Connections(context: Context, val vault: Vault) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val discovery = Discovery(context)
    val transfers = Transfers(context.applicationContext, scope, vault)
    private val conns = mutableMapOf<String, HostConnection>()
    private val _live = MutableStateFlow<Map<String, HostConnection>>(emptyMap())
    val live: StateFlow<Map<String, HostConnection>> = _live.asStateFlow()
    private var running = false

    val deviceName: String = Settings.Global.getString(context.contentResolver, "device_name")?.takeIf { it.isNotBlank() }
        ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

    init {
        // A desktop seen at a new address: remember it and retry right away.
        scope.launch {
            discovery.found.collect { found ->
                for ((id, t) in found) {
                    val saved = vault.data.value.hosts.find { it.id == id } ?: continue
                    if (saved.pk != t.pk) continue
                    val addrs = (t.addrs + saved.addrs).distinct().take(6)
                    if (addrs != saved.addrs || t.port != saved.port) {
                        vault.update { d -> d.copy(hosts = d.hosts.map { if (it.id == id) it.copy(addrs = addrs, port = t.port) else it }) }
                        conns[id]?.let { c ->
                            c.updateAddresses(addrs, t.port)
                            if (c.link.value !is Link.Online) c.retry()
                        }
                    }
                }
            }
        }
    }

    private fun connectionFor(h: SavedHost): HostConnection = conns.getOrPut(h.id) {
        HostConnection(
            PairTarget(h.id, h.name, h.pk, h.addrs, h.port),
            vault.data.value.identity,
            deviceName,
            scope,
            onWelcome = { name, os -> rename(h.id, name, os) },
        )
    }

    private fun publish() {
        _live.value = conns.toMap()
    }

    @Synchronized
    fun start() {
        running = true
        vault.data.value.hosts.forEach { connectionFor(it).start() }
        publish()
        discovery.start()
    }

    @Synchronized
    fun stop() {
        running = false
        conns.values.forEach { it.stop() }
        discovery.stop()
    }

    private fun rename(id: String, name: String, os: String) {
        vault.update { d -> d.copy(hosts = d.hosts.map { if (it.id == id && (it.name != name || it.os != os)) it.copy(name = name, os = os) else it }) }
    }

    fun select(id: String) = vault.update { it.copy(selected = id) }

    /** Forget locally and, if connected, tell the desktop to forget us too. */
    suspend fun forget(id: String) {
        conns[id]?.let { c ->
            if (c.link.value is Link.Online) c.send(Cmd.unpair())
            c.stop()
        }
        synchronized(this) { conns.remove(id) }
        publish()
        vault.update { d ->
            val hosts = d.hosts.filterNot { it.id == id }
            d.copy(hosts = hosts, selected = if (d.selected == id) hosts.firstOrNull()?.id else d.selected)
        }
    }

    /** Pairs and, on success, keeps the connection as a saved host. */
    fun pair(target: PairTarget, secret: PairSecret): StateFlow<PairState> {
        val out = MutableStateFlow<PairState>(PairState.Connecting)
        lateinit var conn: HostConnection
        conn = HostConnection(
            target,
            vault.data.value.identity,
            deviceName,
            scope,
            pairing = secret,
            onWelcome = { name, os ->
                if (out.value is PairState.Done) return@HostConnection rename(target.id, name, os)
                val host = SavedHost(target.id, name, target.pk, target.addrs, target.port, os)
                vault.update { d -> d.copy(hosts = d.hosts.filterNot { it.id == host.id } + host, selected = host.id) }
                synchronized(this) {
                    conns.remove(host.id)?.takeIf { it !== conn }?.stop()
                    conns[host.id] = conn
                }
                publish()
                out.value = PairState.Done(host)
            },
        )
        scope.launch {
            conn.start()
            val end = conn.link.first { it is Link.Refused || it is Link.Online || (it is Link.Offline && it.reason.startsWith("Can't reach")) }
            when (end) {
                is Link.Refused -> out.value = PairState.Failed(end.reason)
                is Link.Offline -> {
                    conn.stop()
                    out.value = PairState.Failed(
                        if (target.addrs.isEmpty()) "No address for ${target.name}"
                        else "Couldn't pair with ${target.name}. Check the code, and that both are on the same network.",
                    )
                }
                else -> {}
            }
        }
        return out
    }

    /** Asks a typed address who it is, for code pairing without discovery. */
    suspend fun probe(address: String, defaultPort: Int): PairTarget? = withContext(Dispatchers.IO) {
        val (host, port) = address.trim().let { a ->
            val p = a.substringAfterLast(':', "").toIntOrNull()
            if (p != null && a.count { it == ':' } == 1) a.substringBeforeLast(':') to p else a to defaultPort
        }
        runCatching {
            sharedHttp.newCall(Request.Builder().url("http://$host:$port/info").build()).execute().use { r ->
                val o = Json.parseToJsonElement(r.body!!.string()).jsonObject
                PairTarget(o["id"]!!.jsonPrimitive.content, o["name"]!!.jsonPrimitive.content, o["pk"]!!.jsonPrimitive.content, listOf(host), port)
            }
        }.getOrNull()
    }

}
