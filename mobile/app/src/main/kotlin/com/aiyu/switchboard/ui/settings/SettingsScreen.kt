package com.aiyu.switchboard.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SettingsBrightness
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiyu.switchboard.AppViewModel
import com.aiyu.switchboard.BuildConfig
import com.aiyu.switchboard.ui.components.ShapeIcon

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val data by vm.vault.data.collectAsStateWithLifecycle()
    val behavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val cs = MaterialTheme.colorScheme
    Scaffold(
        modifier = Modifier.nestedScroll(behavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                scrollBehavior = behavior,
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Group {
                Text("Theme", style = MaterialTheme.typography.titleMediumEmphasized, modifier = Modifier.padding(bottom = 12.dp))
                val opts = listOf(Triple("system", "System", Icons.Rounded.SettingsBrightness), Triple("light", "Light", Icons.Rounded.LightMode), Triple("dark", "Dark", Icons.Rounded.DarkMode))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                    opts.forEachIndexed { i, (key, label, icon) ->
                        ToggleButton(
                            checked = data.theme == key,
                            onCheckedChange = { vm.setTheme(key) },
                            shapes = when (i) {
                                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                opts.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                            },
                            modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                        ) {
                            M3Icon(icon, null)
                            Spacer(Modifier.width(6.dp))
                            Text(label)
                        }
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SettingRow(icon = Icons.Rounded.Palette, shape = MaterialShapes.Cookie9Sided, title = "Wallpaper colours", text = "Take the app's colours from your wallpaper") {
                    Switch(checked = data.dynamicColor, onCheckedChange = vm::setDynamicColor)
                }
            }
            SettingRow(icon = Icons.Rounded.PhoneAndroid, shape = MaterialShapes.Cookie6Sided, title = "This phone", text = vm.connections.deviceName) {}
            SettingRow(icon = Icons.Rounded.Security, shape = MaterialShapes.Clover4Leaf, title = "End-to-end encrypted", text = "X25519 key exchange, ChaCha20-Poly1305 frames. Keys stay in the Android Keystore-protected vault on this phone.") {}
            UpdatesGroup(vm.vault)
            Text(
                "Switchboard ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(24.dp),
            )
        }
    }
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(20.dp)) { content() }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SettingRow(icon: ImageVector, shape: RoundedPolygon, title: String, text: String, trailing: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(20.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        androidx.compose.foundation.layout.Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            ShapeIcon(icon, shape, cs.secondaryContainer, cs.onSecondaryContainer)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(text, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
            trailing()
        }
    }
}
