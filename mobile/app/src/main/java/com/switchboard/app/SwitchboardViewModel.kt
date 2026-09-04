package com.switchboard.app

import android.app.Application
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.switchboard.app.data.TransferPreferences
import com.switchboard.app.data.KnownHost
import com.switchboard.app.net.Actions
import com.switchboard.app.net.Display
import com.switchboard.app.net.DisplaySet
import com.switchboard.app.net.HostState
import com.switchboard.app.net.MediaCommand
import com.switchboard.app.net.Playback
import com.switchboard.app.net.SwitchboardClient
import com.switchboard.app.net.FileProgress
import com.switchboard.app.net.Volume
import com.switchboard.app.transfer.TransferEngine
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    val scanning: Boolean = false,
    /** Live transfers first, then the terminal ones the Files history shows. */
    val transfers: List<FileProgress> = emptyList()
) {
    val canControlDisplay: Boolean get() = host.capabilities.contains("display")
    val canControlVolume: Boolean get() = host.capabilities.contains("volume")
    val canControlMedia: Boolean get() = host.capabilities.contains("media")
}

/**
 * Screen state for one Activity.
 *
 * The socket is deliberately not owned here: this ViewModel dies with the task
 * the user swipes away, and a transfer must not. It observes
 * [SwitchboardConnection] and adds only what is meaningless without a screen --
 * the scanner flag and the reachability badge.
 */
class SwitchboardViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        const val DEFAULT_PORT = SwitchboardConnection.DEFAULT_PORT
    }

    private val connection = SwitchboardConnection.get(application)
    private val transfers = TransferEngine.get(application)
    val transferPreferences = TransferPreferences(application)

    private val _uiState = MutableStateFlow(UiState(hosts = connection.hosts()))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        connection.retainUi()

        viewModelScope.launch {
            connection.state.collect { live ->
                _uiState.update {
                    it.copy(
                        status = live.status,
                        hosts = live.hosts,
                        activeHost = live.activeHost,
                        host = live.host,
                        artwork = live.artwork,
                        error = live.error
                    )
                }
            }
        }

        startLiveProbing()

        viewModelScope.launch {
            transfers.transfers.collect { list -> _uiState.update { it.copy(transfers = list) } }
        }
    }

    override fun onCleared() {
        connection.releaseUi()
        super.onCleared()
    }

    fun setScanning(scanning: Boolean) {
        connection.clearError()
        _uiState.update { it.copy(scanning = scanning, error = null) }
    }

    fun pair(payloadJson: String) {
        _uiState.update { it.copy(scanning = false) }
        connection.pair(payloadJson)
    }

    fun connect(host: KnownHost) {
        _uiState.update { it.copy(scanning = false) }
        connection.connect(host)
    }

    fun pairManually(address: String, code: String) {
        _uiState.update { it.copy(scanning = false) }
        connection.pairManually(address, code)
    }

    fun disconnect() {
        connection.disconnect()
        triggerLiveProbe()
    }

    /** "Forget system": drops stored keys for a host and leaves it if active. */
    fun forget(host: KnownHost) {
        connection.forget(host)
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
        val currentHosts = connection.hosts()
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
        connection.send(Actions.DISPLAY_BRIGHTNESS, DisplaySet(display.id, value))
    }

    fun setContrast(display: Display, value: Int) {
        patchDisplay(display.id) { it.copy(contrast = value) }
        connection.send(Actions.DISPLAY_CONTRAST, DisplaySet(display.id, value))
    }

    fun setVolume(level: Int, muted: Boolean) {
        connection.patchHost { it.copy(volume = Volume(level, muted)) }
        connection.send(Actions.VOLUME_SET, Volume(level, muted))
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
            connection.patchHost { it.copy(media = it.media.copy(status = optimistic)) }
        }
        connection.send(Actions.MEDIA_COMMAND, MediaCommand(action))
    }

    // ---- Files ----

    fun sendFile(uri: Uri) = transfers.send(uri)

    fun controlTransfer(transferId: String, action: String) = transfers.control(transferId, action)

    private fun patchDisplay(id: String, transform: (Display) -> Display) {
        connection.patchHost { host ->
            host.copy(displays = host.displays.map { if (it.id == id) transform(it) else it })
        }
    }
}
