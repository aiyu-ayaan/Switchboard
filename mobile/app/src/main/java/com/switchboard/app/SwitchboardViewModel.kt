package com.switchboard.app

import android.app.Application
import android.os.Build
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
import com.switchboard.app.net.PairingPayload
import com.switchboard.app.net.SwitchboardClient
import com.switchboard.app.net.SwitchboardJson
import com.switchboard.app.net.Volume
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ConnectionStatus { Disconnected, Connecting, Connected }

data class UiState(
    val status: ConnectionStatus = ConnectionStatus.Disconnected,
    val hosts: List<KnownHost> = emptyList(),
    val activeHost: KnownHost? = null,
    val host: HostState = HostState(),
    val error: String? = null,
    val scanning: Boolean = false
) {
    val canControlVolume: Boolean get() = host.capabilities.contains("volume")
    val canControlMedia: Boolean get() = host.capabilities.contains("media")
}

private const val DEFAULT_PORT = 9427

class SwitchboardViewModel(application: Application) : AndroidViewModel(application) {

    private val store = HostStore(application)
    private val client = SwitchboardClient(store.identity, "${Build.MANUFACTURER} ${Build.MODEL}")

    private val _uiState = MutableStateFlow(UiState(hosts = store.hosts()))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var connection: Job? = null

    init {
        // Reconnect to the host used last, so opening the app lands on controls
        // rather than on a picker.
        val last = store.lastHostId?.let { id -> store.hosts().find { it.daemonId == id } }
            ?: store.hosts().firstOrNull()
        last?.let(::connect)
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
            client.connect(credentials).collect { event ->
                when (event) {
                    is ConnectionEvent.Connected -> {
                        // Pin the key the host proved it holds. After a manual
                        // pairing this is the first time we learn it, and every
                        // later resume is checked against it.
                        val saved = host.copy(
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

                    is ConnectionEvent.State ->
                        _uiState.update { it.copy(host = event.state) }

                    is ConnectionEvent.Failed ->
                        _uiState.update {
                            it.copy(status = ConnectionStatus.Disconnected, error = event.reason)
                        }

                    ConnectionEvent.Disconnected ->
                        _uiState.update { it.copy(status = ConnectionStatus.Disconnected) }
                }
            }
        }
    }

    fun disconnect() {
        connection?.cancel()
        connection = null
        client.disconnect()
        _uiState.update { it.copy(status = ConnectionStatus.Disconnected) }
    }

    /** "Forget system": drops stored keys for a host and leaves it if active. */
    fun forget(host: KnownHost) {
        store.forget(host.daemonId)
        if (_uiState.value.activeHost?.daemonId == host.daemonId) {
            disconnect()
            _uiState.update { it.copy(activeHost = null, host = HostState()) }
        }
        _uiState.update { it.copy(hosts = store.hosts()) }
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

    fun media(action: String) = client.send(Actions.MEDIA_COMMAND, MediaCommand(action))

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
