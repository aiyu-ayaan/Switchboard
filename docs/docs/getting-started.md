# Download & Installation

This guide walks through downloading pre-compiled Switchboard binaries, installing the desktop and mobile applications, and completing your first local cryptographic pairing.

:::info Looking to Contribute or Build from Source?
If you are a developer looking to contribute or compile Switchboard from source, see the **[Contributor & Developer Setup Guide](/contributing)** instead.
:::

---

## 🚀 Official Releases

Pre-compiled, ready-to-run releases are published on GitHub:

👉 **[Download Latest Switchboard Release](https://github.com/aiyu-ayaan/Switchboard/releases)**

---

## 📋 System Requirements

| Platform | Minimum Requirement | Recommended |
| :--- | :--- | :--- |
| **PC (Desktop Host)** | Windows 10 (64-bit), Linux, or macOS | Windows 10/11 with DDC/CI monitor support |
| **Phone (Mobile Client)** | Android 8.0 Oreo (API 26) | Android 12+ (Material You dynamic theming) |
| **Local Network** | Wi-Fi or Ethernet on the same local subnet | 5 GHz Wi-Fi or wired PC + Wi-Fi phone |

:::note Internet Not Required
Switchboard communicates strictly over your local Wi-Fi or LAN. An active internet connection is **never** required for pairing, controls, or file transfers.
:::

---

## 🖥️ 1. Desktop Installation (PC)

1. Open the **[Latest Releases](https://github.com/aiyu-ayaan/Switchboard/releases)** page on your computer.
2. Scroll down to the **Assets** section.
3. Download the desktop executable:
   - **Windows**: `Switchboard-Setup.exe` (installer) or `Switchboard-portable.zip`.
4. Run the installer or extract the portable executable to your preferred folder.
5. Launch **Switchboard**.

### Windows Firewall & Tray Behavior
- **Firewall Prompt**: On first launch, Windows Defender Firewall may ask for permission for the Switchboard daemon to communicate on private networks. Check **Private networks** and click **Allow access**.
- **System Tray**: When you close the desktop window, Switchboard minimizes to your Windows system tray so your phone remains connected in the background. Right-click the tray icon to restore or quit.

---

## 📱 2. Mobile Installation (Android)

1. On your Android phone or tablet, open **[Latest Releases](https://github.com/aiyu-ayaan/Switchboard/releases)** in your browser.
2. Under **Assets**, download the Android package:
   - **Android**: `Switchboard.apk` (or `app-release.apk`).
3. Tap the downloaded APK in your browser or notification shade to begin installation.
4. **Allow Unknown Apps**: If prompted by Android security (*"For your security, your phone is not allowed to install unknown apps from this source"*):
   - Tap **Settings**.
   - Enable **Allow from this source**.
   - Return and tap **Install**.
5. Open the **Switchboard** app from your app drawer.

---

## 🔗 3. First-Time Pairing

Switchboard uses ephemeral X25519 (ECDH) key exchange to establish an encrypted bridge without usernames, accounts, or passwords.

```
┌───────────────────────────┐                ┌───────────────────────────┐
│       Desktop Host        │                │      Android Client       │
│  1. Open "Paired Devices" │  Scan QR Code  │  1. Tap "Scan QR Code"    │
│  2. Display QR / PIN code │ ─────────────> │  2. Point camera at PC    │
│  3. Accept Connection     │ <───────────── │  3. Instant E2EE Key Sync │
└───────────────────────────┘                └───────────────────────────┘
```

1. **Open Pairing Screen on PC**:
   - In Switchboard Desktop, click the **Paired Devices** icon (left sidebar).
   - Your PC will generate and display a unique QR code and a 10-character manual PIN.
2. **Connect from Phone**:
   - **Method A (QR Scan — Fastest)**: Tap **Scan QR code** in the Android app. Grant camera permission and point your camera at the PC monitor. Pairing completes within milliseconds.
   - **Method B (Local Network Auto-Discovery)**: Ensure both devices are on the same Wi-Fi. Your PC will appear under **Found on this network** via mDNS. Tap your PC and enter the 10-character code displayed on your monitor.
3. **Start Controlling**:
   - Once paired, your phone immediately displays the live control deck with your hardware monitors, per-app audio mixer, air mouse touchpad, and encrypted file transfer hub!

---

## 🔒 Security & Privacy Features

- **End-to-End Encryption**: Every packet, slider movement, and file transfer is encrypted with AES-256-GCM / ChaCha20-Poly1305.
- **Zero Cloud Relays**: Your files and commands never touch third-party servers.
- **Instant Device Revocation**: To disconnect or revoke trust, tap **Forget Host** on your phone or click the **Remove Device** icon in the desktop Paired Devices tab.

---

## ❓ Troubleshooting Common Setup Issues

- **Devices cannot discover each other?** Verify that both your PC and phone are connected to the same Wi-Fi SSID and that "AP Isolation" / "Client Isolation" is disabled in your router settings.
- **Monitor brightness sliders not moving hardware?** Ensure your external monitor has **DDC/CI enabled** in its physical On-Screen Display (OSD) settings menu.
- **Windows Firewall blocking connection?** Ensure port `9427` (TCP) is allowed through Windows Defender Firewall for local subnet traffic.

For full troubleshooting steps, see the **[Troubleshooting & FAQ Guide](/troubleshooting)**.
