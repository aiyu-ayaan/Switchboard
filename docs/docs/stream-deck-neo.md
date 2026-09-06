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

### 4. Authentic Neo Industrial Chassis
- Sculpted off-white casing (`#F1F3F6`), chamfered bezel, and circular emblem.

---

## ⚡ Supported Actions

Stream Deck Neo keys can be configured with any of the following action types:

| Action Type | Value Example | Behavior |
| :--- | :--- | :--- |
| **`hotkey`** | `ctrl+c`, `win+d`, `alt+f4` | Synthesizes keyboard chord directly on the desktop via native `SendInput`. |
| **`url`** | `https://github.com` | Opens the URL in your desktop's default web browser. |
| **`media`** | `play_pause`, `next`, `volume_up` | Controls desktop media playback and system volume. |
| **`system`** | `lock`, `screenshot` | Locks workstation or opens Windows Snipping Tool (`Win+Shift+S`). |
| **`app`** | `notepad.exe`, `calc.exe` | Launches desktop executable or application. |

---

## 🔄 Bidirectional Customisation

Customisation can be performed seamlessly from **both** sides:

### From the Desktop App
1. Navigate to the **Stream Deck Neo** section in the desktop sidebar.
2. Click on any of the 8 keys to select it in the **Key Inspector**.
3. Choose an action type (`Hotkey`, `Open URL`, `Media`, `System`, or `Launch App`).
4. Select an icon from the built-in library, set background and text colors, and edit the key label.
5. Use the top toolbar to switch pages, add new pages, or test actions with the **Test Action** button.
6. Changes are immediately saved and broadcasted to your mobile device.

### From the Mobile App
1. Open **Stream Deck Neo** from the home screen.
2. Tap the **Edit (pencil)** button in the top right to enter customisation mode.
3. Tap any key to open the **Key Customizer** bottom sheet:
   - Edit the key label and pick an icon.
   - Choose the action category and configure target values.
   - Pick background and text color presets.
4. Tap the **Infobar** to open the **Infobar Settings** bottom sheet to toggle clock and date displays or customize typography colors.
5. Tap the **+** button to create new pages.
6. Every edit is sent to the host daemon and instantly reflected on the desktop interface.

---

## 📱 Mobile Landscape Experience

To provide an authentic 8-key horizontal console layout:

1. **Confirmation Prompt**: When tapping **Stream Deck Neo** on Android, Switchboard presents an alert dialog:
   > *"Stream Deck Neo is designed for horizontal operation to deliver an authentic 8-key hardware experience. Switchboard will rotate your screen into landscape mode."*
2. **Automatic Rotation**: Tapping **Continue to Deck** smoothly rotates the device into sensor landscape mode.
3. **Restoration on Exit**: Returning to the Home screen, tapping the top-bar back arrow, using the system back gesture, or disconnecting automatically restores your device to its standard portrait orientation.
