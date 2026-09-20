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
3. Download the build for your system:

| System | Asset | Install |
| ------ | ----- | ------- |
| Windows 10 / 11 | `Switchboard-Setup-<version>.exe` | Run it. |
| Debian, Ubuntu, Linux Mint | `Switchboard-<version>.deb` | `sudo apt install ./Switchboard-<version>.deb` |
| Any other Linux | `Switchboard-<version>.AppImage` | `chmod +x` it, then run it. |

4. Launch **Switchboard**.

### Windows Firewall & Tray Behavior
- **Firewall Prompt**: On first launch, Windows Defender Firewall may ask for permission for the Switchboard daemon to communicate on private networks. Check **Private networks** and click **Allow access**.
- **System Tray**: When you close the desktop window, Switchboard minimizes to your Windows system tray so your phone remains connected in the background. Right-click the tray icon to restore or quit.

### Linux: one line instead of the download page

The AppImage can install itself, which saves finding it in ~/Downloads every
time you want to launch it:

```bash
curl -fsSL https://raw.githubusercontent.com/aiyu-ayaan/Switchboard/master/scripts/install.sh | sh
```

It takes the AppImage from the latest release and puts it under `~/.local`: the
app in `~/.local/lib/switchboard`, a `switchboard` command in `~/.local/bin`,
and an entry in your applications menu with the right icon.

- **To update**, run the same line again — it says so when you already have the
  newest build.
- **To remove it**, run it with `--uninstall`:

  ```bash
  curl -fsSL https://raw.githubusercontent.com/aiyu-ayaan/Switchboard/master/scripts/install.sh | sh -s -- --uninstall
  ```

- **Other options**: `--pre` takes the newest alpha or beta, `--version 1.2.3`
  pins a release, `--prefix /opt/switchboard` installs somewhere else, and
  `--help` lists them all.

Installing, updating and uninstalling all leave `~/.config/Switchboard` alone —
the host key and every paired phone live there, so none of the three un-pairs
anything.

### Linux notes

Install the `.deb` with `apt` rather than by double-clicking it, so the
dependencies come with it. `pactl` and `lspci` are among them: audio control
goes through the first and the second is what names your GPU on the About
System screen.

- **AppImage on Ubuntu 24.04 or newer** needs FUSE 2, which is no longer
  installed by default: `sudo apt install libfuse2t64`. The `.deb` has no such
  requirement.
- **External monitor brightness** needs you in the `i2c` group —
  `sudo usermod -aG i2c "$USER"`, then log out and back in. The built-in panel
  works without it.
- **The tray icon** needs an AppIndicator host. KDE, Cinnamon, XFCE and MATE
  have one; a stock GNOME session needs the AppIndicator extension, without
  which the window still works but closing it leaves no tray icon to restore.
- **Wayland**: the remote touchpad reaches only Xwayland windows. Log in to an
  X11 session for the full air mouse. Everything else — displays, audio, media,
  file transfer — is unaffected.

Full detail, including what each control reads and what is not implemented yet,
is in **[the Linux host page](/linux-host)**.

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
