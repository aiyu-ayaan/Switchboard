package com.switchboard.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.switchboard.app.data.ThemeConfig
import com.switchboard.app.data.ThemeMode

// Signature Tokyo Night & Slate expressive palette
private val Ink = Color(0xFFC8D1F0)
private val InkDim = Color(0xFF8B93B8)
private val Canvas = Color(0xFF16161E)
private val SurfaceLowest = Color(0xFF13141C)
private val SurfaceBase = Color(0xFF1A1B26)
private val SurfaceContainer = Color(0xFF21222D)
private val SurfaceContainerHigh = Color(0xFF292B38)
private val SurfaceContainerHighest = Color(0xFF343746)

private val Accent = Color(0xFF7AA2F7)
private val Level = Color(0xFF9ECE6A)
private val Danger = Color(0xFFF7768E)
private val Tertiary = Color(0xFFBB9AF7)

val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

private val DarkColors = darkColorScheme(
    primary = Level,
    onPrimary = Color(0xFF10240A),
    primaryContainer = Color(0xFF24381C),
    onPrimaryContainer = Color(0xFFD7EBC8),
    secondary = Accent,
    onSecondary = Color(0xFF0B1220),
    secondaryContainer = Color(0xFF242E46),
    onSecondaryContainer = Ink,
    tertiary = Tertiary,
    onTertiary = Color(0xFF1E0E3E),
    tertiaryContainer = Color(0xFF352654),
    onTertiaryContainer = Color(0xFFEADBFF),
    background = Canvas,
    onBackground = Ink,
    surface = SurfaceBase,
    onSurface = Ink,
    surfaceVariant = SurfaceContainer,
    onSurfaceVariant = InkDim,
    surfaceContainerLowest = SurfaceLowest,
    surfaceContainerLow = SurfaceBase,
    surfaceContainer = SurfaceContainer,
    surfaceContainerHigh = SurfaceContainerHigh,
    surfaceContainerHighest = SurfaceContainerHighest,
    outline = Color(0xFF3A3D4E),
    outlineVariant = Color(0xFF282B3A),
    error = Danger,
    onError = Color(0xFF2A0A10),
    errorContainer = Color(0xFF3A1620),
    onErrorContainer = Color(0xFFFFD9DE)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF3F7D20),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7EBC8),
    onPrimaryContainer = Color(0xFF11290A),
    secondary = Color(0xFF2B5CB8),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3E8F5),
    onSecondaryContainer = Color(0xFF1B2440),
    tertiary = Color(0xFF6B4EA2),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEADBFF),
    onTertiaryContainer = Color(0xFF26104B),
    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF171922),
    surface = Color(0xFFF6F7FB),
    onSurface = Color(0xFF171922),
    surfaceVariant = Color.White,
    onSurfaceVariant = Color(0xFF4A5068),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF9FAFD),
    surfaceContainer = Color(0xFFF0F2F8),
    surfaceContainerHigh = Color(0xFFE7EAF4),
    surfaceContainerHighest = Color(0xFFDFE2EE),
    outline = Color(0xFFC3C8D8),
    outlineVariant = Color(0xFFDDE1EC),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFBDAD6),
    onErrorContainer = Color(0xFF410E0B)
)

@Composable
fun SwitchboardTheme(
    themeConfig: ThemeConfig = ThemeConfig(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val isDark = when (themeConfig.mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }

    val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colors = when {
        themeConfig.dynamicColor && dynamicAvailable ->
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        isDark -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colors,
        shapes = ExpressiveShapes,
        content = content
    )
}
