package com.switchboard.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.switchboard.app.ui.PairingScreen
import com.switchboard.app.ui.Section
import com.switchboard.app.ui.SectionActions
import com.switchboard.app.ui.HomeScreen
import com.switchboard.app.ui.SectionScreen
import com.switchboard.app.ui.SwitchboardTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SwitchboardTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SwitchboardApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwitchboardApp(viewModel: SwitchboardViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()

    // ZXing's scanner activity handles the camera permission prompt itself, so
    // the app never has to hold CAMERA outside an explicit scan.
    val scanner = rememberLauncherForScan { contents ->
        if (contents != null) viewModel.pair(contents) else viewModel.setScanning(false)
    }

    val connected = state.status == ConnectionStatus.Connected

    // null is the home list; a value is that section's own screen. Navigation
    // is one level deep, so a nav graph would be scaffolding around a nullable.
    var section by rememberSaveable { mutableStateOf<Section?>(null) }

    // Losing the host makes any open section meaningless.
    LaunchedEffect(connected) { if (!connected) section = null }
    BackHandler(enabled = connected && section != null) { section = null }

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
                    if (connected && section != null) {
                        IconButton(
                            onClick = { section = null },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to controls"
                            )
                        }
                    }
                },
                title = {
                    Column {
                        Text(
                            text = section?.title
                                ?: state.activeHost?.hostName
                                ?: "Switchboard",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = section?.let { state.activeHost?.hostName ?: "" }
                                ?: when (state.status) {
                                    ConnectionStatus.Connected -> "Connected"
                                    ConnectionStatus.Connecting -> "Connecting…"
                                    ConnectionStatus.Disconnected -> "Not connected"
                                },
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    if (state.status == ConnectionStatus.Connecting) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(20.dp)
                                .padding(end = 4.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    if (connected) {
                        // Multi-host: step back to the picker without forgetting.
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
            when {
                !connected -> PairingScreen(
                    hosts = state.hosts,
                    error = state.error,
                    onScan = { scanner(Unit) },
                    onManual = viewModel::pairManually,
                    onConnect = viewModel::connect,
                    onForget = viewModel::forget
                )

                section != null -> SectionScreen(
                    section = section!!,
                    state = state,
                    actions = actions
                )

                else -> HomeScreen(
                    state = state,
                    actions = actions,
                    onOpen = { section = it }
                )
            }
        }
    }
}

/** Wraps the ZXing scan contract so callers deal in payload strings. */
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
