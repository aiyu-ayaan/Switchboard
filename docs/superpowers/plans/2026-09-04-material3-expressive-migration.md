# Material 3 Expressive Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate the Switchboard Android application to Material 3 Expressive design with spring animations, expressive cards and list items, modern sliders, transport morphing, and a full Settings screen with theme selection (system/dark/light), Material You dynamic theming, and an "About Developer & System" section.

**Architecture:** 
1. **Preferences Layer**: `ThemePreferences` backed by Android `SharedPreferences` managing `ThemeMode` (System, Dark, Light) and `dynamicColor` toggle.
2. **Tokens & Motion Layer**: `Theme.kt` with expanded M3 container tiers (`surfaceContainerLowest` through `surfaceContainerHighest`) and `Motion.kt` housing spring specs (`ExpressiveBouncy`, `ExpressiveSnappy`) and the `Modifier.bouncyClickable()` spring scale modifier.
3. **Component & Screen Layer**: Re-architected `Controls.kt`, `HomeScreen.kt`, `PairingScreen.kt`, and new `SettingsScreen.kt` with expressive cards, pulsing status badges, animated equalizer, and modern capsule sliders.
4. **App Shell**: `MainActivity.kt` with fluid `AnimatedContent` cross-screen and top bar title transitions.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, AndroidX Lifecycle & Activity, SharedPreferences.

**Spec:** `docs/superpowers/specs/2026-09-04-material3-expressive-design.md`

## Global Constraints

- Never execute `git push` or push to remote under any circumstances.
- No AI attribution in commit messages or code comments (configured author: `aiyu-ayaan`).
- Strict conventional commit format: `<type>(<scope>): <short summary>`.
- Preserve all existing network protocols, ECDH cryptographic pairing, and display/audio control commands without modification.
- Must compile cleanly via `./gradlew compileDebugKotlin` and pass `./gradlew test`.

---

### Task 1: Theme Preferences & Storage Layer

**Files:**
- Create: `mobile/app/src/main/java/com/switchboard/app/data/ThemePreferences.kt`
- Create: `mobile/app/src/test/java/com/switchboard/app/data/ThemePreferencesTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  enum class ThemeMode { SYSTEM, DARK, LIGHT }
  data class ThemeConfig(val mode: ThemeMode, val dynamicColor: Boolean)
  class ThemePreferences(context: Context) {
      val config: StateFlow<ThemeConfig>
      fun setThemeMode(mode: ThemeMode)
      fun setDynamicColor(enabled: Boolean)
  }
  ```

- [ ] **Step 1: Write unit test for ThemeConfig and default settings**

Write `mobile/app/src/test/java/com/switchboard/app/data/ThemePreferencesTest.kt`:
```kotlin
package com.switchboard.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ThemePreferencesTest {
    @Test
    fun defaultConfig_isSystemAndNoDynamicColor() {
        val config = ThemeConfig(mode = ThemeMode.SYSTEM, dynamicColor = false)
        assertEquals(ThemeMode.SYSTEM, config.mode)
        assertFalse(config.dynamicColor)
    }

    @Test
    fun themeMode_enumValues_matchExpected() {
        val names = ThemeMode.entries.map { it.name }
        assertEquals(listOf("SYSTEM", "DARK", "LIGHT"), names)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew :app:testDebugUnitTest --tests com.switchboard.app.data.ThemePreferencesTest`
Expected: FAIL (types not yet defined).

- [ ] **Step 3: Implement ThemePreferences and ThemeConfig**

Write `mobile/app/src/main/java/com/switchboard/app/data/ThemePreferences.kt`:
```kotlin
package com.switchboard.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode {
    SYSTEM,
    DARK,
    LIGHT
}

data class ThemeConfig(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false
)

class ThemePreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("switchboard_theme_prefs", Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<ThemeConfig> = _config.asStateFlow()

    private fun loadConfig(): ThemeConfig {
        val modeStr = prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name
        val mode = try {
            ThemeMode.valueOf(modeStr)
        } catch (_: IllegalArgumentException) {
            ThemeMode.SYSTEM
        }
        val dynamicColor = prefs.getBoolean(KEY_DYNAMIC_COLOR, false)
        return ThemeConfig(mode = mode, dynamicColor = dynamicColor)
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
        _config.value = _config.value.copy(mode = mode)
    }

    fun setDynamicColor(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DYNAMIC_COLOR, enabled).apply()
        _config.value = _config.value.copy(dynamicColor = enabled)
    }

    companion object {
        private const val KEY_THEME_MODE = "pref_theme_mode"
        private const val KEY_DYNAMIC_COLOR = "pref_dynamic_color"
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew :app:testDebugUnitTest --tests com.switchboard.app.data.ThemePreferencesTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add mobile/app/src/main/java/com/switchboard/app/data/ThemePreferences.kt mobile/app/src/test/java/com/switchboard/app/data/ThemePreferencesTest.kt
git commit -m "feat(android): add ThemePreferences storage and configuration model"
```

---

### Task 2: Expressive Design Tokens, Motion, and Theme Upgrade

**Files:**
- Create: `mobile/app/src/main/java/com/switchboard/app/ui/Motion.kt`
- Modify: `mobile/app/src/main/java/com/switchboard/app/ui/Theme.kt`

**Interfaces:**
- Produces:
  ```kotlin
  object ExpressiveMotion {
      val Bouncy = spring<Float>(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
      val Snappy = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
  }
  fun Modifier.bouncyClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier
  @Composable fun SwitchboardTheme(themeConfig: ThemeConfig = ThemeConfig(), content: @Composable () -> Unit)
  ```

- [ ] **Step 1: Implement Motion.kt**

Write `mobile/app/src/main/java/com/switchboard/app/ui/Motion.kt`:
```kotlin
package com.switchboard.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

object ExpressiveMotion {
    val Bouncy = spring<Float>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMediumLow
    )

    val Snappy = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium
    )
}

/**
 * Spring-based press scale modifier providing tactile Material 3 Expressive feedback.
 */
fun Modifier.bouncyClickable(
    enabled: Boolean = true,
    pressedScale: Float = 0.96f,
    onClick: () -> Unit
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale = remember { Animatable(1f) }

    LaunchedEffect(isPressed) {
        scale.animateTo(
            targetValue = if (isPressed) pressedScale else 1f,
            animationSpec = ExpressiveMotion.Bouncy
        )
    }

    this
        .graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClick = onClick
        )
}
```

- [ ] **Step 2: Update Theme.kt with Expressive Shapes and Container Tiers**

Update `mobile/app/src/main/java/com/switchboard/app/ui/Theme.kt`:
```kotlin
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
```

- [ ] **Step 3: Compile debug Kotlin to verify no compilation errors**

Run: `.\gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add mobile/app/src/main/java/com/switchboard/app/ui/Motion.kt mobile/app/src/main/java/com/switchboard/app/ui/Theme.kt
git commit -m "feat(android): add expressive motion tokens and update theme hierarchy"
```

---

### Task 3: Expressive Controls, Sliders & Items Redesign

**Files:**
- Modify: `mobile/app/src/main/java/com/switchboard/app/ui/Controls.kt`

**Interfaces:**
- Produces:
  ```kotlin
  @Composable fun SectionCard(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(24.dp), content: @Composable ColumnScope.() -> Unit)
  @Composable fun EmptyCard(title: String, body: String)
  @Composable fun LevelRow(icon: ImageVector, label: String, value: Int, min: Int, max: Int, enabled: Boolean = true, onChange: (Int) -> Unit)
  @Composable fun DisplayCard(display: Display, detailed: Boolean = true, onBrightness: (Int) -> Unit, onContrast: (Int) -> Unit)
  @Composable fun VolumeCard(level: Int, muted: Boolean, onVolume: (Int, Boolean) -> Unit)
  @Composable fun NowPlaying(media: MediaState, artwork: ImageBitmap?, artSize: Dp = 72.dp)
  @Composable fun TransportRow(playing: Boolean, onMedia: (String) -> Unit)
  ```

- [ ] **Step 1: Implement Expressive Controls in Controls.kt**

Update `mobile/app/src/main/java/com/switchboard/app/ui/Controls.kt` to include:
- `SectionCard` with 24.dp rounded corners and subtle outline.
- `LevelRow` with expressive pill slider layout, tactile feedback, and animated percentage badge.
- `VolumeCard` with expressive mute toggle (tonal container shift).
- `NowPlaying` with artwork spring scale and animated 3-bar equalizer pulse when `media.isPlaying`.
- `TransportRow` with 56.dp central Play/Pause morphing button and spring scale buttons.

- [ ] **Step 2: Compile debug Kotlin**

Run: `.\gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add mobile/app/src/main/java/com/switchboard/app/ui/Controls.kt
git commit -m "feat(android): redesign controls with expressive sliders, cards, and animated equalizer"
```

---

### Task 4: Settings Screen Implementation

**Files:**
- Create: `mobile/app/src/main/java/com/switchboard/app/ui/SettingsScreen.kt`

**Interfaces:**
- Produces:
  ```kotlin
  @Composable
  fun SettingsScreen(
      themeConfig: ThemeConfig,
      onSetThemeMode: (ThemeMode) -> Unit,
      onSetDynamicColor: (Boolean) -> Unit,
      onBack: () -> Unit,
      modifier: Modifier = Modifier
  )
  ```

- [ ] **Step 1: Implement SettingsScreen.kt**

Write `mobile/app/src/main/java/com/switchboard/app/ui/SettingsScreen.kt` featuring:
- Theme Mode Segmented / Radio selector (Follow System, Dark, Light) with expressive cards and checkmarks.
- Dynamic Theming (Material You) toggle with description and Android 12+ version check.
- Live Color Palette Preview chips displaying current `primary`, `secondary`, `tertiary`, and `surfaceContainer`.
- About Developer & System Card (`aiyu-ayaan`, Monorepo architecture summary, E2EE protocol, App Version 0.1.0).

- [ ] **Step 2: Compile debug Kotlin**

Run: `.\gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add mobile/app/src/main/java/com/switchboard/app/ui/SettingsScreen.kt
git commit -m "feat(android): add SettingsScreen with theme selector and developer info"
```

---

### Task 5: Expressive Pairing Screen & Dialog Redesign

**Files:**
- Modify: `mobile/app/src/main/java/com/switchboard/app/ui/PairingScreen.kt`

**Interfaces:**
- Produces:
  ```kotlin
  @Composable fun PairingScreen(hosts: List<KnownHost>, error: String?, onScan: () -> Unit, onManual: (String, String) -> Unit, onConnect: (KnownHost) -> Unit, onForget: (KnownHost) -> Unit, modifier: Modifier = Modifier)
  ```

- [ ] **Step 1: Upgrade PairingScreen.kt to Material 3 Expressive**

Update `mobile/app/src/main/java/com/switchboard/app/ui/PairingScreen.kt` with:
- 28.dp rounded Hero Card for connecting a desktop.
- Expressive Scan QR button (Filled Button, 52.dp height, pill/rounded corners).
- Expressive Outlined manual entry button.
- Animated high-contrast error banner with `AnimatedVisibility`.
- Expressive `HostRow` cards with `bouncyClickable`, rounded icon badge, and quick connect.
- 28.dp rounded `AlertDialog` for Forget Host confirmation and Manual Pairing dialog.

- [ ] **Step 2: Compile debug Kotlin**

Run: `.\gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add mobile/app/src/main/java/com/switchboard/app/ui/PairingScreen.kt
git commit -m "feat(android): modernize pairing screen and dialogs with expressive styling"
```

---

### Task 6: Expressive Home Screen & MainActivity Navigation

**Files:**
- Modify: `mobile/app/src/main/java/com/switchboard/app/ui/HomeScreen.kt`
- Modify: `mobile/app/src/main/java/com/switchboard/app/MainActivity.kt`

**Interfaces:**
- Produces:
  - Navigation state handling `Screen.Main`, `Screen.Section`, `Screen.Settings`.
  - Spring-driven `AnimatedContent` for screen transitions.
  - Animated top bar title and settings icon button.
  - Expressive `ConnectedDeviceCard` with pulsing status badge.
  - Expressive `SectionRow` with pill icon badges and `bouncyClickable`.

- [ ] **Step 1: Update HomeScreen.kt with Expressive Cards and Animated Badges**

- [ ] **Step 2: Update MainActivity.kt with Settings Navigation and AnimatedContent**

- [ ] **Step 3: Compile debug Kotlin**

Run: `.\gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add mobile/app/src/main/java/com/switchboard/app/ui/HomeScreen.kt mobile/app/src/main/java/com/switchboard/app/MainActivity.kt
git commit -m "feat(android): integrate expressive navigation, settings, and animated screen transitions"
```

---

### Task 7: Full Verification & Documentation Update

**Files:**
- Modify: `development/devdocs/TODO.md`

- [ ] **Step 1: Run full unit test suite**

Run: `.\gradlew test`
Expected: All tests pass.

- [ ] **Step 2: Run full debug build assemble**

Run: `.\gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Update documentation in development submodule**

Update `development/devdocs/TODO.md` noting the completion of the Android Material 3 Expressive migration, animations, and Settings page.
Following submodule protocol: verify `git branch --show-current` is `main` inside `development/`, commit in submodule first (`docs(devdocs): update roadmap for Material 3 Expressive Android migration`), then commit submodule pointer in root repository (`docs(submodule): update development pointer for M3 Expressive migration`).

- [ ] **Step 4: Final verification check**

Run: `git status -s`
Ensure working tree is clean.
