package com.switchboard.app

import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.Button
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.switchboard.app.data.HapticPreferences
import com.switchboard.app.data.ThemePreferences
import com.switchboard.app.ui.ConnectionInfoSheet
import com.switchboard.app.ui.HomeScreen
import com.switchboard.app.ui.PairingScreen
import com.switchboard.app.ui.Section
import com.switchboard.app.ui.SectionActions
import com.switchboard.app.ui.SectionScreen
import com.switchboard.app.ui.SettingsScreen
import com.switchboard.app.update.UpdateScreen
import com.switchboard.app.update.UpdateSheet
import com.switchboard.app.update.Updates
import com.switchboard.app.ui.ShareTargetSheet
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.switchboard.app.ui.TouchpadActions
import com.switchboard.app.ui.LocalHaptics
import com.switchboard.app.ui.SwitchboardTheme
import com.switchboard.app.ui.rememberHaptics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MainActivity : ComponentActivity() {
    private val pendingSharedUris = MutableStateFlow<List<Uri>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShareIntent(intent)
        setContent {
            val context = LocalContext.current
            val themePreferences = remember { ThemePreferences(context) }
            val themeConfig by themePreferences.config.collectAsState()
            val hapticPreferences = remember { HapticPreferences.get(context) }
            val hapticsEnabled by hapticPreferences.enabled.collectAsState()

            SwitchboardTheme(themeConfig = themeConfig) {
                // Provided around the whole app, so every control below can
                // answer a touch without being handed the setting first.
                CompositionLocalProvider(LocalHaptics provides rememberHaptics(hapticsEnabled)) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        SwitchboardApp(
                            themePreferences = themePreferences,
                            hapticPreferences = hapticPreferences,
                            sharedUrisFlow = pendingSharedUris
                        )
                    }
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
    data object Updates : AppScreen
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwitchboardApp(
    viewModel: SwitchboardViewModel = viewModel(),
    themePreferences: ThemePreferences,
    hapticPreferences: HapticPreferences,
    sharedUrisFlow: StateFlow<List<Uri>?> = MutableStateFlow(null)
) {
    val state by viewModel.uiState.collectAsState()
    val themeConfig by themePreferences.config.collectAsState()
    val hapticsEnabled by hapticPreferences.enabled.collectAsState()
    val transferConfig by viewModel.transferPreferences.config.collectAsState()
    val sharedUris by sharedUrisFlow.collectAsState()

    val scanner = rememberLauncherForScan { contents ->
        if (contents != null) viewModel.pair(contents) else viewModel.setScanning(false)
    }

    val connected = state.status == ConnectionStatus.Connected
    val context = LocalContext.current
    val haptics = LocalHaptics.current

    var currentScreen by remember { mutableStateOf<AppScreen>(AppScreen.Main) }
    var showConnectionInfo by remember { mutableStateOf(false) }
    var showLockConfirmDialog by remember { mutableStateOf(false) }
    var showLandscapePromptDialog by remember { mutableStateOf(false) }
    val homeListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

    DisposableEffect(Unit) {
        onDispose {
            (context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Switchboard installs from a GitHub release rather than a store, so the
    // app is the only thing that can notice a newer one. Once per launch, and
    // silent unless there is something to offer: `check` returns early while
    // the feature is off or a snooze is running.
    LaunchedEffect(Unit) {
        Updates.init(context)
        Updates.check()
    }

    LaunchedEffect(currentScreen) {
        if (currentScreen !is AppScreen.Detail || (currentScreen as AppScreen.Detail).section != Section.Deck) {
            (context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

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
            showLandscapePromptDialog = false
            if (currentScreen is AppScreen.Detail) {
                if ((currentScreen as AppScreen.Detail).section == Section.Deck) {
                    (context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
                currentScreen = AppScreen.Main
            }
        }
    }

    BackHandler(enabled = currentScreen !is AppScreen.Main) {
        if (currentScreen is AppScreen.Detail && (currentScreen as AppScreen.Detail).section == Section.Deck) {
            (context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        // Updates is reached from Settings, so back goes there rather than
        // dropping two levels to the home surface.
        currentScreen = if (currentScreen is AppScreen.Updates) AppScreen.Settings else AppScreen.Main
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
                onGesture = viewModel::shellGesture,
                onText = viewModel::sendText,
                onClipboard = viewModel::sendClipboard
            ),
            onTransferControl = viewModel::controlTransfer,
            rateUnit = transferConfig.rateUnit,
            onLockSystem = {
                val live = latestState.value
                if (!live.host.locked) {
                    showLockConfirmDialog = true
                }
            },
            onDeckAction = viewModel::triggerDeckAction,
            onSaveDeckConfig = viewModel::saveDeckConfig,
            onRefreshApps = viewModel::refreshInstalledApps,
            onPower = viewModel::sendPower,
            onMicVolume = viewModel::setMicVolume,
            onAudioInput = viewModel::setInputDevice
        )
    }

    val isDeckScreen = currentScreen is AppScreen.Detail && (currentScreen as AppScreen.Detail).section == Section.Deck

    BackHandler(enabled = isDeckScreen) {
        (context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        currentScreen = AppScreen.Main
    }

    Scaffold(
        contentWindowInsets = if (isDeckScreen) androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0) else androidx.compose.material3.ScaffoldDefaults.contentWindowInsets,
        topBar = {
            if (!isDeckScreen) {
                TopAppBar(
                navigationIcon = {
                    if (currentScreen !is AppScreen.Main) {
                        IconButton(
                            onClick = {
                                haptics.tap()
                                if (currentScreen is AppScreen.Detail && (currentScreen as AppScreen.Detail).section == Section.Deck) {
                                    (context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                }
                                currentScreen = if (currentScreen is AppScreen.Updates) {
                                    AppScreen.Settings
                                } else {
                                    AppScreen.Main
                                }
                            },
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
                                is AppScreen.Updates -> {
                                    Text(
                                        text = "App updates",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Released on GitHub, installed by Android",
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
                                onClick = {
                                    haptics.tap()
                                    viewModel.disconnect()
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Filled.SwapHoriz, contentDescription = "Switch desktop")
                            }

                            IconButton(
                                onClick = {
                                    haptics.tap()
                                    showConnectionInfo = true
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Filled.Info, contentDescription = "Connection info")
                            }
                        } else {
                            IconButton(
                                onClick = {
                                    haptics.tap()
                                    scanner(Unit)
                                },
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
                            alwaysOn = state.alwaysOn,
                            startOnBoot = state.startOnBoot,
                            onSetAlwaysOn = viewModel::setAlwaysOn,
                            onSetStartOnBoot = viewModel::setStartOnBoot,
                            onSetThemeMode = themePreferences::setThemeMode,
                            onSetDynamicColor = themePreferences::setDynamicColor,
                            hapticsEnabled = hapticsEnabled,
                            onSetHaptics = hapticPreferences::setEnabled,
                            onSetSaveDirectory = viewModel.transferPreferences::setSaveDirectory,
                            onSetRateUnit = viewModel.transferPreferences::setRateUnit,
                            onOpenUpdates = { currentScreen = AppScreen.Updates },
                            onBack = { currentScreen = AppScreen.Main }
                        )
                    }
                    is AppScreen.Updates -> {
                        UpdateScreen()
                    }
                    is AppScreen.Detail -> {
                        SectionScreen(
                            section = targetScreen.section,
                            state = state,
                            actions = actions,
                            onBack = {
                                if (targetScreen.section == Section.Deck) {
                                    (context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                }
                                currentScreen = AppScreen.Main
                            }
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
                                onOpen = { section ->
                                    if (section == Section.Deck) {
                                        showLandscapePromptDialog = true
                                    } else {
                                        currentScreen = AppScreen.Detail(section)
                                    }
                                },
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
        AlertDialog(
            onDismissRequest = { showLockConfirmDialog = false },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            icon = {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text(
                    text = "Lock $hostName?",
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
                        // Not a tap: locking the desktop is the end of an
                        // errand, and it should not feel like opening a menu.
                        haptics.confirm()
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
                    onClick = {
                        haptics.tap()
                        showLockConfirmDialog = false
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // What a launch check does when it finds something. Not shown on the update
    // screen itself, where the same offer is already on the page.
    if (currentScreen !is AppScreen.Updates) {
        UpdateSheet(onOpenSettings = {
            Updates.dismiss()
            currentScreen = AppScreen.Updates
        })
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

    if (showLandscapePromptDialog) {
        AlertDialog(
            onDismissRequest = { showLandscapePromptDialog = false },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            icon = {
                Icon(
                    imageVector = Icons.Filled.ScreenRotation,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Switchboard Deck",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = "ALPHA",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            },
            text = {
                Text(
                    text = "Switchboard Deck is designed for horizontal operation to deliver an authentic 8-key hardware console experience. Switchboard will rotate your screen into landscape mode.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        haptics.tap()
                        showLandscapePromptDialog = false
                        (context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        currentScreen = AppScreen.Detail(Section.Deck)
                    }
                ) {
                    Text("Continue to Deck")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        haptics.tap()
                        showLandscapePromptDialog = false
                    }
                ) {
                    Text("Cancel")
                }
            }
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
