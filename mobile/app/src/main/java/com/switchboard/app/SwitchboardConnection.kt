package com.switchboard.app

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.switchboard.app.data.HostStore
import com.switchboard.app.data.KnownHost
import com.switchboard.app.net.Actions
import com.switchboard.app.net.ConnectionEvent
import com.switchboard.app.net.Credentials
import com.switchboard.app.net.FileProgress
import com.switchboard.app.net.HostState
import com.switchboard.app.net.PairingPayload
import com.switchboard.app.net.SwitchboardClient
import com.switchboard.app.net.WirePayload
import com.switchboard.app.net.SwitchboardJson
import com.switchboard.app.net.TransferStatus
import com.switchboard.app.camera.CameraController
import com.switchboard.app.transfer.TransferEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything about the live session that outlives any one Activity. */
data class ConnectionState(
    val status: ConnectionStatus = ConnectionStatus.Disconnected,
    val hosts: List<KnownHost> = emptyList(),
    val activeHost: KnownHost? = null,
    val host: HostState = HostState(),
    /** Cover art for [HostState.media], decoded once per track. */
    val artwork: ImageBitmap? = null,
    val error: String? = null
)

/**
 * Owns the socket for the whole process rather than for one screen.
 *
 * Swiping the task away destroys the Activity and its ViewModel. While the
 * connection lived in `viewModelScope` that also closed the WebSocket, so a
 * transfer the foreground service was deliberately keeping alive was failed
 * with "Connection lost" a moment later. Holding it here means the only thing
 * that ends a transfer is the transfer itself.
 *
 * The process still needs a reason to stay resident, which is
 * [com.switchboard.app.transfer.TransferService]; this holder only guarantees
 * that nothing in the UI layer tears the socket down underneath it.
 */
class SwitchboardConnection private constructor(context: Context) {

    private val store = HostStore(context)
    private val transfers = TransferEngine.get(context)
    val camera = CameraController.get(context)
    private val client = SwitchboardClient(store.identity, "${Build.MANUFACTURER} ${Build.MODEL}")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(ConnectionState(hosts = store.hosts()))
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private var connection: Job? = null

    /**
     * Counted rather than a flag: a new Activity can attach before the old
     * one's ViewModel is cleared, and a plain boolean would let that stale
     * release drop a connection the new screen is already using.
     */
    private var uiHolders = 0
    private var idleWatch: Job? = null

    /**
     * The artwork ID currently held or in flight. Snapshots arrive on every
     * host change, so without this a single track would be re-fetched on each
     * one; the host only changes this ID when the track itself changes.
     */
    private var artworkId: String = ""

    init {
        // Reconnect to the host used last, so opening the app lands on controls
        // rather than on a picker.
        val last = store.lastHostId?.let { id -> store.hosts().find { it.daemonId == id } }
            ?: store.hosts().firstOrNull()
        last?.let(::connect)
    }

    // ---- UI attachment ----

    fun retainUi() {
        uiHolders++
        // Started on first attach, not in init: the check would otherwise run
        // against zero holders while this very constructor is still dialling
        // the last host, and tear that attempt down immediately.
        if (idleWatch == null) {
            idleWatch = scope.launch {
                transfers.transfers.collect { list ->
                    if (shouldTearDown(uiHolders > 0, list)) disconnect()
                }
            }
        }
    }

    /**
     * The last screen has gone. Anything still moving keeps the session; an
     * idle app must not sit on a socket and a foreground service forever, so
     * with nothing in flight the connection goes now.
     */
    fun releaseUi() {
        uiHolders--
        if (shouldTearDown(uiHolders > 0, transfers.transfers.value)) disconnect()
    }

    // ---- Session ----

    /** Handles a scanned or manually resolved QR payload. */
    fun pair(payloadJson: String) {
        val payload = runCatching {
            SwitchboardJson.decodeFromString(PairingPayload.serializer(), payloadJson)
        }.getOrNull()

        if (payload == null) {
            _state.update { it.copy(error = "That is not a Switchboard code.") }
            return
        }
        startConnection(
            Credentials.Pair(payload),
            KnownHost(
                daemonId = payload.daemonId,
                hostName = payload.hostName.ifEmpty { payload.host },
                host = payload.host,
                port = payload.port,
                hostKey = payload.hostKey
            )
        )
    }

    fun connect(host: KnownHost) = startConnection(Credentials.Resume(host), host)

    /**
     * Pairs from a typed address and code, for users who cannot scan. The code
     * is the same secret the QR carries, so this reaches an identical session.
     */
    fun pairManually(address: String, code: String) {
        val trimmedCode = code.trim().uppercase()
        val host = address.trim().substringBefore(':')
        val port = address.trim().substringAfter(':', "").toIntOrNull() ?: DEFAULT_PORT

        if (host.isEmpty() || trimmedCode.isEmpty()) {
            _state.update { it.copy(error = "Enter the address and code shown on your desktop.") }
            return
        }
        val payload = PairingPayload(host = host, port = port, code = trimmedCode)
        startConnection(
            Credentials.Pair(payload),
            KnownHost(
                daemonId = "manual:$host:$port",
                hostName = host,
                host = host,
                port = port,
                hostKey = ""
            )
        )
    }

    private fun startConnection(credentials: Credentials, host: KnownHost) {
        connection?.cancel()
        _state.update {
            it.copy(status = ConnectionStatus.Connecting, activeHost = host, error = null)
        }

        connection = scope.launch {
            android.util.Log.i(TAG, "Starting connection collect for credentials=$credentials")
            client.connect(credentials).collect { event ->
                // Deliberately not logged per event. This collector runs on the
                // main thread, and file acks and chunks arrive tens of times a
                // second — stringifying each one's JSON payload here put that
                // work on the UI thread for the length of every transfer. The
                // lifecycle branches below log themselves.
                when (event) {
                    is ConnectionEvent.Connected -> {
                        // A manual pairing only knows a placeholder ID until the
                        // host introduces itself. Re-key the entry to the real
                        // daemon ID so scanning the same desktop later updates
                        // this record instead of adding a duplicate.
                        val realId = event.daemonId.ifEmpty { host.daemonId }
                        if (realId != host.daemonId) store.forget(host.daemonId)

                        // Pin the key the host proved it holds. After a manual
                        // pairing this is the first time we learn it, and every
                        // later resume is checked against it.
                        val saved = host.copy(
                            daemonId = realId,
                            hostName = event.hostName.ifEmpty { host.hostName },
                            hostKey = event.hostKey.ifEmpty { host.hostKey },
                            deviceId = event.deviceId,
                            lastConnected = System.currentTimeMillis()
                        )
                        // The engine only learns how to reach the desktop here;
                        // before the handshake there is no session to write to.
                        transfers.bind { action, payload, blob -> client.send(action, payload, blob) }
                        camera.bind { action, payload, blob -> client.send(action, payload, blob) }
                        store.save(saved)
                        store.lastHostId = saved.daemonId
                        _state.update {
                            it.copy(
                                status = ConnectionStatus.Connected,
                                activeHost = saved,
                                hosts = store.hosts(),
                                error = null
                            )
                        }
                    }

                    is ConnectionEvent.State -> {
                        _state.update { it.copy(host = event.state) }
                        syncArtwork(event.state.media.artworkId)
                    }

                    is ConnectionEvent.Artwork -> {
                        // A late reply for a track that has already changed is
                        // dropped rather than shown against the wrong song.
                        if (event.artwork.artworkId == artworkId) {
                            val decoded = decodeArtwork(event.artwork.data)
                            _state.update { it.copy(artwork = decoded) }
                        }
                    }

                    is ConnectionEvent.FileFrame ->
                        transfers.onFrame(event.action, event.payload, event.blob)

                    is ConnectionEvent.CameraCommand ->
                        camera.onCommand(event.action, event.payload)

                    is ConnectionEvent.Failed -> {
                        transfers.unbind()
                        camera.unbind()
                        _state.update {
                            it.copy(
                                status = ConnectionStatus.Disconnected,
                                activeHost = null,
                                error = event.reason
                            )
                        }
                    }

                    ConnectionEvent.Disconnected -> {
                        transfers.unbind()
                        camera.unbind()
                        _state.update {
                            it.copy(status = ConnectionStatus.Disconnected, activeHost = null)
                        }
                    }
                }
            }
        }
    }

    fun disconnect() {
        connection?.cancel()
        connection = null
        transfers.unbind()
        camera.unbind()
        client.disconnect()
        _state.update { it.copy(status = ConnectionStatus.Disconnected, activeHost = null) }
    }

    /** "Forget system": drops stored keys for a host and leaves it if active. */
    fun forget(host: KnownHost) {
        store.forget(host.daemonId)
        if (_state.value.activeHost?.daemonId == host.daemonId) {
            disconnect()
            artworkId = ""
            _state.update { it.copy(host = HostState(), artwork = null) }
        }
        _state.update { it.copy(hosts = store.hosts()) }
    }

    /**
     * Moves a stored host to the address mDNS just reported it at.
     *
     * DHCP hands the desktop a new lease and every stored endpoint goes stale;
     * without this, discovery would find the host while reconnect kept dialling
     * the old address. The host key is untouched, so the handshake still has to
     * prove the machine at the new address is the same one.
     */
    fun adoptDiscoveredAddress(daemonId: String, host: String, port: Int) {
        val known = store.hosts().find { it.daemonId == daemonId } ?: return
        if (known.host == host && known.port == port) return
        // Only the stored record moves. A live socket keeps the address it
        // dialled; the next connect reads the store, which is where reconnect
        // picks its host from.
        store.save(known.copy(host = host, port = port))
        _state.update { it.copy(hosts = store.hosts()) }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    fun hosts(): List<KnownHost> = store.hosts()

    fun send(action: String, payload: WirePayload? = null) = client.send(action, payload)

    /** Applies a value locally so the control tracks the finger; the host's next broadcast reconciles it. */
    fun patchHost(transform: (HostState) -> HostState) =
        _state.update { it.copy(host = transform(it.host)) }

    /** Requests cover art when the host moves to a track we have not seen. */
    private fun syncArtwork(id: String) {
        if (id == artworkId) return
        artworkId = id
        if (id.isEmpty()) {
            _state.update { it.copy(artwork = null) }
            return
        }
        client.send(Actions.MEDIA_ARTWORK)
    }

    private suspend fun decodeArtwork(base64: String): ImageBitmap? =
        withContext(Dispatchers.Default) {
            runCatching {
                val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }

    companion object {
        private const val TAG = "SwitchboardConnection"
        const val DEFAULT_PORT = 9427

        /**
         * The teardown rule, kept separate so it can be exercised without a
         * socket: the session survives the UI only for as long as something is
         * actually using it.
         */
        internal fun shouldTearDown(uiActive: Boolean, transfers: List<FileProgress>): Boolean =
            !uiActive && transfers.none { !TransferStatus.isTerminal(it.status) }

        @Volatile
        private var instance: SwitchboardConnection? = null

        fun get(context: Context): SwitchboardConnection = instance ?: synchronized(this) {
            instance ?: SwitchboardConnection(context.applicationContext).also { instance = it }
        }
    }
}
