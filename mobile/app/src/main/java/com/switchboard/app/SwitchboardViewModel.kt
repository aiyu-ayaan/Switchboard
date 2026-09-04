package com.switchboard.app

import android.app.Application
import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.switchboard.app.data.HostStore
import com.switchboard.app.data.KnownHost
import com.switchboard.app.net.Actions
import com.switchboard.app.net.ConnectionEvent
import com.switchboard.app.net.Credentials
import com.switchboard.app.net.Display
import com.switchboard.app.net.DisplaySet
import com.switchboard.app.net.HostState
import com.switchboard.app.net.MediaCommand
import com.switchboard.app.net.Playback
import com.switchboard.app.net.PairingPayload
import com.switchboard.app.net.SwitchboardClient
import com.switchboard.app.net.SwitchboardJson
import com.switchboard.app.net.Volume
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ConnectionStatus { Disconnected, Connecting, Connected }

data class UiState(
    val status: ConnectionStatus = ConnectionStatus.Disconnected,
    val hosts: List<KnownHost> = emptyList(),
    val liveHostsCount: Int = 0,
    val liveHostIds: Set<String> = emptySet(),
    val activeHost: KnownHost? = null,
    val host: HostState = HostState(),
    /** Cover art for [HostState.media], decoded once per track. */
    val artwork: ImageBitmap? = null,
    val error: String? = null,
    val scanning: Boolean = false
) {
    val canControlDisplay: Boolean get() = host.capabilities.contains("display")
    val canControlVolume: Boolean get() = host.capabilities.contains("volume")
    val canControlMedia: Boolean get() = host.capabilities.contains("media")
}

class SwitchboardViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        const val DEFAULT_PORT = 9427
    }

    private val store = HostStore(application)
    private val client = SwitchboardClient(store.identity, "${Build.MANUFACTURER} ${Build.MODEL}")

    private val _uiState = MutableStateFlow(UiState(hosts = store.hosts()))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var connection: Job? = null

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

        startLiveProbing()
    }

    fun setScanning(scanning: Boolean) = _uiState.update { it.copy(scanning = scanning, error = null) }

    /** Handles a scanned or manually resolved QR payload. */
    fun pair(payloadJson: String) {
        val payload = runCatching {
            SwitchboardJson.decodeFromString(PairingPayload.serializer(), payloadJson)
        }.getOrNull()

        if (payload == null) {
            _uiState.update { it.copy(scanning = false, error = "That is not a Switchboard code.") }
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
            _uiState.update { it.copy(error = "Enter the address and code shown on your desktop.") }
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
        _uiState.update {
            it.copy(
                status = ConnectionStatus.Connecting,
                activeHost = host,
                error = null,
                scanning = false
            )
        }

        connection = viewModelScope.launch {
            android.util.Log.i("SwitchboardVM", "Starting connection collect for credentials=$credentials")
            client.connect(credentials).collect { event ->
                android.util.Log.i("SwitchboardVM", "Collected event: $event")
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
                        store.save(saved)
                        store.lastHostId = saved.daemonId
                        _uiState.update {
                            it.copy(
                                status = ConnectionStatus.Connected,
                                activeHost = saved,
                                hosts = store.hosts(),
                                error = null
                            )
                        }
                    }

                    is ConnectionEvent.State -> {
                        _uiState.update { it.copy(host = event.state) }
                        syncArtwork(event.state.media.artworkId)
                    }

                    is ConnectionEvent.Artwork -> {
                        // A late reply for a track that has already changed is
                        // dropped rather than shown against the wrong song.
                        if (event.artwork.artworkId == artworkId) {
                            val decoded = decodeArtwork(event.artwork.data)
                            _uiState.update { it.copy(artwork = decoded) }
                        }
                    }

                    is ConnectionEvent.Failed ->
                        _uiState.update {
                            it.copy(
                                status = ConnectionStatus.Disconnected,
                                activeHost = null,
                                error = event.reason
                            )
                        }

                    ConnectionEvent.Disconnected ->
                        _uiState.update {
                            it.copy(
                                status = ConnectionStatus.Disconnected,
                                activeHost = null
                            )
                        }
                }
            }
        }
    }

    fun disconnect() {
        connection?.cancel()
        connection = null
        client.disconnect()
        _uiState.update { it.copy(status = ConnectionStatus.Disconnected, activeHost = null) }
        triggerLiveProbe()
    }

    /** "Forget system": drops stored keys for a host and leaves it if active. */
    fun forget(host: KnownHost) {
        store.forget(host.daemonId)
        if (_uiState.value.activeHost?.daemonId == host.daemonId) {
            disconnect()
            artworkId = ""
            _uiState.update { it.copy(activeHost = null, host = HostState(), artwork = null) }
        }
        _uiState.update { it.copy(hosts = store.hosts()) }
        triggerLiveProbe()
    }

    private var liveProbeJob: Job? = null

    private fun startLiveProbing() {
        liveProbeJob?.cancel()
        liveProbeJob = viewModelScope.launch {
            while (isActive) {
                probeLiveHosts()
                delay(3000)
            }
        }
    }

    fun triggerLiveProbe() {
        viewModelScope.launch {
            probeLiveHosts()
        }
    }

    private suspend fun probeLiveHosts() {
        val currentHosts = store.hosts()
        if (currentHosts.isEmpty()) {
            _uiState.update { it.copy(liveHostsCount = 0, liveHostIds = emptySet()) }
            return
        }

        val active = _uiState.value.activeHost
        val isConnected = _uiState.value.status == ConnectionStatus.Connected

        val liveIds = coroutineScope {
            currentHosts.map { host ->
                async(Dispatchers.IO) {
                    if (isConnected && active?.daemonId == host.daemonId) {
                        host.daemonId to true
                    } else {
                        host.daemonId to isHostReachable(host)
                    }
                }
            }.awaitAll()
                .filter { it.second }
                .map { it.first }
                .toSet()
        }

        _uiState.update {
            it.copy(
                liveHostsCount = liveIds.size,
                liveHostIds = liveIds
            )
        }
    }

    private fun isHostReachable(host: KnownHost): Boolean {
        val candidates = buildList {
            add(host.host to host.port)
            if (SwitchboardClient.isEmulator()) {
                add("10.0.2.2" to host.port)
            }
            add("127.0.0.1" to host.port)
        }.distinct()

        for ((targetHost, targetPort) in candidates) {
            // An open TCP port is not proof the daemon is up: an adb reverse
            // tunnel, or any forwarder, accepts the connection and then drops
            // it. Only an actual HTTP reply means Switchboard is answering, so
            // the badge stops reading "Live" against a dead desktop. Any status
            // code counts -- the daemon 404s an unknown path, which is a reply.
            val connection = try {
                (URL("http://$targetHost:$targetPort/").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 750
                    readTimeout = 750
                    requestMethod = "GET"
                }
            } catch (_: Exception) {
                continue
            }
            try {
                connection.responseCode
                return true
            } catch (_: Exception) {
                // Try next candidate
            } finally {
                connection.disconnect()
            }
        }
        return false
    }

    // ---- Controls ----
    //
    // Each setter applies the value locally first so the slider tracks the
    // finger, then sends the command. The host echoes a full state broadcast,
    // which reconciles the two.

    fun setBrightness(display: Display, value: Int) {
        patchDisplay(display.id) { it.copy(brightness = value) }
        client.send(Actions.DISPLAY_BRIGHTNESS, DisplaySet(display.id, value))
    }

    fun setContrast(display: Display, value: Int) {
        patchDisplay(display.id) { it.copy(contrast = value) }
        client.send(Actions.DISPLAY_CONTRAST, DisplaySet(display.id, value))
    }

    fun setVolume(level: Int, muted: Boolean) {
        _uiState.update { it.copy(host = it.host.copy(volume = Volume(level, muted))) }
        client.send(Actions.VOLUME_SET, Volume(level, muted))
    }

    /**
     * Sends a transport command, moving the button to its new state at once.
     * The host reports the truth within a poll, which reconciles the two; the
     * alternative is a play button that looks dead for a second after a tap.
     */
    fun media(action: String) {
        val optimistic = when (action) {
            "play" -> Playback.PLAYING
            "pause" -> Playback.PAUSED
            "stop" -> Playback.STOPPED
            "toggle" -> if (_uiState.value.host.media.isPlaying) Playback.PAUSED else Playback.PLAYING
            else -> null // skips leave playback state alone
        }
        if (optimistic != null) {
            _uiState.update {
                it.copy(host = it.host.copy(media = it.host.media.copy(status = optimistic)))
            }
        }
        client.send(Actions.MEDIA_COMMAND, MediaCommand(action))
    }

    /** Requests cover art when the host moves to a track we have not seen. */
    private fun syncArtwork(id: String) {
        if (id == artworkId) return
        artworkId = id
        if (id.isEmpty()) {
            _uiState.update { it.copy(artwork = null) }
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

    private fun patchDisplay(id: String, transform: (Display) -> Display) {
        _uiState.update { state ->
            state.copy(
                host = state.host.copy(
                    displays = state.host.displays.map { if (it.id == id) transform(it) else it }
                )
            )
        }
    }
}
