package com.switchboard.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.switchboard.app.mirror.MirrorController

/**
 * Sharing this phone's screen with the desktop.
 *
 * Usually started from the desktop, which asks and waits on Android's prompt
 * here; this screen is where the user can start it themselves, see that it is
 * running, stop it, and switch on desktop control.
 */
@Composable
fun MirrorScreen() {
    val context = LocalContext.current
    val haptics = LocalHaptics.current
    val controller = remember { MirrorController.get(context) }
    val state by controller.state.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionCard {
            Header(
                icon = Icons.Filled.CastConnected,
                title = "Share this screen",
                subtitle = when {
                    state.streaming -> "Your desktop can see this screen · ${state.width}×${state.height}"
                    state.awaitingConsent -> "Waiting for you to allow sharing on this phone"
                    else -> "Show this phone on your desktop, and open its apps in their own windows"
                }
            )
            state.error?.let {
                Spacer(Modifier.size(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.size(14.dp))
            if (state.streaming || state.awaitingConsent) {
                OutlinedButton(
                    onClick = {
                        haptics.tap()
                        controller.stop()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Stop sharing") }
            } else {
                Button(
                    onClick = {
                        haptics.tap()
                        controller.clearError()
                        controller.request()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Start sharing") }
            }
        }

        SectionCard {
            Header(
                icon = Icons.Filled.Mouse,
                title = "Desktop control",
                subtitle = if (state.control) {
                    "On — clicks, scrolling and typing in the desktop window act on this phone"
                } else {
                    "Off — the desktop can watch but not touch. Turn on \"Switchboard desktop control\" in Accessibility."
                }
            )
            if (!state.control) {
                Spacer(Modifier.size(14.dp))
                FilledTonalButton(
                    onClick = {
                        haptics.tap()
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Open Accessibility settings") }

                // Android 13 greys the switch out for apps installed outside a
                // store, which Switchboard is; the way past it is in App info.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Spacer(Modifier.size(10.dp))
                    Text(
                        "If Android says the setting is restricted, open App info, tap ⋮, " +
                            "choose \"Allow restricted settings\", then try again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.size(6.dp))
                    OutlinedButton(
                        onClick = {
                            haptics.tap()
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null)
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Open App info") }
                }
            }
        }
    }
}

@Composable
private fun Header(icon: ImageVector, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
