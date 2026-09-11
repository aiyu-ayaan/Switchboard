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
import com.switchboard.app.net.HostDiscovery
import com.switchboard.app.net.SwitchboardClient
import com.switchboard.app.net.WirePayload
import com.switchboard.app.net.SwitchboardJson
import com.switchboard.app.net.TransferStatus
import com.switchboard.app.net.Volume
import com.switchboard.app.camera.CameraController
import com.switchboard.app.transfer.TransferEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull

/** Everything about the live session that outlives any one Activity. */
data class ConnectionState(
    val status: ConnectionStatus = ConnectionStatus.Disconnected,
    val hosts: List<KnownHost> = emptyList(),
    val activeHost: KnownHost? = null,
    val host: HostState = HostState(),
    /** Cover art for [HostState.media], decoded once per track. */
    val artwork: ImageBitmap? = null,
    val error: String? = null,
    /** The session is held open past the UI, and redialled when it drops. */
    val alwaysOn: Boolean = false,
    /** Whether the connection service launches on device boot. */
    val startOnBoot: Boolean = true,
    val deckConfig: com.switchboard.app.net.DeckConfig = com.switchboard.app.net.DeckConfig(),
    val installedApps: List<com.switchboard.app.net.InstalledApp> = emptyList()
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

    private val appContext = context.applicationContext
    private val store = HostStore(context)
    private val transfers = TransferEngine.get(context)
    val camera = CameraController.get(context)
    private val client = SwitchboardClient(store.identity, "${Build.MANUFACTURER} ${Build.MODEL}")
    private val discovery = HostDiscovery(context)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(
        ConnectionState(hosts = store.hosts(), alwaysOn = store.alwaysOn, startOnBoot = store.startOnBoot)
    )
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
        // The setting outlives the process. This constructor also runs when the
        // system restarts the service on its own, and the notification has to
        // come back with it or the process loses its reason to be resident.
        if (store.alwaysOn && last != null) ConnectionService.start(appContext)
    }

    // ---- Always-on ----

    /**
     * Turns the persistent session on or off.
     *
     * Enabling it only starts the service; the reconnect loop is armed by the
     * next attempt, so a user who flips this while disconnected still has to
     * pick a desktop once. Disabling it drops the notification and lets the
     * ordinary "no UI, nothing moving" teardown apply again.
     */
    fun setAlwaysOn(enabled: Boolean) {
        if (store.alwaysOn == enabled) return
        store.alwaysOn = enabled
        _state.update { it.copy(alwaysOn = enabled) }

        if (enabled) {
            ConnectionService.start(appContext)
            // Flipping this on from the pairing screen should reach the last
            // desktop rather than wait for a tap. The job is checked rather
            // than the field: a session that dropped while always-on was off
            // leaves a finished job behind, and that is exactly the case the
            // user is switching this on to fix.
            if (connection?.isActive != true) {
                val last = store.lastHostId?.let { id -> store.hosts().find { it.daemonId == id } }
                    ?: store.hosts().firstOrNull()
                last?.let(::connect)
            }
        } else {
            ConnectionService.stop(appContext)
            if (shouldTearDown(uiHolders > 0, transfers.transfers.value, alwaysOn = false)) {
                disconnect()
            }
        }
    }

    /**
     * Toggles whether the background connection service should automatically start
     * up on device reboot when alwaysOn is active.
     */
    fun setStartOnBoot(enabled: Boolean) {
        if (store.startOnBoot == enabled) return
        store.startOnBoot = enabled
        _state.update { it.copy(startOnBoot = enabled) }
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
                    if (shouldTearDown(uiHolders > 0, list, store.alwaysOn)) disconnect()
                }
            }
        }
    }

    /**
     * The last screen has gone. Anything still moving keeps the session, and so
     * does always-on; an idle app that asked for neither must not sit on a
     * socket and a foreground service forever, so the connection goes now.
     */
    fun releaseUi() {
        uiHolders--
        if (shouldTearDown(uiHolders > 0, transfers.transfers.value, store.alwaysOn)) disconnect()
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
            var attemptCredentials = credentials
            var attemptHost = host
            var failures = 0
            var moves = 0

            while (isActive) {
                android.util.Log.i(TAG, "Starting connection collect for credentials=$attemptCredentials")
                var handshook = false

                client.connect(attemptCredentials).collect { event ->
                    if (event is ConnectionEvent.Connected) handshook = true
                    attemptHost = handle(event, attemptHost)
                }

                // A pairing code is spent on first use, so a pairing that never
                // reached a handshake cannot be usefully repeated -- redialling
                // would only burn attempts against the host's rate limiter. The
                // user has to scan again.
                if (!handshook && attemptCredentials is Credentials.Pair) break

                if (handshook) moves = 0

                // A resume that never handshook is usually dialling an address
                // the desktop has left behind: the stored record holds whatever
                // it had on the network the two last met on, and joining another
                // Wi-Fi changes it. Ask mDNS where this daemon answers now and
                // retry there immediately. This runs ahead of the always-on
                // check on purpose -- a paired desktop must stay reachable from
                // any network, not only for users who left always-on on.
                if (!handshook && moves < MAX_MOVES) {
                    val moved = rediscover(attemptHost)
                    if (moved != null) {
                        moves++
                        adoptDiscoveredAddress(moved.daemonId, moved.host, moved.port)
                        attemptHost = moved
                        attemptCredentials = Credentials.Resume(moved)
                        _state.update {
                            it.copy(status = ConnectionStatus.Connecting, activeHost = moved)
                        }
                        continue
                    }
                }

                // The flow completes only once the socket is gone. Without
                // always-on that is the end of the session; with it, the drop
                // is the thing this loop exists to paper over.
                if (!store.alwaysOn) break

                failures = if (handshook) 0 else failures + 1

                // Re-read the record before redialling: mDNS may have moved the
                // desktop to a new lease while this attempt failed against the
                // old address. Resume rather than Pair, because by this point
                // the host key is pinned and the code is gone.
                attemptHost = store.hosts().find { it.daemonId == attemptHost.daemonId } ?: attemptHost
                attemptCredentials = Credentials.Resume(attemptHost)
                _state.update {
                    it.copy(status = ConnectionStatus.Connecting, activeHost = attemptHost)
                }
                delay(retryDelayMs(failures))
            }
        }
    }

    /**
     * Applies one event, returning the host record it leaves in force.
     *
     * Split out of the collect so the retry loop above can wrap it: a
     * handshake rewrites the record it was dialled with, and the next attempt
     * has to start from that rewritten one.
     */
    private suspend fun handle(event: ConnectionEvent, host: KnownHost): KnownHost {
        // Deliberately not logged per event. This runs on the main thread, and
        // file acks and chunks arrive tens of times a second -- stringifying
        // each one's JSON payload here put that work on the UI thread for the
        // length of every transfer. The lifecycle branches below log themselves.
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
                client.send(Actions.DECK_GET)
                client.send(Actions.SYSTEM_APPS)
                _state.update {
                    it.copy(
                        status = ConnectionStatus.Connected,
                        activeHost = saved,
                        hosts = store.hosts(),
                        error = null
                    )
                }
                return saved
            }

            is ConnectionEvent.State -> {
                _state.update { it.copy(host = event.state) }
                syncArtwork(event.state.media.artworkId)
            }

            is ConnectionEvent.DeckState -> {
                _state.update { it.copy(deckConfig = event.config) }
            }

            is ConnectionEvent.InstalledApps -> {
                _state.update { it.copy(installedApps = event.apps) }
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
        return host
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
    /**
     * Where this daemon answers right now, or null if it has not moved or the
     * network will not say.
     *
     * The daemon id is the identity that survives a change of address, so the
     * lookup matches on it. Nothing is trusted from the record itself: the
     * resume handshake is still checked against the pinned host key, so a
     * machine advertising a stolen id gets a failed handshake, not a session.
     */
    private suspend fun rediscover(host: KnownHost): KnownHost? {
        val found = withTimeoutOrNull(REDISCOVER_TIMEOUT_MS) {
            discovery.hosts()
                .mapNotNull { list -> list.firstOrNull { it.daemonId == host.daemonId } }
                .firstOrNull()
        } ?: return null
        if (found.host == host.host && found.port == host.port) return null
        return host.copy(host = found.host, port = found.port)
    }

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

    /** Surfaces a failure that happened on the phone rather than on the socket. */
    fun reportError(message: String) = _state.update { it.copy(error = message) }

    fun hosts(): List<KnownHost> = store.hosts()

    fun send(action: String, payload: WirePayload? = null) = client.send(action, payload)

    fun sendDeckAction(keyIndex: Int, action: com.switchboard.app.net.DeckAction, pageId: String? = null) {
        send(Actions.DECK_ACTION, com.switchboard.app.net.DeckActionRequest(pageId = pageId, keyIndex = keyIndex, action = action))
    }

    fun saveDeckConfig(config: com.switchboard.app.net.DeckConfig) {
        _state.update { it.copy(deckConfig = config) }
        send(Actions.DECK_SET, config)
    }

    fun refreshInstalledApps() {
        send(Actions.SYSTEM_APPS)
    }

    fun sendPower(action: String, seconds: Int = 0) {
        client.sendPower(action, seconds)
    }

    fun setMicVolume(level: Int, muted: Boolean) {
        patchHost { it.copy(mic = Volume(level, muted)) }
        client.setMicVolume(level, muted)
    }

    fun setInputDevice(deviceId: String) {
        patchHost { host ->
            host.copy(inputs = host.inputs.map { it.copy(default = it.id == deviceId) })
        }
        client.setInputDevice(deviceId)
    }

    fun sendText(text: String) {
        client.sendText(text)
    }

    fun sendClipboard(text: String) {
        client.sendClipboard(text)
    }

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

        /** How long a failed resume waits for mDNS to name the host's new address. */
        private const val REDISCOVER_TIMEOUT_MS = 4_000L

        /**
         * Address changes chased within one session. A record that flaps between
         * two addresses would otherwise redial forever without ever backing off.
         */
        private const val MAX_MOVES = 3

        /**
         * The teardown rule, kept separate so it can be exercised without a
         * socket: the session survives the UI only for as long as something is
         * actually using it.
         */
        internal fun shouldTearDown(
            uiActive: Boolean,
            transfers: List<FileProgress>,
            alwaysOn: Boolean = false
        ): Boolean =
            !uiActive &&
                !alwaysOn &&
                transfers.none { !TransferStatus.isTerminal(it.status) }

        /**
         * Backoff between reconnect attempts, doubling to a 30s ceiling.
         *
         * A desktop that is simply switched off must not be dialled in a tight
         * loop for hours, and the ceiling is low enough that the phone is back
         * within half a minute of it waking up.
         */
        internal fun retryDelayMs(consecutiveFailures: Int): Long =
            (1_000L shl consecutiveFailures.coerceIn(0, 5)).coerceAtMost(30_000L)

        @Volatile
        private var instance: SwitchboardConnection? = null

        fun get(context: Context): SwitchboardConnection = instance ?: synchronized(this) {
            instance ?: SwitchboardConnection(context.applicationContext).also { instance = it }
        }
    }
}
