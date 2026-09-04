package com.switchboard.app.net

import android.os.Build
import android.util.Log
import com.switchboard.app.crypto.SessionCrypto
import com.switchboard.app.data.KnownHost
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** What the UI observes while a connection is live. */
sealed interface ConnectionEvent {
    data class Connected(
        /** The host's real daemon ID, learned from its hello. */
        val daemonId: String,
        val deviceId: String,
        val hostName: String,
        /** The host key learned during this handshake, pinned for later resumes. */
        val hostKey: String
    ) : ConnectionEvent
    data class State(val state: HostState) : ConnectionEvent
    data class Artwork(val artwork: MediaArtwork) : ConnectionEvent

    /**
     * Any `file.*` frame, handed over undecoded. Transfers are stateful and
     * ordered, so the engine that owns that state decodes them; routing six
     * more typed events through the UI layer would only widen the path they
     * take to get there.
     */
    data class FileFrame(val action: String, val payload: JsonElement) : ConnectionEvent
    data class Failed(val reason: String) : ConnectionEvent
    data object Disconnected : ConnectionEvent
}

/** How this connection authenticates. */
sealed interface Credentials {
    /**
     * First contact. The payload comes from a scanned QR code, which pins the
     * host key, or from manual entry, where [PairingPayload.hostKey] is empty
     * and the typed code is the only shared secret. Both are safe: the code is
     * mixed into the key schedule, so a machine that does not know it cannot
     * produce a valid host proof.
     */
    data class Pair(val payload: PairingPayload) : Credentials

    /** Reconnect: the stored host key plus this device's identity. */
    data class Resume(val host: KnownHost) : Credentials
}

/**
 * Encrypted WebSocket client for one desktop host.
 *
 * The socket carries exactly two plaintext frames, the host's `hello` and this
 * device's `auth`, plus the host's verdict. Everything after that is
 * AES-256-GCM under the derived session key.
 */
class SwitchboardClient(
    private val identity: SessionCrypto.KeyPair,
    private val deviceName: String
) {
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // the socket is long-lived
        .build()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var session: SessionCrypto.Session? = null

    fun connect(credentials: Credentials): Flow<ConnectionEvent> = callbackFlow {
        val endpoint: String
        val expectedHostKey: String
        val pairingCode: ByteArray?

        when (credentials) {
            is Credentials.Pair -> {
                endpoint = "ws://${credentials.payload.host}:${credentials.payload.port}/ws"
                expectedHostKey = credentials.payload.hostKey
                // The code is used verbatim as typed/scanned, matching
                // newPairingCode() on the host. It is not base64.
                pairingCode = credentials.payload.code.toByteArray()
            }
            is Credentials.Resume -> {
                endpoint = credentials.host.endpoint
                expectedHostKey = credentials.host.hostKey
                pairingCode = null
            }
        }

        val endpoints = buildList {
            add(endpoint)

            fun fallbackUrl(newHost: String): String {
                val prefix = endpoint.substringBefore("://") + "://"
                val rest = endpoint.substringAfter("://")
                val portAndPath = rest.substring(
                    rest.indexOfFirst { it == ':' || it == '/' }.let { if (it == -1) rest.length else it }
                )
                return "$prefix$newHost$portAndPath"
            }

            if (isEmulator()) {
                val emulatorUrl = fallbackUrl("10.0.2.2")
                if (!contains(emulatorUrl)) {
                    add(emulatorUrl)
                }
            }
            val loopbackUrl = fallbackUrl("127.0.0.1")
            if (!contains(loopbackUrl)) {
                add(loopbackUrl)
            }
        }

        val mode = if (credentials is Credentials.Pair) "pair" else "resume"
        val ephemeral = SessionCrypto.KeyPair.generate()

        lateinit var listener: WebSocketListener

        var endpointIndex = 0
        fun startNextConnection(): Boolean {
            if (endpointIndex < endpoints.size) {
                val nextUrl = endpoints[endpointIndex++]
                Log.i(TAG, "Connecting to host endpoint: $nextUrl")
                socket = http.newWebSocket(Request.Builder().url(nextUrl).build(), listener)
                return true
            }
            return false
        }

        listener = object : WebSocketListener() {
            /** hello -> auth sent -> authenticated. */
            private var phase = Phase.AWAITING_HELLO
            private var pending: SessionCrypto.Session? = null
            private var daemonId = ""
            private var learnedHostKey = ""

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "onMessage(text) in phase=$phase: $text")
                runCatching {
                    when (phase) {
                        Phase.AWAITING_HELLO -> onHello(webSocket, text)
                        Phase.AWAITING_RESULT -> onResult(webSocket, text)
                        // Once encrypted, a plaintext frame is not part of the
                        // protocol and must not be acted on.
                        Phase.READY -> Log.w(TAG, "ignoring unexpected plaintext frame")
                    }
                }.onFailure { failure ->
                    Log.e(TAG, "Error in onMessage handshake: ${failure.message}", failure)
                    trySend(ConnectionEvent.Failed(failure.message ?: "handshake failed"))
                    webSocket.cancel()
                }
            }

            private fun onHello(webSocket: WebSocket, text: String) {
                Log.i(TAG, "Received hello from server")
                val hello = SwitchboardJson.decodeFromString(Hello.serializer(), text)

                if (expectedHostKey.isNotEmpty()) {
                    check(hello.identityKey == expectedHostKey) {
                        "this host is not the paired device (expected=$expectedHostKey, got=${hello.identityKey})"
                    }
                }
                learnedHostKey = hello.identityKey

                val derived = SessionCrypto.derive(
                    identity = identity,
                    ephemeral = ephemeral,
                    hostIdentityPub = SessionCrypto.decode(hello.identityKey),
                    hostEphemeralPub = SessionCrypto.decode(hello.ephemeralKey),
                    challenge = SessionCrypto.decode(hello.challenge),
                    pairingCode = pairingCode
                )

                webSocket.send(
                    SwitchboardJson.encodeToString(
                        Auth.serializer(),
                        Auth(
                            mode = mode,
                            identityKey = SessionCrypto.encode(identity.publicKey),
                            ephemeralKey = SessionCrypto.encode(ephemeral.publicKey),
                            deviceName = deviceName,
                            proof = SessionCrypto.encode(
                                derived.proof("client", hello.daemonId, identity.publicKey)
                            )
                        )
                    )
                )

                pending = derived
                daemonId = hello.daemonId
                phase = Phase.AWAITING_RESULT
                Log.i(TAG, "Sent auth to server, phase is now AWAITING_RESULT")
            }

            private fun onResult(webSocket: WebSocket, text: String) {
                Log.i(TAG, "Received auth result from server: $text")
                val result = SwitchboardJson.decodeFromString(AuthResult.serializer(), text)
                val derived = checkNotNull(pending) { "no session in progress" }

                if (!result.ok) {
                    Log.e(TAG, "Server rejected auth: ${result.error}")
                    trySend(ConnectionEvent.Failed(result.error.ifEmpty { "pairing rejected" }))
                    webSocket.cancel()
                    return
                }

                // Mutual proof: the host must show it derived the same key.
                val hostProved = derived.verifyProof(
                    label = "host",
                    daemonId = daemonId,
                    clientPublicKey = identity.publicKey,
                    received = SessionCrypto.decode(result.proof)
                )
                check(hostProved) { "host failed to prove its identity" }

                session = derived
                phase = Phase.READY
                Log.i(TAG, "Handshake complete! Emitting ConnectionEvent.Connected for ${result.hostName}")
                trySend(
                    ConnectionEvent.Connected(
                        daemonId,
                        result.deviceId,
                        result.hostName,
                        learnedHostKey
                    )
                )
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val active = session ?: return
                runCatching {
                    val envelope = SwitchboardJson.decodeFromString(
                        Envelope.serializer(),
                        active.open(bytes.toByteArray()).decodeToString()
                    )
                    val payload = envelope.payload
                    when {
                        envelope.action == Actions.HOST_STATE && payload != null -> trySend(
                            ConnectionEvent.State(
                                SwitchboardJson.decodeFromJsonElement(
                                    HostState.serializer(),
                                    payload
                                )
                            )
                        )

                        envelope.action == Actions.MEDIA_ARTWORK && payload != null -> trySend(
                            ConnectionEvent.Artwork(
                                SwitchboardJson.decodeFromJsonElement(
                                    MediaArtwork.serializer(),
                                    payload
                                )
                            )
                        )

                        envelope.action.startsWith("file.") && payload != null ->
                            trySend(ConnectionEvent.FileFrame(envelope.action, payload))
                    }
                }.onFailure { Log.w(TAG, "dropping frame: ${it.message}") }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: code=$code, reason=$reason")
                trySend(ConnectionEvent.Disconnected)
                channel.close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WebSocket onFailure: phase=$phase, error=$t, response=$response")
                if (phase == Phase.AWAITING_HELLO && startNextConnection()) {
                    Log.i(TAG, "Primary endpoint connection failed ($t), trying fallback endpoint")
                    return
                }
                // A transport failure just means the desktop is not reachable.
                // Raw socket messages ("unexpected end of stream on ...") are
                // noise to the user, so this reads as a plain disconnect; real
                // protocol failures still emit Failed from the handshake.
                trySend(ConnectionEvent.Disconnected)
                channel.close()
            }
        }

        startNextConnection()

        awaitClose {
            socket?.close(NORMAL_CLOSURE, null)
            socket = null
            session = null
        }
    }

    /** Sends one encrypted command. No-ops before the handshake completes. */
    fun send(action: String, payload: Any? = null) {
        val active = session ?: return
        val envelope = Envelope(
            id = UUID.randomUUID().toString(),
            type = "command",
            action = action,
            payload = payload?.let(::encodePayload),
            timestamp = System.currentTimeMillis()
        )
        val raw = SwitchboardJson.encodeToString(Envelope.serializer(), envelope)
        socket?.send(active.seal(raw.encodeToByteArray()).toByteString())
    }

    private fun encodePayload(payload: Any) = when (payload) {
        is DisplaySet -> SwitchboardJson.encodeToJsonElement(DisplaySet.serializer(), payload)
        is Volume -> SwitchboardJson.encodeToJsonElement(Volume.serializer(), payload)
        is MediaCommand -> SwitchboardJson.encodeToJsonElement(MediaCommand.serializer(), payload)
        is FileOffer -> SwitchboardJson.encodeToJsonElement(FileOffer.serializer(), payload)
        is FileAccept -> SwitchboardJson.encodeToJsonElement(FileAccept.serializer(), payload)
        is FileChunk -> SwitchboardJson.encodeToJsonElement(FileChunk.serializer(), payload)
        is FileAck -> SwitchboardJson.encodeToJsonElement(FileAck.serializer(), payload)
        is FileComplete -> SwitchboardJson.encodeToJsonElement(FileComplete.serializer(), payload)
        is FileControl -> SwitchboardJson.encodeToJsonElement(FileControl.serializer(), payload)
        else -> throw IllegalArgumentException("unsupported payload ${payload::class}")
    }

    fun disconnect() {
        socket?.close(NORMAL_CLOSURE, null)
        socket = null
        session = null
    }

    private enum class Phase { AWAITING_HELLO, AWAITING_RESULT, READY }

    companion object {
        private const val TAG = "SwitchboardClient"
        private const val NORMAL_CLOSURE = 1000

        internal fun isEmulator(): Boolean {
            return (Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.HARDWARE.contains("goldfish")
                || Build.HARDWARE.contains("ranchu")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.startsWith("sdk_")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.DEVICE.contains("emu")
                || Build.MANUFACTURER.contains("Genymotion")
                || (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                || "google_sdk" == Build.PRODUCT)
        }
    }
}
