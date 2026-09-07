---
sidebar_position: 1
slug: /intro
---

import useBaseUrl from '@docusaurus/useBaseUrl';

# Switchboard

Switchboard is an ultra-low-latency, zero-password encrypted control bridge that allows you to manage and control your host computer system directly from your mobile device.

<p align="center">
  <img src={useBaseUrl('img/screenshot_desktop_main.png')} alt="Switchboard Desktop Host" style={{maxWidth: '100%', borderRadius: '12px', border: '1px solid var(--switchboard-edge)'}} />
</p>

:::tip Download Switchboard
Official binaries for Windows (Desktop Host + Go Daemon) and Android (Native APK) are published on GitHub Releases:
- 🚀 **[Download Latest Release](https://github.com/aiyu-ayaan/Switchboard/releases)**
:::

## What's in these Docs

- **[Product Overview & Capabilities](/overview)** — Product vision, system architecture comparison with traditional tools, hardware compatibility, and phase milestones.
- **[App Features Showcase](/features)** — Comprehensive visual deep-dive with screenshots into Displays, Audio Mixer, File Transfer, Air Mouse, Stream Deck Neo, and Security.
- **[Getting Started & Build Guide](/getting-started)** — Prerequisites, repository structure, monorepo setup, inner-loop development commands, and packaging.
- **[System Architecture & Topology](/architecture)** — Monorepo layout, Go backend daemon, Electron desktop host, native Android client, and WebSocket packet flow.
- **[Host Discovery (mDNS / DNS-SD)](/discovery)** — How the desktop advertises \_switchboard._tcp\ and how the Android app automatically discovers changing network IPs without cloud intermediaries.
- **[Security & Pairing Protocol](/security-pairing)** — Zero-password trust model via ephemeral X25519 ECDH key exchange, QR code payloads, and AEAD encryption (AES-256-GCM / ChaCha20-Poly1305).
- **[API & Wire Protocol Reference](/api-protocol)** — Complete JSON wire format, client commands, host events, and loopback REST API endpoints.
- **[Displays & DDC/CI Hardware Controls](/displays-ddcci)** — VESA MCCS monitor communication via Windows DXVA2 and WMI laptop display brightness control.
- **[Audio Mixer & SMTC Transport](/audio-mixer)** — Windows Core Audio (WASAPI) session mixer, per-process sliders, and SMTC media transport controls.
- **[Air Mouse (Remote Touchpad)](/air-mouse)** — Sub-millisecond cursor navigation, multi-finger gesture recognition, and Windows SendInput injection.
- **[Elgato Stream Deck Neo](/stream-deck-neo)** — 8-key hardware console replication with LCD infobar, touch points, and custom action triggers.
- **[Encrypted File Transfer](/file-transfer)** — Chunked P2P encrypted file streaming, SHA-256 verification, and Android Storage Access Framework (SAF) integration.
- **[Troubleshooting & FAQ](/troubleshooting)** — Diagnosis and resolution for local network pairing, Windows Firewall configuration, and background tasks.

## Core Capabilities

- **Zero-Password Cryptographic Pairing**: No accounts, usernames, or passwords. Connect in seconds via camera QR scan or a 6-character code.
- **Hardware Display Controls**: Real-time brightness and contrast sliders for both external HDMI/DisplayPort panels (via DDC/CI) and laptop screens (via WMI).
- **Per-Application Volume Mixer**: Adjust individual volume levels for Chrome, Spotify, Discord, or games independently from master audio.
- **Rich Media Transport**: Synchronized playback control with album art extraction, track scrubbing, play/pause, and track skip.
- **Wireless P2P File Transfer**: High-speed, chunked local file transmission with resume capability and zero telemetry.
- **Air Mouse & Touchpad**: Turn your phone into an ultra-responsive touchpad with pointer acceleration, left/right clicks, and scrolling.
- **Switchboard Deck (Stream Deck Neo)**: Landscape-mode 8-key physical console emulator for launching apps, macros, and controlling system states.
- **Material Design 3 Expressive**: Beautiful, fluid mobile interface with dynamic wallpaper theming (Material You) and dark/light modes.
