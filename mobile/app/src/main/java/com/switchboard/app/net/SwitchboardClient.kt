package com.switchboard.app.net

import android.util.Log
import com.switchboard.app.crypto.SessionCrypto
import com.switchboard.app.data.KnownHost
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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
        val deviceId: String,
        val hostName: String,
        /** The host key learned during this handshake, pinned for later resumes. */
        val hostKey: String
    ) : ConnectionEvent
    data class State(val state: HostState) : ConnectionEvent
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
        val mode = if (credentials is Credentials.Pair) "pair" else "resume"
        val ephemeral = SessionCrypto.KeyPair.generate()

        val listener = object : WebSocketListener() {
            /** hello -> auth sent -> authenticated. */
            private var phase = Phase.AWAITING_HELLO
            private var pending: SessionCrypto.Session? = null
            private var daemonId = ""
            private var learnedHostKey = ""

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    when (phase) {
                        Phase.AWAITING_HELLO -> onHello(webSocket, text)
                        Phase.AWAITING_RESULT -> onResult(webSocket, text)
                        // Once encrypted, a plaintext frame is not part of the
                        // protocol and must not be acted on.
                        Phase.READY -> Log.w(TAG, "ignoring unexpected plaintext frame")
                    }
                }.onFailure { failure ->
                    trySend(ConnectionEvent.Failed(failure.message ?: "handshake failed"))
                    webSocket.cancel()
                }
            }

            private fun onHello(webSocket: WebSocket, text: String) {
                val hello = SwitchboardJson.decodeFromString(Hello.serializer(), text)

                // When the key is already known (QR scan, or a stored host) it
                // must match exactly. On manual entry there is nothing to
                // compare against yet, and the pairing code carries the
                // authentication instead; the key is pinned on success below.
                if (expectedHostKey.isNotEmpty()) {
                    check(hello.identityKey == expectedHostKey) {
                        "this host is not the paired device"
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
            }

            private fun onResult(webSocket: WebSocket, text: String) {
                val result = SwitchboardJson.decodeFromString(AuthResult.serializer(), text)
                val derived = checkNotNull(pending) { "no session in progress" }

                if (!result.ok) {
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
                trySend(ConnectionEvent.Connected(result.deviceId, result.hostName, learnedHostKey))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val active = session ?: return
                runCatching {
                    val envelope = SwitchboardJson.decodeFromString(
                        Envelope.serializer(),
                        active.open(bytes.toByteArray()).decodeToString()
                    )
                    if (envelope.action == Actions.HOST_STATE && envelope.payload != null) {
                        trySend(
                            ConnectionEvent.State(
                                SwitchboardJson.decodeFromJsonElement(
                                    HostState.serializer(),
                                    envelope.payload
                                )
                            )
                        )
                    }
                }.onFailure { Log.w(TAG, "dropping frame: ${it.message}") }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                trySend(ConnectionEvent.Disconnected)
                channel.close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                trySend(ConnectionEvent.Failed(t.message ?: "connection failed"))
                channel.close()
            }
        }

        socket = http.newWebSocket(Request.Builder().url(endpoint).build(), listener)

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
        else -> throw IllegalArgumentException("unsupported payload ${payload::class}")
    }

    fun disconnect() {
        socket?.close(NORMAL_CLOSURE, null)
        socket = null
        session = null
    }

    private enum class Phase { AWAITING_HELLO, AWAITING_RESULT, READY }

    private companion object {
        const val TAG = "SwitchboardClient"
        const val NORMAL_CLOSURE = 1000
    }
}
