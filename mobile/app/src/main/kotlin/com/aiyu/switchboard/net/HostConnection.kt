package com.aiyu.switchboard.net

import android.annotation.SuppressLint
import com.aiyu.switchboard.core.NativeCore
import com.aiyu.switchboard.data.B64
import com.aiyu.switchboard.data.Identity
import com.aiyu.switchboard.data.PairSecret
import com.aiyu.switchboard.data.PairTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

sealed interface Link {
    data object Connecting : Link
    data class Online(val os: String) : Link
    data class Offline(val reason: String) : Link
    /** The desktop refused this phone; reconnecting will not help. */
    data class Refused(val reason: String) : Link
}

/**
 * The desktop's TLS certificate is self-signed and made fresh at every start,
 * so there is nothing to check it against. TLS only hides the plaintext hello
 * from network observers; the end-to-end handshake with pinned keys is what
 * authenticates the desktop. Never use this client for anything else.
 */
@SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager", "BadHostnameVerifier")
private val acceptAnyCertificate = object : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

@SuppressLint("BadHostnameVerifier")
val sharedHttp: OkHttpClient = OkHttpClient.Builder()
    .sslSocketFactory(
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(acceptAnyCertificate), SecureRandom()) }.socketFactory,
        acceptAnyCertificate,
    )
    .hostnameVerifier { _, _ -> true }
    .connectTimeout(3, TimeUnit.SECONDS)
    .readTimeout(0, TimeUnit.SECONDS)
    // The desktop drops a phone after 40 s of silence.
    .pingInterval(15, TimeUnit.SECONDS)
    .build()

/**
 * One phone-to-desktop link. Tries each known address in turn and reconnects
 * with backoff until [stop]. With a [pairing] secret it pairs on first
 * contact; [onWelcome] fires once the desktop has proved its key.
 */
class HostConnection(
    target: PairTarget,
    private val identity: Identity,
    private val deviceName: String,
    private val scope: CoroutineScope,
    private var pairing: PairSecret? = null,
    private val onWelcome: (hostName: String, os: String, macs: List<String>) -> Unit = { _, _, _ -> },
    private val onRevoked: () -> Unit = {},
) {
    @Volatile var target: PairTarget = target
        private set

    private val _link = MutableStateFlow<Link>(Link.Offline("Not connected"))
    val link: StateFlow<Link> = _link.asStateFlow()
    private val _state = MutableStateFlow<HostState?>(null)
    val state: StateFlow<HostState?> = _state.asStateFlow()

    private val _offers = MutableStateFlow<List<Event.FileOffer>>(emptyList())
    /** Files the desktop is waiting for this phone to take or decline. */
    val offers: StateFlow<List<Event.FileOffer>> = _offers.asStateFlow()

    fun dismissOffer(xfer: String) {
        _offers.value = _offers.value.filter { it.xfer != xfer }
    }

    private val ids = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<Event.Result>>()
    private var ws: WebSocket? = null
    /** Native session handle; only touched under [lock] so it is never used after free. */
    private var session = 0L
    private val lock = Any()
    private var loop: Job? = null
    @Volatile private var stopped = true

    fun updateAddresses(addrs: List<String>, port: Int) {
        target = target.copy(addrs = addrs, port = port)
    }

    fun start() {
        if (!stopped) return
        stopped = false
        loop = scope.launch { run() }
    }

    fun stop() {
        stopped = true
        loop?.cancel()
        ws?.close(1000, null)
        cleanup("Not connected")
    }

    /** Reconnect now instead of waiting out the backoff. */
    fun retry() {
        stop()
        start()
    }

    private suspend fun run() {
        var backoff = 1000L
        while (!stopped) {
            for (addr in target.addrs.distinct()) {
                if (stopped) return
                val ended = attempt(addr)
                if (ended == Attempt.Refused || stopped) return
                if (ended == Attempt.WasOnline) backoff = 1000L
            }
            if (_link.value !is Link.Refused) _link.value = Link.Offline("Can't reach ${target.name}")
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
    }

    private enum class Attempt { Failed, WasOnline, Refused }

    /** One connection to one address; returns when it closes. */
    private suspend fun attempt(addr: String): Attempt {
        _link.value = Link.Connecting
        val done = CompletableDeferred<Attempt>()
        val (bare, port) = splitAddr(addr, target.port)
        val host = if (bare.contains(':')) "[$bare]" else bare
        val req = Request.Builder().url("wss://$host:$port/ws").build()
        val eph = NativeCore.keypair()
        val staticSk = B64.decode(identity.secretKey)!!
        val staticPk = NativeCore.publicKey(staticSk)!!
        var helloText = ""
        var everOnline = false

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val mode = when (pairing) {
                    is PairSecret.Token -> "pair_qr"
                    is PairSecret.Code -> "pair_code"
                    null -> "resume"
                }
                helloText = WireJson.encodeToString(
                    ClientHello.serializer(),
                    ClientHello(mode = mode, deviceId = identity.deviceId, deviceName = deviceName, staticPk = B64.encode(staticPk), ephPk = B64.encode(eph.copyOfRange(32, 64))),
                )
                webSocket.send(helloText)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                when (val sh = runCatching { WireJson.decodeFromString(ServerHello.serializer(), text) }.getOrNull()) {
                    is ServerHello.Reject -> {
                        _link.value = Link.Refused(
                            when (sh.reason) {
                                "unknown" -> "${target.name} no longer knows this phone"
                                "not_pairing" -> "${target.name} isn't showing a pairing code"
                                "version" -> "Update Switchboard on both devices"
                                else -> "${target.name} refused the connection"
                            },
                        )
                        done.complete(Attempt.Refused)
                        webSocket.close(1000, null)
                    }
                    is ServerHello.Accept -> {
                        val psk = when (val p = pairing) {
                            is PairSecret.Token -> p.bytes
                            is PairSecret.Code -> NativeCore.codePsk(p.code)
                            null -> ByteArray(32)
                        }
                        val t = NativeCore.transcript(helloText.encodeToByteArray(), text.encodeToByteArray())
                        val remoteEph = B64.decode(sh.ephPk) ?: ByteArray(0)
                        val pinned = B64.decode(target.pk) ?: ByteArray(0)
                        val h = NativeCore.sessionNew(staticSk, eph.copyOfRange(0, 32), pinned, remoteEph, psk, t)
                        synchronized(lock) { session = h }
                        if (h == 0L) {
                            webSocket.close(1002, null)
                            return
                        }
                        sendFrame(webSocket, buildJsonObject { put("id", 0); put("type", "hello") })
                    }
                    null -> webSocket.close(1002, null)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val pt = synchronized(lock) { if (session == 0L) null else NativeCore.open(session, bytes.toByteArray()) } ?: run {
                    webSocket.close(1002, null)
                    return
                }
                when (val ev = runCatching { WireJson.decodeFromString(Event.serializer(), pt.decodeToString()) }.getOrNull()) {
                    is Event.Welcome -> {
                        everOnline = true
                        // From now on this phone is known: reconnects resume.
                        pairing = null
                        target = target.copy(name = ev.hostName)
                        _link.value = Link.Online(ev.os)
                        onWelcome(ev.hostName, ev.os, ev.macs)
                    }
                    is Event.Result -> pending.remove(ev.id)?.complete(ev)
                    is Event.State -> _state.value = ev
                    is Event.FileOffer -> _offers.value = (_offers.value + ev).takeLast(10)
                    Event.Revoked -> {
                        _link.value = Link.Refused("${target.name} forgot this phone")
                        onRevoked()
                        done.complete(Attempt.Refused)
                    }
                    null -> {}
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                done.complete(if (everOnline) Attempt.WasOnline else Attempt.Failed)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                done.complete(if (everOnline) Attempt.WasOnline else Attempt.Failed)
            }
        }

        val socket = sharedHttp.newWebSocket(req, listener)
        ws = socket
        // A desktop that accepted TCP but never answered the hello is dead to us.
        withTimeoutOrNull(if (pairing != null) 15_000 else 8_000) {
            while (!everOnline && !done.isCompleted) delay(50)
        }
        if (!everOnline && !done.isCompleted) {
            socket.cancel()
            done.complete(Attempt.Failed)
        }
        val ended = done.await()
        cleanup(if (ended == Attempt.Refused) null else "Disconnected")
        return ended
    }

    private fun cleanup(offline: String?) {
        ws = null
        synchronized(lock) {
            if (session != 0L) NativeCore.sessionFree(session)
            session = 0
        }
        pending.values.forEach { it.complete(Event.Result(-1, false, "Disconnected")) }
        pending.clear()
        // The desktop forgets its offers when the link drops.
        _offers.value = emptyList()
        if (offline != null && _link.value !is Link.Refused) _link.value = Link.Offline(offline)
    }

    /** Seal and send under one lock so frames leave in counter order. */
    private fun sendFrame(socket: WebSocket, obj: JsonObject): Boolean = synchronized(lock) {
        if (session == 0L) return false
        val frame = NativeCore.seal(session, obj.toString().encodeToByteArray()) ?: return false
        socket.send(frame.toByteString())
    }

    /** Sends a command and waits for its result (5 s). */
    suspend fun send(cmd: JsonObject): Event.Result {
        val socket = ws
        if (socket == null || _link.value !is Link.Online) return Event.Result(-1, false, "Not connected")
        val id = ids.incrementAndGet()
        val d = CompletableDeferred<Event.Result>()
        pending[id] = d
        val obj = JsonObject(cmd + ("id" to kotlinx.serialization.json.JsonPrimitive(id)))
        if (!sendFrame(socket, obj)) {
            pending.remove(id)
            return Event.Result(id, false, "Not connected")
        }
        return withTimeoutOrNull(5_000) { d.await() } ?: Event.Result(id, false, "${target.name} didn't answer").also { pending.remove(id) }
    }

    /** Fire-and-forget for slider drags; the next state push confirms. */
    fun post(cmd: JsonObject) {
        scope.launch { send(cmd) }
    }
}
