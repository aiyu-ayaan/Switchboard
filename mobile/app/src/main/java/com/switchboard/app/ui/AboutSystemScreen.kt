package com.switchboard.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.switchboard.app.UiState
import com.switchboard.app.net.AboutSystemResponse
import com.switchboard.app.net.BatteryInfo
import com.switchboard.app.net.DriveItem
import com.switchboard.app.net.GpuInfo
import java.util.Locale

@Composable
fun AboutSystemScreen(
    state: UiState,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val about = state.telemetry.aboutSystem

    LaunchedEffect(Unit) {
        if (about == null) {
            onRefresh()
        }
    }

    if (about == null) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Text(
                    text = "Querying system hardware specifications...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Header with Back button and Refresh action
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    IconButton(onClick = tapping(onBack)) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Column {
                        Text(
                            text = state.activeHost?.hostName ?: "Host System",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Hardware architecture & diagnostics",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = tapping(onRefresh)) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Refresh System Specs",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // 2. Battery Health Card
        item {
            BatteryHealthCard(battery = about.battery)
        }

        // 3. Operating System & Uptime Card
        item {
            SectionSpecCard(
                title = "Operating System",
                icon = Icons.Filled.Computer,
                accentColor = MaterialTheme.colorScheme.primary
            ) {
                // Never guess at the platform here. A host that reports no OS
                // name is a host this build cannot describe, and naming one
                // outright turned a Linux desktop into a confident "Windows".
                SpecRow("OS Edition", about.os.name.ifEmpty { "Unknown" })
                SpecRow("Build Version", about.os.build.ifEmpty { "Unknown" })
                SpecRow("System Uptime", formatUptime(about.os.uptimeSeconds))
            }
        }

        // 4. Processor (CPU) Card
        item {
            SectionSpecCard(
                title = "Processor (CPU)",
                icon = Icons.Filled.Speed,
                accentColor = Color(0xFF60A5FA)
            ) {
                SpecRow("Model", about.cpu.model.ifEmpty { "Generic Processor" })
                SpecRow("Core Count", "${about.cpu.cores} Physical Cores (${about.cpu.threads} Threads)")
                if (about.cpu.baseClockMhz > 0) {
                    SpecRow("Base Clock", "${about.cpu.baseClockMhz} MHz")
                }
            }
        }

        // 5. Memory (RAM) Card
        item {
            val totalGb = String.format(Locale.US, "%.1f GB", about.memory.totalBytes / (1024.0 * 1024 * 1024))
            SectionSpecCard(
                title = "Installed Memory (RAM)",
                icon = Icons.Filled.Memory,
                accentColor = Color(0xFFA78BFA)
            ) {
                SpecRow("Total Capacity", totalGb)
                if (about.memory.type.isNotEmpty()) {
                    SpecRow("Memory Type", about.memory.type)
                }
                if (about.memory.slots > 0) {
                    SpecRow("Modules / Slots", "${about.memory.slots} installed")
                }
            }
        }

        // 6. Graphics (GPU) Card
        if (about.gpus.isNotEmpty()) {
            item {
                SectionSpecCard(
                    title = "Graphics (GPU)",
                    icon = Icons.Filled.Tv,
                    accentColor = Color(0xFF34D399)
                ) {
                    about.gpus.forEachIndexed { index, gpu ->
                        if (index > 0) {
                            Spacer(Modifier.height(10.dp))
                        }
                        GpuSpecBlock(gpu = gpu)
                    }
                }
            }
        }

        // 7. Storage Volumes
        if (about.drives.isNotEmpty()) {
            item {
                SectionSpecCard(
                    title = "Storage Partitions",
                    icon = Icons.Filled.Storage,
                    accentColor = Color(0xFFFBBF24)
                ) {
                    about.drives.forEachIndexed { index, drive ->
                        if (index > 0) {
                            Spacer(Modifier.height(10.dp))
                        }
                        DriveSpecBlock(drive = drive)
                    }
                }
            }
        }
    }
}

@Composable
private fun BatteryHealthCard(battery: BatteryInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (battery.charging) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryFull,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Battery & Power Health",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (battery.present) "Hardware capacity diagnostics" else "Desktop AC supply",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (battery.present) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (battery.charging) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
                    ) {
                        Text(
                            text = if (battery.charging) "CHARGING" else "DISCHARGING",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (battery.charging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            if (!battery.present || (battery.designCapacityMwh == 0L && battery.fullCapacityMwh == 0L)) {
                // Desktop without battery
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Power,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "AC Power Connected",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Desktop workstation running on continuous wall power. No battery degradation.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                // Laptop with battery
                val healthPct = (100.0 - battery.wearPercent).coerceIn(0.0, 100.0)
                val healthColor = when {
                    healthPct >= 80.0 -> Color(0xFF4ADE80)
                    healthPct >= 65.0 -> Color(0xFFFBBF24)
                    else -> Color(0xFFF87171)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = String.format(Locale.US, "%.1f%% Health", healthPct),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = healthColor
                        )
                        Text(
                            text = String.format(Locale.US, "Wear Level: %.1f%%", battery.wearPercent),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "${battery.percent}%",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Current Charge",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                LinearProgressIndicator(
                    progress = { (healthPct / 100.0).toFloat() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = healthColor,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    strokeCap = StrokeCap.Round
                )

                Spacer(Modifier.height(16.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SpecRow("Cycle Count", if (battery.cycleCount > 0) "${battery.cycleCount} cycles" else "Unavailable (WMI/EC protected)")
                    SpecRow(
                        "Full Charge Capacity",
                        if (battery.fullCapacityMwh > 0) "${battery.fullCapacityMwh} mWh" else "Unavailable"
                    )
                    SpecRow(
                        "Factory Design Capacity",
                        if (battery.designCapacityMwh > 0) "${battery.designCapacityMwh} mWh" else "Unavailable"
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionSpecCard(
    title: String,
    icon: ImageVector,
    accentColor: Color,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = accentColor.copy(alpha = 0.15f),
                    modifier = Modifier.size(34.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun SpecRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun GpuSpecBlock(gpu: GpuInfo) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = gpu.name.ifEmpty { "Dedicated Graphics" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            if (gpu.driver.isNotEmpty()) {
                Text(
                    text = "Driver: ${gpu.driver}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (gpu.vramBytes > 0) {
                val vramGb = String.format(Locale.US, "%.1f GB", gpu.vramBytes / (1024.0 * 1024 * 1024))
                Text(
                    text = "VRAM: $vramGb",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DriveSpecBlock(drive: DriveItem) {
    val usedBytes = (drive.totalBytes - drive.freeBytes).coerceAtLeast(0L)
    val totalGb = drive.totalBytes / (1024.0 * 1024 * 1024)
    val freeGb = drive.freeBytes / (1024.0 * 1024 * 1024)
    val usedGb = usedBytes / (1024.0 * 1024 * 1024)
    val fraction = if (drive.totalBytes > 0) (usedBytes.toFloat() / drive.totalBytes.toFloat()).coerceIn(0f, 1f) else 0f

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (drive.label.isNotEmpty()) "${drive.device} (${drive.label})" else drive.device,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = String.format(Locale.US, "%.0f%% Used", fraction * 100),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(6.dp))

            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                strokeCap = StrokeCap.Round
            )

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = String.format(Locale.US, "%.1f GB free", freeGb),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = String.format(Locale.US, "%.1f GB / %.1f GB", usedGb, totalGb),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatUptime(seconds: Long): String {
    if (seconds <= 0) return "Just started"
    val days = seconds / 86400
    val hours = (seconds % 86400) / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        days > 0 -> "${days}d ${hours}h ${minutes}m"
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}
