package com.switchboard.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Switchboard shares one palette with the desktop shell so a user moving
 * between the two reads the same surfaces and the same green level fill.
 */
private val Ink = Color(0xFFC8D1F0)
private val InkDim = Color(0xFF8B93B8)
private val Canvas = Color(0xFF1A1B26)
private val CardSurface = Color(0xFF21222D)
private val Raised = Color(0xFF292B38)
private val Accent = Color(0xFF7AA2F7)
private val Level = Color(0xFF9ECE6A)
private val Danger = Color(0xFFF7768E)

private val DarkColors = darkColorScheme(
    primary = Level,
    onPrimary = Color(0xFF10240A),
    secondary = Accent,
    onSecondary = Color(0xFF0B1220),
    background = Canvas,
    onBackground = Ink,
    surface = Canvas,
    onSurface = Ink,
    surfaceVariant = CardSurface,
    onSurfaceVariant = InkDim,
    surfaceContainer = CardSurface,
    surfaceContainerHigh = Raised,
    surfaceContainerHighest = Raised,
    primaryContainer = Color(0xFF2B3A22),
    onPrimaryContainer = Ink,
    secondaryContainer = Raised,
    onSecondaryContainer = Ink,
    errorContainer = Color(0xFF3A1620),
    onErrorContainer = Color(0xFFFFD9DE),
    outline = Color(0xFF3A3D4E),
    outlineVariant = Color(0xFF2F3140),
    error = Danger,
    onError = Color(0xFF2A0A10)
)

// The light scheme is a genuine light palette rather than an inversion, so
// contrast holds in both directions.
private val LightColors = lightColorScheme(
    primary = Color(0xFF3F7D20),
    onPrimary = Color.White,
    secondary = Color(0xFF2B5CB8),
    onSecondary = Color.White,
    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF171922),
    surface = Color(0xFFF6F7FB),
    onSurface = Color(0xFF171922),
    surfaceVariant = Color.White,
    onSurfaceVariant = Color(0xFF4A5068),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFE9ECF5),
    surfaceContainerHighest = Color(0xFFDCE1EE),
    primaryContainer = Color(0xFFD7EBC8),
    onPrimaryContainer = Color(0xFF11290A),
    secondaryContainer = Color(0xFFE3E8F5),
    onSecondaryContainer = Color(0xFF1B2440),
    errorContainer = Color(0xFFFBDAD6),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFFC3C8D8),
    outlineVariant = Color(0xFFDDE1EC),
    error = Color(0xFFB3261E),
    onError = Color.White
)

@Composable
fun SwitchboardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /**
     * Material You is off by default: Switchboard mirrors the desktop shell,
     * and a wallpaper-derived palette would break that pairing.
     */
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
