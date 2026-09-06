# Switchboard: Product Overview & Philosophy

Switchboard turns your Android smartphone or tablet into a versatile, low-latency control hub and direct file transfer station for your PC or laptop.

---

## 🎯 The Problem

Modern computing frequently places personal computers in configurations where physical interaction with hardware controls is awkward or fragmented:
- **Multiple External Monitors**: Adjusting brightness across multiple external monitors typically requires reaching behind each physical panel to navigate sluggish on-screen display (OSD) button menus.
- **Background Applications & Media**: Changing volume levels for a game, chat application, or music player often requires alt-tabbing or navigating deep into Windows sound settings.
- **Ad-Hoc File Transfer**: Sending photos or documents between phone and desktop typically relies on cloud-based messaging services (like Telegram, WhatsApp, or Slack), cloud drives (Google Drive, OneDrive), or clumsy USB cables.
- **Third-Party Bloat**: Existing remote control software usually demands online accounts, cloud relays, persistent internet connections, or invasive subscription tiers.

---

## 💡 The Switchboard Solution

Switchboard operates with a strict **local-first, zero-trust philosophy**:
1. **Zero External Dependence**: All traffic stays within your home or office local network (LAN / Wi-Fi).
2. **Direct Hardware Control**: Adjusts actual monitor backlights via VESA DDC/CI commands and taps directly into OS audio mixing sessions via WASAPI.
3. **Seamless Cryptographic Trust**: Pairing takes seconds using ephemeral asymmetric key exchange (X25519) via QR codes. No accounts, emails, or passwords.
4. **Native Performance**: Written in Go for the lightweight host daemon and Kotlin with Jetpack Compose for the mobile client.

---

## 🚀 Key Capabilities Matrix

| Feature | Switchboard | Traditional Remote Apps | Cloud File Sharing |
| :--- | :--- | :--- | :--- |
| **Network Footprint** | Pure local LAN / Wi-Fi | Relayed through cloud servers | Cloud storage servers |
| **Account Required** | ❌ None (Zero-password) | ✔️ Email / Account / Password | ✔️ Account required |
| **DDC/CI Hardware Displays** | ✔️ Native hardware VESA commands | ❌ Software gamma overlay only | ❌ None |
| **Per-App Audio Mixer** | ✔️ Direct Windows WASAPI sessions | ❌ Master volume only | ❌ None |
| **Media Transport & Artwork** | ✔️ Windows SMTC with album art | ⚠️ Partial / Emulated keys | ❌ None |
| **File Transfer Privacy** | ✔️ End-to-end encrypted direct P2P | ⚠️ Varies / often unencrypted | ⚠️ Stored on third-party servers |
| **Android Integration** | ✔️ Material You & SAF storage | ⚠️ Outdated non-native UI | ⚠️ Generic web / mobile app |

---

## 🖥️ Hardware & Platform Compatibility

### Desktop Host
- **Supported Operating Systems**:
  - **Windows 10 / 11** (Full feature set: DDC/CI, WMI internal display, WASAPI mixer, SMTC media transport).
  - **Linux / macOS** (Architecture supports modular OS drivers).
- **Display Compatibility**:
  - Any external display supporting **DDC/CI** connected via DisplayPort, HDMI, or USB-C.
  - Internal laptop displays supported via Windows WMI brightness controls.

### Android Mobile Client
- **Minimum Android Version**: Android 8.0 (Oreo, API Level 26) or higher.
- **Recommended**: Android 12+ for dynamic Material You theming and edge-to-edge layouts.
- **Permissions**:
  - `CAMERA`: Used exclusively while the QR scanner viewfinder is active.
  - `INTERNET` & `ACCESS_NETWORK_STATE`: For local network communication.
  - `POST_NOTIFICATIONS` & `FOREGROUND_SERVICE`: For uninterrupted background file transfers.
  - `FOREGROUND_SERVICE_CONNECTED_DEVICE` & `CHANGE_NETWORK_STATE`: For the optional **Stay Connected** mode, which holds the desktop session open while the app is backgrounded or cleared from Recents.
  - `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: Offered only from Settings, and only while **Stay Connected** is on — Android's Doze mode otherwise suspends network access with the screen off. The app works without it.
  - *No broad storage permissions required*: File transfers use the secure Android Storage Access Framework (SAF).
