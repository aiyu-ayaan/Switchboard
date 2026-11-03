package com.aiyu.switchboard.ui.pair

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiyu.switchboard.AppViewModel
import com.aiyu.switchboard.data.fingerprint
import com.aiyu.switchboard.ui.components.ShapeIcon

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AddScreen(vm: AppViewModel, onBack: () -> Unit, onScan: () -> Unit, onCode: () -> Unit, onPairing: () -> Unit) {
    val found by vm.connections.discovery.found.collectAsStateWithLifecycle()
    val data by vm.vault.data.collectAsStateWithLifecycle()
    val fresh = found.values.filter { t -> data.hosts.none { it.id == t.id } }
    val behavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val cs = MaterialTheme.colorScheme
    Scaffold(
        modifier = Modifier.nestedScroll(behavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Add a computer") },
                subtitle = { Text("Pair once, reconnect automatically") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                scrollBehavior = behavior,
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Option(Icons.Rounded.QrCodeScanner, MaterialShapes.Cookie9Sided, cs.primaryContainer, cs.onPrimaryContainer, "Scan QR code", "Fastest. Point the camera at the computer's screen.", onScan, big = true)
            Option(Icons.Rounded.Keyboard, MaterialShapes.Cookie6Sided, cs.tertiaryContainer, cs.onTertiaryContainer, "Enter code", "Type the 12-character code shown on the computer.", onCode)

            Row(Modifier.padding(top = 20.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("On this network", style = MaterialTheme.typography.titleMediumEmphasized, modifier = Modifier.weight(1f))
                LoadingIndicator(Modifier.size(32.dp))
            }
            if (fresh.isEmpty()) {
                Text(
                    "Looking for computers running Switchboard… If yours doesn't show up, scan its code or type its address on the code screen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            fresh.forEach { t ->
                Option(Icons.Rounded.Computer, MaterialShapes.Clover4Leaf, cs.secondaryContainer, cs.onSecondaryContainer, t.name, "Fingerprint ${fingerprint(t.pk)}", {
                    vm.codeTarget.value = t
                    onCode()
                })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Option(icon: ImageVector, shape: RoundedPolygon, container: Color, content: Color, title: String, text: String, onClick: () -> Unit, big: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Surface(onClick = onClick, shape = RoundedCornerShape(28.dp), color = if (big) container else cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            ShapeIcon(icon, shape, if (big) content else container, if (big) container else content, size = if (big) 64.dp else 52.dp, iconSize = if (big) 30.dp else 24.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLargeEmphasized, color = if (big) content else cs.onSurface)
                Text(text, style = MaterialTheme.typography.bodyMedium, color = if (big) content.copy(alpha = 0.8f) else cs.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = if (big) content else cs.onSurfaceVariant)
        }
    }
    if (big) Spacer(Modifier.height(0.dp))
}
