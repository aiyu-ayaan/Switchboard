package com.switchboard.app

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.switchboard.app.data.ThemePreferences
import com.switchboard.app.ui.ConnectionInfoSheet
import com.switchboard.app.ui.HomeScreen
import com.switchboard.app.ui.PairingScreen
import com.switchboard.app.ui.Section
import com.switchboard.app.ui.SectionActions
import com.switchboard.app.ui.SectionScreen
import com.switchboard.app.ui.SettingsScreen
import com.switchboard.app.ui.ShareTargetSheet
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.switchboard.app.ui.TouchpadActions
import com.switchboard.app.ui.UnlockConfig
import com.switchboard.app.ui.SwitchboardTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MainActivity : FragmentActivity() {
    private val pendingSharedUris = MutableStateFlow<List<Uri>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShareIntent(intent)
        setContent {
            val context = LocalContext.current
            val themePreferences = remember { ThemePreferences(context) }
            val themeConfig by themePreferences.config.collectAsState()

            SwitchboardTheme(themeConfig = themeConfig) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SwitchboardApp(
                        themePreferences = themePreferences,
                        sharedUrisFlow = pendingSharedUris
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        val uris = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (uri != null) uris.add(uri)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                if (!list.isNullOrEmpty()) uris.addAll(list)
            }
        }
        if (uris.isNotEmpty()) {
            pendingSharedUris.value = uris
        }
    }
}

sealed interface AppScreen {
    data object Main : AppScreen
    data class Detail(val section: Section) : AppScreen
    data object Settings : AppScreen
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwitchboardApp(
    viewModel: SwitchboardViewModel = viewModel(),
    themePreferences: ThemePreferences,
    sharedUrisFlow: StateFlow<List<Uri>?> = MutableStateFlow(null)
) {
    val state by viewModel.uiState.collectAsState()
    val themeConfig by themePreferences.config.collectAsState()
    val transferConfig by viewModel.transferPreferences.config.collectAsState()
    val sharedUris by sharedUrisFlow.collectAsState()

    val scanner = rememberLauncherForScan { contents ->
        if (contents != null) viewModel.pair(contents) else viewModel.setScanning(false)
    }

    val connected = state.status == ConnectionStatus.Connected

    // The biometric prompt hangs off a FragmentActivity window, so the unlock
    // path needs the host Activity rather than a bare Context.
    val activity = androidx.activity.compose.LocalActivity.current as? FragmentActivity

    var currentScreen by remember { mutableStateOf<AppScreen>(AppScreen.Main) }
    var showConnectionInfo by remember { mutableStateOf(false) }
    var showLockConfirmDialog by remember { mutableStateOf(false) }
    val homeListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

    // Files shared in from another app, held until the user names a desktop.
    var shareFiles by remember { mutableStateOf<List<Uri>?>(null) }
    // The host that was picked, while the connection to it is still being made.
    var shareTarget by remember { mutableStateOf<String?>(null) }
    // Whether that attempt has actually started. Without it, the tap and the
    // status race: `connect` publishes Connecting asynchronously, so the first
    // pass still reads Disconnected and would cancel the send that was just
    // asked for.
    var shareConnecting by remember { mutableStateOf(false) }

    LaunchedEffect(sharedUris) {
        val uris = sharedUris ?: return@LaunchedEffect
        shareFiles = uris
        shareTarget = null
        shareConnecting = false
        (sharedUrisFlow as? MutableStateFlow)?.value = null
    }

    // The send waits for the socket rather than racing it: a host picked while
    // the phone was talking to a different desktop is not connected yet at the
    // moment of the tap, and files queued against the old session would go to
    // the wrong machine.
    LaunchedEffect(shareTarget, state.status, state.activeHost) {
        val target = shareTarget ?: return@LaunchedEffect
        val files = shareFiles ?: return@LaunchedEffect
        if (state.status == ConnectionStatus.Connecting) shareConnecting = true
        when {
            connected && state.activeHost?.daemonId == target -> {
                viewModel.sendFiles(files)
                shareFiles = null
                shareTarget = null
                shareConnecting = false
                currentScreen = AppScreen.Detail(Section.Files)
            }
            // The attempt ended without arriving: back to the list, with the
            // files still in hand, rather than silently dropping them.
            shareConnecting && state.status == ConnectionStatus.Disconnected -> {
                shareTarget = null
                shareConnecting = false
            }
        }
    }

    LaunchedEffect(connected) {
        if (!connected) {
            showConnectionInfo = false
            showLockConfirmDialog = false
            if (currentScreen is AppScreen.Detail) {
                currentScreen = AppScreen.Main
            }
        }
    }

    BackHandler(enabled = currentScreen !is AppScreen.Main) {
        currentScreen = AppScreen.Main
    }

    // The lock control lives on the home surfaces only, so its click needs the
    // freshest state rather than whatever composition built these actions.
    val latestState = rememberUpdatedState(state)

    val actions = remember(viewModel, transferConfig.rateUnit) {
        SectionActions(
            onBrightness = viewModel::setBrightness,
            onContrast = viewModel::setContrast,
            onVolume = viewModel::setVolume,
            onMixerSession = viewModel::setSessionVolume,
            onAudioOutput = viewModel::setAudioOutput,
            onMedia = viewModel::media,
            onSendFile = viewModel::sendFile,
            onSendFiles = viewModel::sendFiles,
            onSendFolder = { uri -> viewModel.sendFolder(uri) },
            touchpad = TouchpadActions(
                onMove = viewModel::movePointer,
                onButton = viewModel::mouseButton,
                onScroll = viewModel::scroll,
                onGesture = viewModel::shellGesture
            ),
            onTransferControl = viewModel::controlTransfer,
            rateUnit = transferConfig.rateUnit,
            onLockSystem = {
                // No confirm dialog on the unlock path: the fingerprint
                // prompt is the confirmation, and a dialog in front of it
                // would only be a tap between the user and their finger.
                val live = latestState.value
                if (live.host.locked && live.canUnlockSystem && activity != null) {
                    viewModel.unlockSystem(activity)
                } else {
                    showLockConfirmDialog = true
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (currentScreen !is AppScreen.Main) {
                        IconButton(
                            onClick = { currentScreen = AppScreen.Main },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    }
                },
                title = {
                    AnimatedContent(
                        targetState = currentScreen,
                        transitionSpec = {
                            fadeIn(tween(200)) togetherWith fadeOut(tween(200))
                        },
                        label = "topBarTitle"
                    ) { screen ->
                        Column {
                            when (screen) {
                                is AppScreen.Settings -> {
                                    Text(
                                        text = "Settings",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Appearance & About",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                is AppScreen.Detail -> {
                                    Text(
                                        text = screen.section.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = state.activeHost?.hostName ?: "",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                is AppScreen.Main -> {
                                    if (connected) {
                                        Text(
                                            text = state.activeHost?.hostName ?: "Switchboard",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "Connected",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    } else {
                                        Text(
                                            text = "Switchboard",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "Connected ${state.liveHostsCount}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = if (state.liveHostsCount > 0) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                actions = {
                    if (currentScreen is AppScreen.Main) {
                        if (state.status == ConnectionStatus.Connecting) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(20.dp)
                                    .padding(end = 4.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(10.dp))
                        }

                        if (connected) {
                            IconButton(
                                onClick = { viewModel.disconnect() },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Filled.SwapHoriz, contentDescription = "Switch desktop")
                            }

                            IconButton(
                                onClick = { showConnectionInfo = true },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Filled.Info, contentDescription = "Connection info")
                            }
                        } else {
                            IconButton(
                                onClick = { scanner(Unit) },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Filled.QrCodeScanner, contentDescription = "Scan pairing code")
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            AnimatedContent(
                targetState = currentScreen,
                transitionSpec = {
                    if (targetState is AppScreen.Main) {
                        (slideInHorizontally(tween(280)) { -it / 3 } + fadeIn(tween(280)))
                            .togetherWith(slideOutHorizontally(tween(280)) { it } + fadeOut(tween(280)))
                    } else {
                        (slideInHorizontally(tween(280)) { it } + fadeIn(tween(280)))
                            .togetherWith(slideOutHorizontally(tween(280)) { -it / 3 } + fadeOut(tween(280)))
                    }
                },
                label = "screenTransition"
            ) { targetScreen ->
                when (targetScreen) {
                    is AppScreen.Settings -> {
                        SettingsScreen(
                            themeConfig = themeConfig,
                            transferConfig = transferConfig,
                            unlockConfig = UnlockConfig(
                                availableOnPhone = state.unlockAvailableOnPhone,
                                enrolled = state.unlockEnrolled,
                                hostAccepts = connected && state.hostAcceptsUnlock
                            ),
                            onSetThemeMode = themePreferences::setThemeMode,
                            onSetDynamicColor = themePreferences::setDynamicColor,
                            onSetSaveDirectory = viewModel.transferPreferences::setSaveDirectory,
                            onSetRateUnit = viewModel.transferPreferences::setRateUnit,
                            onEnrolUnlock = viewModel::enrolUnlock,
                            onForgetUnlock = viewModel::forgetUnlockKey,
                            onBack = { currentScreen = AppScreen.Main }
                        )
                    }
                    is AppScreen.Detail -> {
                        SectionScreen(
                            section = targetScreen.section,
                            state = state,
                            actions = actions
                        )
                    }
                    is AppScreen.Main -> {
                        if (!connected) {
                            PairingScreen(
                                hosts = state.hosts,
                                discovered = state.discovered,
                                liveHostIds = state.liveHostIds,
                                error = state.error,
                                onScan = { scanner(Unit) },
                                onManual = viewModel::pairManually,
                                onConnect = viewModel::connect,
                                onForget = viewModel::forget,
                                onOpenSettings = { currentScreen = AppScreen.Settings },
                                onRescan = viewModel::rescanHosts
                            )
                        } else {
                            HomeScreen(
                                state = state,
                                actions = actions,
                                onOpen = { currentScreen = AppScreen.Detail(it) },
                                listState = homeListState
                            )
                        }
                    }
                }
            }
        }
    }

    // Shown until a desktop is picked; the connect-and-send effect above takes
    // over from there.
    val pendingShare = shareFiles
    if (pendingShare != null && shareTarget == null) {
        ShareTargetSheet(
            fileCount = pendingShare.size,
            hosts = state.hosts,
            liveHostIds = state.liveHostIds,
            activeHostId = if (connected) state.activeHost?.daemonId else null,
            onPick = { host ->
                shareTarget = host.daemonId
                shareConnecting = false
                if (!connected || state.activeHost?.daemonId != host.daemonId) {
                    viewModel.connect(host)
                }
            },
            onDismiss = { shareFiles = null }
        )
    }

    if (showLockConfirmDialog && connected) {
        val hostName = state.activeHost?.hostName ?: "the workstation"
        val isLocked = state.host.locked
        AlertDialog(
            onDismissRequest = { showLockConfirmDialog = false },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            icon = {
                Icon(
                    imageVector = if (isLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                    contentDescription = null,
                    tint = if (isLocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(
                    text = if (isLocked) "Lock $hostName again?" else "Lock $hostName?",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "This will immediately lock the desktop screen and require the user's password or PIN to sign back in.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLockConfirmDialog = false
                        viewModel.lockSystem()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(
                        text = "Lock Workstation",
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showLockConfirmDialog = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showConnectionInfo && connected) {
        ConnectionInfoSheet(
            host = state.activeHost,
            onDisconnect = {
                showConnectionInfo = false
                viewModel.disconnect()
            },
            onOpenSettings = {
                showConnectionInfo = false
                currentScreen = AppScreen.Settings
            },
            onDismiss = { showConnectionInfo = false }
        )
    }
}

@Composable
private fun rememberLauncherForScan(onResult: (String?) -> Unit): (Unit) -> Unit {
    val launcher = rememberLauncherForActivityResult(ScanContract()) { result ->
        onResult(result.contents)
    }
    return {
        launcher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Point at the code on your desktop")
                .setBeepEnabled(false)
                .setOrientationLocked(true)
        )
    }
}
