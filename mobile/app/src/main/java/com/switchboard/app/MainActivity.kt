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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.switchboard.app.ui.HomeScreen
import com.switchboard.app.ui.PairingScreen
import com.switchboard.app.ui.Section
import com.switchboard.app.ui.SectionActions
import com.switchboard.app.ui.SectionScreen
import com.switchboard.app.ui.SettingsScreen
import com.switchboard.app.ui.SwitchboardTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
                        themePreferences = themePreferences
                    )
                }
            }
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
    themePreferences: ThemePreferences
) {
    val state by viewModel.uiState.collectAsState()
    val themeConfig by themePreferences.config.collectAsState()

    val scanner = rememberLauncherForScan { contents ->
        if (contents != null) viewModel.pair(contents) else viewModel.setScanning(false)
    }

    val connected = state.status == ConnectionStatus.Connected

    var currentScreen by remember { mutableStateOf<AppScreen>(AppScreen.Main) }

    LaunchedEffect(connected) {
        if (!connected && currentScreen is AppScreen.Detail) {
            currentScreen = AppScreen.Main
        }
    }

    BackHandler(enabled = currentScreen !is AppScreen.Main) {
        currentScreen = AppScreen.Main
    }

    val actions = remember(viewModel) {
        SectionActions(
            onBrightness = viewModel::setBrightness,
            onContrast = viewModel::setContrast,
            onVolume = viewModel::setVolume,
            onMedia = viewModel::media
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
                                    Text(
                                        text = state.activeHost?.hostName ?: "Switchboard",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = when (state.status) {
                                            ConnectionStatus.Connected -> "Connected"
                                            ConnectionStatus.Connecting -> "Connecting…"
                                            ConnectionStatus.Disconnected -> "Not connected"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                },
                actions = {
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

                    if (currentScreen !is AppScreen.Settings) {
                        if (connected) {
                            IconButton(
                                onClick = { viewModel.disconnect() },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Filled.SwapHoriz, contentDescription = "Switch desktop")
                            }
                        } else {
                            IconButton(
                                onClick = { scanner(Unit) },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Filled.QrCodeScanner, contentDescription = "Scan pairing code")
                            }
                        }

                        IconButton(
                            onClick = { currentScreen = AppScreen.Settings },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Filled.Settings, contentDescription = "Open Settings")
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
                            onSetThemeMode = themePreferences::setThemeMode,
                            onSetDynamicColor = themePreferences::setDynamicColor,
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
                                error = state.error,
                                onScan = { scanner(Unit) },
                                onManual = viewModel::pairManually,
                                onConnect = viewModel::connect,
                                onForget = viewModel::forget
                            )
                        } else {
                            HomeScreen(
                                state = state,
                                actions = actions,
                                onOpen = { currentScreen = AppScreen.Detail(it) }
                            )
                        }
                    }
                }
            }
        }
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
                .setOrientationLocked(false)
        )
    }
}
