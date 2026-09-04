# Material 3 Expressive Migration Specification

**Date:** 2026-09-04  
**Project:** Switchboard Mobile Client (`mobile/`)  
**Scope:** Android Jetpack Compose UI layer  

---

## 1. Objective

Migrate the Switchboard Android application to Google's **Material 3 Expressive** design pattern, introducing:
1. An expressive color and surface hierarchy (expanding container tiers and contrast).
2. Expressive rounded corner shapes and typography hierarchy.
3. Spring physics motion tokens and micro-interactions (press-down scaling, screen slide/scale transitions, animated equalizer).
4. Redesigned expressive items and controls (hero device card, section rows, sliders, now-playing card, transport controls, and dialogs).
5. A dedicated **Settings Screen** supporting dark/light/system theme switching, optional Material You dynamic theming, and an "About Developer & System" section.

---

## 2. Architecture & Theming

### 2.1 Color Tokens & Hierarchy (`Theme.kt`)
- **Base Brand Palette (Tokyo Night & Slate)**:
  - `Canvas` (`#16161E`) & `Surface` (`#1A1B26`)
  - Container tiers:
    - `surfaceContainerLowest`: `#13141C`
    - `surfaceContainerLow`: `#1A1B26`
    - `surfaceContainer`: `#21222D`
    - `surfaceContainerHigh`: `#292B38`
    - `surfaceContainerHighest`: `#343746`
  - Accents:
    - `primary`: `#9ECE6A` (Emerald / Level green)
    - `primaryContainer`: `#24381C`
    - `onPrimaryContainer`: `#D7EBC8`
    - `secondary`: `#7AA2F7` (Tokyo Night Blue)
    - `secondaryContainer`: `#242E46`
    - `tertiary`: `#BB9AF7` (Accent Purple)
    - `error`: `#F7768E` (Danger Red)
- **Light Theme**:
  - High-contrast day palette (`surfaceContainerLowest` `#FFFFFF`, `surfaceContainer` `#F0F2F8`, `surfaceContainerHighest` `#DFE2EE`, `primary` `#3F7D20`, `secondary` `#2B5CB8`).
- **Dynamic Theming (Material You)**:
  - On Android 12+ (`Build.VERSION.SDK_INT >= Build.VERSION_CODES.S`), if enabled by user in Settings, delegates to `dynamicDarkColorScheme` / `dynamicLightColorScheme`.

### 2.2 Expressive Shapes (`Theme.kt`)
- `ExtraSmall`: `8.dp` (badges, mini chips)
- `Small`: `12.dp` (chips, compact sliders)
- `Medium`: `18.dp` (buttons, dialog action triggers)
- `Large`: `24.dp` (section cards, host rows)
- `ExtraLarge`: `28.dp` - `32.dp` (hero cards, bottom sheets, alert dialogs)
- `Pill`: `CircleShape` / `RoundedCornerShape(percent = 50)`

### 2.3 Preferences Persistence (`ThemePreferences.kt`)
- Persistent storage using Android `SharedPreferences`:
  - `ThemeMode`: `SYSTEM`, `DARK`, `LIGHT`
  - `dynamicColor`: `Boolean` (defaults to `false`)
- Exposed as `StateFlow` / `State` to dynamically re-evaluate theme at runtime without app restart.

---

## 3. Motion & Animation Tokens (`Motion.kt` or `Theme.kt`)

### 3.1 Spring Specifications
- **`ExpressiveBouncy`**: `spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)`
  - Used for interactive tap scaling, modal sheet transitions, and play/pause morphing.
- **`ExpressiveSnappy`**: `spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)`
  - Used for layout shifts (`animateContentSize`), level sliders, and navigation crossfades.

### 3.2 Micro-Interactions
- `Modifier.bouncyClickable()`: Reusable modifier using Compose `InteractionSource` to spring scale cards down to `0.96f` on press and bounce back on release with haptic feedback.
- `AnimatedContent`:
  - Main screen transitions: slide + fade between `PairingScreen`, `HomeScreen`, `SectionScreen`, and `SettingsScreen`.
  - TopAppBar title transitions smoothly between screen titles and connection status.
- `NowPlaying` equalizer: Animated 3-bar vertical canvas/box visualizer pulsing when `isPlaying` is true.

---

## 4. Component Redesign

### 4.1 Cards & List Items (`HomeScreen.kt`, `Controls.kt`)
- **`ConnectedDeviceCard`**:
  - 28.dp rounded corner card.
  - Connection status badge with animated pulsing indicator dot (amber for connecting, green for connected).
  - Prominent host name and monospace address chip.
- **`SectionRow`**:
  - 24.dp rounded corner card with `bouncyClickable`.
  - Pill-shaped icon container with `primaryContainer` tonal background.
  - Title and summary subtitle layout with trailing chevron.
- **`SectionCard` & `EmptyCard`**:
  - Material 3 Expressive container elevation, subtle outline variant border, and generous internal padding.

### 4.2 Sliders & Controls (`Controls.kt`)
- **`LevelRow`**:
  - Expressive slider styling with thick capsule track.
  - Animated level indicator pill (`0%` to `100%`) with monospace font.
  - Smooth release value publishing.
- **`VolumeCard`**:
  - Expressive card with integrated mute button.
  - Visual mute state transitioning color to error/disabled with animated strike-through icon.
- **`TransportRow`**:
  - 56.dp central Play/Pause button with expressive primary container and spring bounce.
  - 48.dp secondary skip and stop buttons.
- **`NowPlaying`**:
  - Artwork with rounded corners and animated spring scale.
  - Active animated equalizer bar animation when audio is playing.

### 4.3 Pairing & Dialogs (`PairingScreen.kt`)
- **`PairingScreen`**:
  - Expressive Hero Card for QR scan and manual entry.
  - High-contrast error message banner with animated entry.
  - Expressive paired host cards with quick connect and delete actions.
- **Dialogs**:
  - 28.dp rounded corners for `AlertDialog` (`ConfirmForget` and `ManualPairingDialog`).
  - Filled tonal and text action buttons.

---

## 5. Settings Screen (`SettingsScreen.kt`)

### 5.1 Appearance & Theming
- **Theme Selection**:
  - 3 options: *Follow System*, *Dark*, *Light*.
  - Expressive segmented picker or radio cards with checkmarks.
- **Dynamic Theming (Material You)**:
  - Toggle switch with explanatory label. Disabled with helper text if Android < 12.
- **Color Palette Preview**:
  - Preview chips showing active primary, secondary, tertiary, and surface colors.

### 5.2 About Developer & System
- **Developer Section**:
  - Name: `aiyu-ayaan` (Aiyu Ayaan).
  - Role: Creator & Maintainer.
  - GitHub repository link.
- **System Architecture**:
  - Protocol: Zero-trust ephemeral ECDH (X25519) + AES-256-GCM / ChaCha20-Poly1305.
  - Stack: Kotlin + Jetpack Compose + Material 3 Expressive.
  - Monorepo: Host Daemon (Go + SQLite) & Mobile Client.
  - Version: `v0.1.0`.

---

## 6. Verification & Quality Gates

1. **Compilation**: `./gradlew compileDebugKotlin` succeeds with 0 warnings/errors.
2. **Tests**: `./gradlew test` passes.
3. **Behavioral Integrity**:
   - Camera QR scanner launcher remains unchanged.
   - Host pairing, connection, and disconnection flows remain intact.
   - Display brightness/contrast and volume sliders retain their debounce/release publishing.
4. **Git Standards**:
   - Strictly local commits (no `git push`).
   - Conventional commit messages (`feat(android): ...`, `refactor(android): ...`).
   - No AI attribution in commits.
