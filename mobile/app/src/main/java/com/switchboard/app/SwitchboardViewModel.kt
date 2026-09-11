package com.switchboard.app

import android.app.Application
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.switchboard.app.data.TransferPreferences
import com.switchboard.app.data.KnownHost
import com.switchboard.app.net.Actions
import com.switchboard.app.net.DiscoveredHost
import com.switchboard.app.net.Display
import com.switchboard.app.net.DisplaySet
import com.switchboard.app.net.HostDiscovery
import com.switchboard.app.net.HostState
import com.switchboard.app.net.InputButton
import com.switchboard.app.net.InputGesture
import com.switchboard.app.net.InputMove
import com.switchboard.app.net.InputScroll
import com.switchboard.app.net.MediaCommand
import com.switchboard.app.net.MixerSet
import com.switchboard.app.net.OutputSet
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
    val transfers: List<FileProgress> = emptyList(),
    /** Desktops seen over mDNS, so pairing does not need a typed IP address. */
    val discovered: List<DiscoveredHost> = emptyList(),
    /** The session is held open past the UI, and redialled when it drops. */
    val alwaysOn: Boolean = false,
    /** Whether the connection service launches on device boot. */
    val startOnBoot: Boolean = true,
    val deckConfig: com.switchboard.app.net.DeckConfig = com.switchboard.app.net.DeckConfig(),
    val installedApps: List<com.switchboard.app.net.InstalledApp> = emptyList()
) {
    val canControlDisplay: Boolean get() = host.capabilities.contains("display")
    val canControlVolume: Boolean get() = host.capabilities.contains("volume")
    val canControlMedia: Boolean get() = host.capabilities.contains("media")
    val canControlMixer: Boolean get() = host.capabilities.contains("mixer")
    val canRouteOutput: Boolean get() = host.capabilities.contains("outputs")
    val canDriveInput: Boolean get() = host.capabilities.contains("input")
    val canLockSystem: Boolean get() = host.capabilities.contains("lock")
    val canControlDeck: Boolean get() = host.capabilities.contains("deck")
    val canControlPower: Boolean get() = host.capabilities.contains("power")
    val canControlMic: Boolean get() = host.capabilities.contains("mic")
    val canRouteInput: Boolean get() = host.capabilities.contains("inputs")
}

/**
 * Screen state for one Activity.
 *
 * The socket is deliberately not owned here: this ViewModel dies with the task
 * the user swipes away, and a transfer must not. It observes
 * [SwitchboardConnection] and adds only what is meaningless without a screen --
 * the scanner flag and the reachability badge.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SwitchboardViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        const val DEFAULT_PORT = SwitchboardConnection.DEFAULT_PORT

        /** One pointer frame per ~60 Hz tick, regardless of the panel's rate. */
        private const val POINTER_FLUSH_MS = 16L

        /** Empty ticks tolerated before the flusher stands down. */
        private const val POINTER_IDLE_TICKS = 30
    }

    private val connection = SwitchboardConnection.get(application)
    private val discovery = HostDiscovery(application)
    private val transfers = TransferEngine.get(application)
    val transferPreferences = TransferPreferences.get(application)

    /**
     * Bumped by [rescanHosts]. Emitting here restarts the browse through
     * flatMapLatest, which is what a manual rescan is: NSD has no "ask again",
     * only a fresh discovery.
     */
    private val rescans = MutableStateFlow(0)

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
                        error = live.error,
                        alwaysOn = live.alwaysOn,
                        startOnBoot = live.startOnBoot,
                        deckConfig = live.deckConfig,
                        installedApps = live.installedApps
                    )
                }
            }
        }

        startLiveProbing()

        viewModelScope.launch {
            transfers.transfers.collect { list -> _uiState.update { it.copy(transfers = list) } }
        }

        // Browsing lives on the ViewModel rather than the connection: it is
        // only meaningful while a screen is up, and it stops with the screen.
        //
        // It also stops the moment a desktop is connected. A connected phone
        // has no use for the list, and an always-on browse keeps the system's
        // local-network prompt coming back at a user who has already chosen
        // their host. The window matches exactly when the pairing screen is on
        // screen -- anything but Connected -- so the list does not blank out
        // mid-attempt. rescanHosts() restarts it on demand.
        viewModelScope.launch {
            connection.state
                .map { it.status != ConnectionStatus.Connected }
                .distinctUntilChanged()
                .combine(rescans) { idle, _ -> idle }
                .flatMapLatest { idle -> if (idle) discovery.hosts() else flowOf(emptyList()) }
                .collect { hosts ->
                    // A paired desktop that moved to a new DHCP lease is
                    // followed here, so reconnect stops dialling the address
                    // it has left.
                    hosts.forEach { connection.adoptDiscoveredAddress(it.daemonId, it.host, it.port) }
                    _uiState.update { it.copy(discovered = hosts) }
                }
        }
    }

    override fun onCleared() {
        connection.releaseUi()
        super.onCleared()
    }

    /** Restarts the mDNS browse. Does nothing while a desktop is connected. */
    fun rescanHosts() {
        rescans.update { it + 1 }
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

    /**
     * Keeps the desktop connected while the app is backgrounded or swiped away.
     *
     * The foreground service this starts can only be started from a visible
     * app, which is where this call comes from, so the flip and the service
     * cannot drift apart.
     */
    fun setAlwaysOn(enabled: Boolean) = connection.setAlwaysOn(enabled)

    /**
     * Toggles whether the background connection service should automatically start
     * up on device reboot when alwaysOn is active.
     */
    fun setStartOnBoot(enabled: Boolean) = connection.setStartOnBoot(enabled)

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
     * Applies one session's level/mute locally so the slider tracks the finger,
     * then sends the wire command. The host echoes the whole mixer list in its
     * reply, which reconciles any discrepancy.
     */
    fun setSessionVolume(sessionId: String, level: Int, muted: Boolean) {
        connection.patchHost { host ->
            host.copy(mixer = host.mixer.map {
                if (it.id == sessionId) it.copy(level = level, muted = muted) else it
            })
        }
        connection.send(Actions.MIXER_SET, MixerSet(sessionId, level, muted))
    }

    /**
     * Moves the desktop's default output endpoint.
     *
     * The tick is patched locally first: Windows takes a moment to move the
     * endpoint, and the next broadcast would otherwise still mark the old
     * device, so the row the user tapped would flick back under their finger.
     */
    fun setAudioOutput(deviceId: String) {
        connection.patchHost { host ->
            host.copy(outputs = host.outputs.map { it.copy(default = it.id == deviceId) })
        }
        connection.send(Actions.OUTPUT_SET, OutputSet(deviceId))
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

    /** Requests the host workstation to lock its session. */
    fun lockSystem() {
        connection.patchHost { it.copy(locked = true) }
        connection.send(Actions.SYSTEM_LOCK)
    }

    fun sendPower(action: String, seconds: Int = 0) =
        connection.sendPower(action, seconds)

    fun setMicVolume(level: Int, muted: Boolean) =
        connection.setMicVolume(level, muted)

    fun setInputDevice(deviceId: String) =
        connection.setInputDevice(deviceId)

    fun sendText(text: String) =
        connection.sendText(text)

    fun sendClipboard(text: String) =
        connection.sendClipboard(text)

    // ---- Air mouse ----
    //
    // Pointer motion arrives from the touch surface at the display's refresh
    // rate, which is well over what a cursor needs and more socket frames than
    // it is worth encrypting. Deltas are summed here and flushed on a frame
    // tick instead: the cursor moves just as smoothly on a fraction of the
    // frames, and the desktop's own sub-pixel accumulator absorbs the rest.

    private var pendingMoveX = 0.0
    private var pendingMoveY = 0.0
    private var moveFlusher: Job? = null

    /** Queues relative motion, in desktop pixels. */
    fun movePointer(dx: Double, dy: Double) {
        synchronized(this) {
            pendingMoveX += dx
            pendingMoveY += dy
        }
        if (moveFlusher?.isActive != true) startMoveFlusher()
    }

    private fun startMoveFlusher() {
        moveFlusher = viewModelScope.launch {
            var idleTicks = 0
            while (isActive) {
                delay(POINTER_FLUSH_MS)
                val (dx, dy) = synchronized(this@SwitchboardViewModel) {
                    val pair = pendingMoveX to pendingMoveY
                    pendingMoveX = 0.0
                    pendingMoveY = 0.0
                    pair
                }
                if (dx == 0.0 && dy == 0.0) {
                    // A finger lifted a moment ago may still be mid-fling in the
                    // recogniser, so the loop idles briefly before standing down
                    // rather than restarting on every stroke.
                    if (++idleTicks > POINTER_IDLE_TICKS) return@launch
                    continue
                }
                idleTicks = 0
                connection.send(Actions.INPUT_MOVE, InputMove(dx, dy))
            }
        }
    }

    /**
     * Presses, releases, clicks, or double-clicks a mouse button. Any queued
     * motion is flushed first: a click that overtakes the move that positioned
     * the cursor lands in the wrong place.
     */
    fun mouseButton(button: String, action: String) {
        flushPointer()
        connection.send(Actions.INPUT_BUTTON, InputButton(button, action))
    }

    /** Turns the wheel by a number of notches. */
    fun scroll(dx: Double, dy: Double, ctrl: Boolean = false) {
        connection.send(Actions.INPUT_SCROLL, InputScroll(dx, dy, ctrl))
    }

    /** Triggers one named shell gesture on the desktop. */
    fun shellGesture(name: String) {
        connection.send(Actions.INPUT_GESTURE, InputGesture(name))
    }

    private fun flushPointer() {
        val (dx, dy) = synchronized(this) {
            val pair = pendingMoveX to pendingMoveY
            pendingMoveX = 0.0
            pendingMoveY = 0.0
            pair
        }
        if (dx != 0.0 || dy != 0.0) connection.send(Actions.INPUT_MOVE, InputMove(dx, dy))
    }

    // ---- Files ----

    fun sendFile(uri: Uri) = transfers.send(uri)

    fun sendFiles(uris: List<Uri>) = transfers.sendAll(uris)

    fun sendFolder(treeUri: Uri) = transfers.sendFolder(treeUri)

    fun controlTransfer(transferId: String, action: String) = transfers.control(transferId, action)

    private fun patchDisplay(id: String, transform: (Display) -> Display) {
        connection.patchHost { host ->
            host.copy(displays = host.displays.map { if (it.id == id) transform(it) else it })
        }
    }

    // ---- Stream Deck Neo ----

    fun triggerDeckAction(keyIndex: Int, action: com.switchboard.app.net.DeckAction, pageId: String? = null) {
        connection.sendDeckAction(keyIndex, action, pageId)
    }

    fun saveDeckConfig(config: com.switchboard.app.net.DeckConfig) {
        connection.saveDeckConfig(config)
    }

    fun refreshInstalledApps() {
        connection.refreshInstalledApps()
    }
}
