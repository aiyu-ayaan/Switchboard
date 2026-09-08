# Elgato Stream Deck Neo

The **Elgato Stream Deck Neo** integration brings a physical desktop console experience directly to your phone or desktop screen. Guarded by the `deck` capability, it replicates the hardware design, key feel, and layout of Elgato's Stream Deck Neo.

---

## 🎮 Hardware Replication

Switchboard's Stream Deck Neo interface mirrors the exact physical form factor of the official hardware:

```
+-------------------------------------------------------------+
|  [Page 1 (1/2)]                    ( G )                    |
|                                                             |
|   +-------+     +-------+         +-------+     +-------+   |
|   | Home  |     | Notes |         | Files |     |Calendar|   |
|   +-------+     +-------+         +-------+     +-------+   |
|                                                             |
|     ( < )     [   10:24 AM  •  MONDAY 12/5/26   ]     ( > ) |
|    ( • • )    [             PAGE 1              ]    ( • • )|
|                                                             |
|   +-------+     +-------+         +-------+     +-------+   |
|   | Google|     |YouTube|         |Spotify|     |LinkedIn|  |
|   +-------+     +-------+         +-------+     +-------+   |
+-------------------------------------------------------------+
```

### 1. Eight Squircle LCD Keys (2x4 Grid)
- **Squircle Key Caps**: High-contrast, rounded LCD keys with responsive tactile feedback, click depth animations, and Android haptic taps.
- **Customizable Appearance**: Each key can have custom icons, background color accents, text labels, and font colors.
- **Instant Execution**: Tapping or clicking triggers host-side execution with sub-millisecond local network latency.

### 2. Central Dynamic Infobar
- **Real-Time Clock & Date**: Live 12-hour clock (`10:24 AM`) and date readout (`MONDAY 12/5/26`).
- **Page Information**: Displays the active page title and index.
- **Customizable Styling**: Configurable text color and font scaling.

### 3. Dual Touch Points & LED Line Indicators
- **Capacitive Touch Sensors**: Touch points on the left and right flank the Infobar for fast multi-page switching (`<` and `>`).
- **LED Bars**: Glowing indicator lines illuminate upon contact and indicate available pages.

### 4. Theme-Aware Neo Industrial Chassis
- The Android console uses Material 3 semantic surface, outline, and text colors, so it follows the selected system/light/dark or dynamic theme rather than imposing a light hardware frame.
- The Electron console uses the application's dark surface tokens, preserving a consistent desktop shell without a white panel.
- The circular emblem, OLED-style Infobar, and capacitive touch points keep the Neo hardware character while retaining readable contrast.

---

## ⚡ Supported Actions

Stream Deck Neo keys can be configured with any of the following action types:

| Action Type | Value Example | Behavior |
| :--- | :--- | :--- |
| **`hotkey`** | `ctrl+c`, `win+d`, `alt+f4` | Synthesizes keyboard chord directly on the desktop via native `SendInput`. |
| **`url`** | `https://github.com` | Opens the URL in your desktop's default web browser. |
| **`media`** | `play_pause`, `next`, `volume_up` | Controls desktop media playback and system volume. |
| **`system`** | `lock`, `screenshot` | Locks workstation or opens Windows Snipping Tool (`Win+Shift+S`). |
| **`app`** | `C:\...\Code.exe`, `notepad` | Launches desktop executable or application discovered on the host. |

### 🚀 Installed Applications Discovery
Both desktop and mobile key customizers feature an integrated **Installed Applications** picker:
- Automatically enumerates launchable Start Menu shortcuts from the machine-wide and per-user Programs folders, plus core system utilities (Notepad, Calculator, Windows Terminal, PowerShell, Command Prompt, Explorer, Task Manager, Snipping Tool, Paint, Settings).
- Includes real-time search filtering.
- Shows an appropriate semantic app icon, then automatically populates the command path, key title, and matching deck icon when selected.

### 🖼️ Real Icons on Every Key
A key wears the artwork of what it actually launches, not a stand-in glyph:

- **App keys** use the application's own Windows icon. Shortcuts are followed to their target first, and built-in commands such as `notepad` and `explorer` are resolved to their real executable, so each one looks like the app rather than a generic document.
- **Link keys** use the website's own logo, read from the icons the site declares (falling back to `/favicon.ico`). The logo is stored with the key, so the same artwork appears on your phone's deck too.
- If neither can be resolved, the key falls back to the icon you picked from the built-in library. You can always override the artwork by choosing a glyph in the inspector.

---

## 🔄 Bidirectional Real-Time Synchronization

Customisation can be performed seamlessly from **both** sides with instant synchronization:

### From the Desktop App
1. Navigate to the **Stream Deck Neo** section in the desktop sidebar.
2. Click on any of the 8 keys to select it in the **Key Inspector**.
3. Choose an action type (`Hotkey`, `Open URL`, `Media`, `System`, or `Launch App`).
4. Select an application from the searchable installed apps list or enter a custom path/command.
5. Select an icon from the built-in library, set background and text colors, and edit the key label.
6. Use the top toolbar to switch pages, add new pages, or test actions with the **Test Action** button.
7. Changes are immediately saved to the daemon and broadcast to all connected mobile clients via `deck.state`.

### From the Mobile App
1. Open **Stream Deck Neo** from the home screen.
2. Tap the **Edit (pencil)** button in the header to enter customisation mode.
3. Tap any key to open the **Key Customizer** bottom sheet:
   - Edit the key label and pick an icon.
   - Choose the action category and configure target values.
   - Under **Launch App**, search and pick from installed desktop applications retrieved live from the host PC.
   - Pick background and text color presets.
4. Tap the **Infobar** to open the **Infobar Settings** bottom sheet to toggle clock, media, page, or custom text displays.
5. Tap the **+** button to create new pages.
6. Every edit is immediately transmitted via WebSocket (`deck.set`) and synchronized with the desktop app.

---

## 📱 Mobile Landscape & Fullscreen Experience

To provide an authentic 8-key horizontal console layout:

1. **Confirmation Prompt**: When tapping **Stream Deck Neo** on Android, Switchboard presents an alert dialog:
   > *"Stream Deck Neo is designed for horizontal operation to deliver an authentic 8-key hardware experience. Switchboard will rotate your screen into landscape mode."*
2. **Automatic Rotation & Fullscreen Immersion**: Tapping **Continue to Deck** smoothly rotates the device into landscape mode. The standard TopAppBar is automatically hidden to dedicate 100% of the display to the console hardware surface.
3. **Integrated Exit Control**: A dedicated circular back button in the console header allows instant 1-tap exit.
4. **Restoration on Exit**: Exiting the Deck screen or disconnecting immediately restores the device to standard portrait orientation.
