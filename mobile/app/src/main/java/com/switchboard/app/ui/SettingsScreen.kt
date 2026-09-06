package com.switchboard.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.switchboard.app.data.ThemeConfig
import com.switchboard.app.data.ThemeMode
import com.switchboard.app.data.TransferConfig
import com.switchboard.app.transfer.RateUnit

/**
 * What Settings needs to know about fingerprint unlock. Derived rather than
 * stored: the phone half comes from the keystore and the host half from the
 * connected desktop's capabilities, so it is rebuilt from live state.
 */
data class UnlockConfig(
    /** The phone has a fingerprint sensor a keystore key can be gated on. */
    val availableOnPhone: Boolean = false,
    /** A key exists on this phone. */
    val enrolled: Boolean = false,
    /** The connected desktop has a password enrolled for remote unlock. */
    val hostAccepts: Boolean = false
)

@Composable
fun SettingsScreen(
    themeConfig: ThemeConfig,
    transferConfig: TransferConfig,
    unlockConfig: UnlockConfig,
    alwaysOn: Boolean,
    onSetAlwaysOn: (Boolean) -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetDynamicColor: (Boolean) -> Unit,
    onSetSaveDirectory: (String) -> Unit,
    onSetRateUnit: (RateUnit) -> Unit,
    onEnrolUnlock: () -> Unit,
    onForgetUnlock: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var folderError by remember { mutableStateOf<String?>(null) }

    val power = remember { context.getSystemService(PowerManager::class.java) }
    // Re-read rather than observed: the platform offers no callback for this,
    // and the only thing that changes it is the system dialog below, which
    // reports back when it closes.
    var batteryExempt by remember {
        mutableStateOf(power?.isIgnoringBatteryOptimizations(context.packageName) ?: true)
    }
    val batteryPrompt = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // That dialog always reports cancelled, so the answer comes from asking
        // the system again rather than from the result code.
        batteryExempt = power?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    }

    // Without notification permission the service still runs, but its ongoing
    // notification is silently dropped -- which is how a user ends up unable to
    // find the switch that turns the connection off.
    val notifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { onSetAlwaysOn(true) }

    val enableAlwaysOn: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onSetAlwaysOn(true)
        }
    }

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { tree ->
        if (tree == null) return@rememberLauncherForActivityResult

        // Without persisting the grant the folder is unwritable after the next
        // process death, and an incoming file would fail on a setting the user
        // believes they already made. Not every picker hands back a tree that
        // can be persisted, and taking one that cannot throws — so the folder
        // is only stored once the grant is actually held, and the failure is
        // said out loud rather than leaving a tap that did nothing.
        val granted = runCatching {
            context.contentResolver.takePersistableUriPermission(
                tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }.isSuccess

        if (granted) {
            folderError = null
            onSetSaveDirectory(tree.toString())
        } else {
            folderError = "Android could not grant persistent access to that folder. Transfers will continue saving to Downloads/Switchboard."
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Connection",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
            )
        }

        item {
            SettingsCard {
                SwitchRow(
                    icon = Icons.Filled.Sync,
                    title = "Stay Connected",
                    description = "Hold the link to your desktop while Switchboard is in the " +
                        "background or cleared from Recents, and redial on its own when it drops.",
                    checked = alwaysOn,
                    onCheckedChange = { on -> if (on) enableAlwaysOn() else onSetAlwaysOn(false) }
                )
            }
        }

        // Only worth the user's attention once they have asked for a connection
        // that Doze would otherwise cut. Offering it earlier is asking for a
        // battery exemption for a feature they have not turned on.
        if (alwaysOn && !batteryExempt) {
            item {
                SettingsCard {
                    ThemeModeOption(
                        title = "Allow Background Battery Use",
                        description = "Android may currently suspend Switchboard's network " +
                            "access while the screen is off, which drops the connection. " +
                            "Tap to exempt it.",
                        icon = Icons.Filled.BatterySaver,
                        selected = false,
                        onClick = {
                            batteryPrompt.launch(
                                Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:" + context.packageName)
                                )
                            )
                        }
                    )
                }
            }
        }

        item {
            Text(
                text = "Appearance",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
            )
        }

        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Theme Mode",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(2.dp))

                    ThemeModeOption(
                        title = "Follow System",
                        description = "Automatically adapt to device dark/light setting",
                        icon = Icons.Filled.BrightnessAuto,
                        selected = themeConfig.mode == ThemeMode.SYSTEM,
                        onClick = { onSetThemeMode(ThemeMode.SYSTEM) }
                    )

                    ThemeModeOption(
                        title = "Dark Theme",
                        description = "Tokyo Night & Slate dark palette",
                        icon = Icons.Filled.DarkMode,
                        selected = themeConfig.mode == ThemeMode.DARK,
                        onClick = { onSetThemeMode(ThemeMode.DARK) }
                    )

                    ThemeModeOption(
                        title = "Light Theme",
                        description = "High-contrast clean day palette",
                        icon = Icons.Filled.LightMode,
                        selected = themeConfig.mode == ThemeMode.LIGHT,
                        onClick = { onSetThemeMode(ThemeMode.LIGHT) }
                    )
                }
            }
        }

        item {
            val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            SettingsCard {
                SwitchRow(
                    icon = Icons.Filled.ColorLens,
                    title = "Dynamic Theming",
                    description = if (dynamicAvailable) {
                        "Derive tonal palette from your Android wallpaper (Material You)"
                    } else {
                        "Material You dynamic theming requires Android 12+"
                    },
                    checked = themeConfig.dynamicColor && dynamicAvailable,
                    onCheckedChange = onSetDynamicColor,
                    enabled = dynamicAvailable
                )
            }
        }

        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text(
                        text = "Current Palette Preview",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        PaletteChip("Primary", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary, Modifier.weight(1f))
                        PaletteChip("Secondary", MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.onSecondary, Modifier.weight(1f))
                        PaletteChip("Tertiary", MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.onTertiary, Modifier.weight(1f))
                        PaletteChip("Surface", MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                    }
                }
            }
        }

        item {
            Text(
                text = "File Transfers",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp)
            )
        }

        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Save Folder",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    ThemeModeOption(
                        title = folderLabel(transferConfig.saveDirectory),
                        description = if (transferConfig.saveDirectory.isEmpty()) {
                            "Files sent from the desktop land in Downloads/Switchboard. Tap to customize."
                        } else {
                            "Files sent from the desktop land here. Tap to change."
                        },
                        icon = Icons.Filled.Folder,
                        selected = true,
                        onClick = { pickFolder.launch(initialSaveDirectory(transferConfig.saveDirectory)) }
                    )

                    if (transferConfig.saveDirectory.isNotEmpty()) {
                        TextButton(
                            onClick = { onSetSaveDirectory("") },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Reset to Default (Downloads)")
                        }
                    }

                    folderError?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }

                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Speed Unit",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    RateUnit.entries.forEach { unit ->
                        ThemeModeOption(
                            title = unit.label,
                            description = if (unit == RateUnit.BYTES) {
                                "Megabytes per second, as file managers report it"
                            } else {
                                "Megabits per second, as network tools report it"
                            },
                            icon = Icons.Filled.Speed,
                            selected = transferConfig.rateUnit == unit,
                            onClick = { onSetRateUnit(unit) }
                        )
                    }
                }
            }
        }

        // Fingerprint unlock is only offered where it can actually work: the
        // phone needs a sensor a keystore key can be gated on, and the desktop
        // needs a password enrolled. Anything less and the section stays away
        // rather than showing a control that would fail on tap.
        if (unlockConfig.availableOnPhone) {
            item {
                Text(
                    text = "Security",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp)
                )
            }

            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Fingerprint Unlock",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (unlockConfig.enrolled) {
                                "This phone can open the desktop's lock screen. The key lives in " +
                                    "hardware and only unlocks with your fingerprint."
                            } else {
                                "Register a fingerprint-gated key so this phone can open the " +
                                    "desktop's lock screen. The desktop must be unlocked and set " +
                                    "up for remote unlock."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (!unlockConfig.enrolled && !unlockConfig.hostAccepts) {
                            Text(
                                text = "The connected desktop has not been set up for remote unlock.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (unlockConfig.enrolled) {
                                TextButton(onClick = onForgetUnlock) { Text("Remove key") }
                            } else {
                                TextButton(
                                    onClick = onEnrolUnlock,
                                    enabled = unlockConfig.hostAccepts
                                ) { Text("Set up") }
                            }
                        }
                    }
                }
            }
        }

        item {
            Text(
                text = "About",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp)
            )
        }

        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(46.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.Person,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(
                                text = "Aiyu Ayaan",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Developer & Creator (@aiyu-ayaan)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Filled.Security,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Zero-Trust E2EE Protocol",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Text(
                                text = "Ephemeral X25519 ECDH key exchange with AES-256-GCM local encrypted WebSocket framing.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Switchboard Android Client",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest
                        ) {
                            Text(
                                text = "v0.1.0",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The bordered container every settings group sits in. */
@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        content()
    }
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(42.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
            )
        )
    }
}

@Composable
private fun ThemeModeOption(
    title: String,
    description: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .bouncyClickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selected) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PaletteChip(
    name: String,
    color: Color,
    onColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color,
        modifier = modifier.height(52.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = onColor,
                maxLines = 1
            )
        }
    }
}

/**
 * Where the folder picker should open.
 *
 * Launched with no hint it reopens wherever the picker was last left, which is
 * wherever the send-a-file picker left it: some album under Videos or Images.
 * Those are media roots, and a media root cannot be granted as a tree at all,
 * so the picker offers no way to select the folder standing in front of you
 * and the setting looks broken rather than unsupported. Downloads on primary
 * storage is somewhere a grant is actually possible. A folder already chosen
 * is better still, since the reason to reopen this is usually to move it
 * somewhere nearby.
 *
 * It is only a hint: a picker is free to ignore it, and one that does lands
 * exactly where it did before.
 */
private fun initialSaveDirectory(current: String): Uri =
    if (current.isNotEmpty()) {
        Uri.parse(current)
    } else {
        DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_PROVIDER, "primary:Download")
    }

private const val EXTERNAL_STORAGE_PROVIDER = "com.android.externalstorage.documents"

/**
 * The tree URI's last path segment is the closest thing the SAF exposes to a
 * folder name; showing the raw URI would be unreadable.
 */
private fun folderLabel(treeUri: String): String {
    if (treeUri.isEmpty()) return "Downloads / Switchboard (Default)"
    val documentId = Uri.decode(treeUri.substringAfterLast('/'))
    return documentId.substringAfterLast(':').ifEmpty { documentId }
}
