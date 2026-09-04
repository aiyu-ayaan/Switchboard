# Switchboard Architecture & System Design

This document details the architectural layout, process boundaries, data flows, and communication layers across the Switchboard platform.

---

## 🏛️ High-Level System Architecture

Switchboard is engineered around three distinct execution domains:

```
┌────────────────────────────────────────────────────────────────────────┐
│                          Mobile Client (Android)                       │
│                                                                        │
│   UI Layer (Jetpack Compose, MVI, Material You)                        │
│         │                                                              │
│         ▼                                                              │
│   ViewModel & State Repository                                         │
│         │                                                              │
│         ▼                                                              │
│   Network Engine (OkHttp WebSocket, Retrofit HTTP)                     │
│         │                                                              │
│         ▼                                                              │
│   Crypto Engine (X25519 ECDH, ChaCha20-Poly1305, EncryptedSharedPreferences)
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                         Encrypted LAN Transport
                      (WebSocket / Local HTTP Streaming)
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│                         Host Daemon (Go Service)                       │
│                                                                        │
│   Server & Routing (net/http, gorilla/websocket)                       │
│         │                                                              │
│         ├─── Pairing & Crypto Engine (Ephemeral ECDH, Session Registry)│
│         ├─── Transfer Engine (Encrypted Chunk Streamer, SHA-256 Check) │
│         ├─── Database Layer (SQLite via modernc.org/sqlite)            │
│         └─── System Controller (Platform Driver Layer)                 │
│                   │                                                    │
│                   ├─── Displays: VESA MCCS via dxva2.dll & WMI         │
│                   ├─── Master Audio & App Mixer: WASAPI Core Audio     │
│                   └─── Media Transport & Artwork: WinRT / SMTC         │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                            Loopback REST API
                            (127.0.0.1:9427/local)
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│                       Desktop Host UI (Electron)                       │
│                                                                        │
│   Main Process (Tray Lifecycle, Daemon Child Process, Safe Native APIs)│
│         │ (Context-Isolated IPC Bridge)                                │
│   Preload Bridge (window.switchboard)                                  │
│         │                                                              │
│   Renderer Process (React 18, Tailwind CSS, Tokyo Night Dark Theme)    │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 🖥️ 1. Host Daemon (`backend/`)

The host daemon is a lightweight, compiled Go service designed for minimal memory and CPU footprint.

### Key Packages
- **`cmd/server`**: Bootstraps configuration, sets up graceful OS signal handling, initializes SQLite storage, and activates the controller.
- **`internal/server`**:
  - Implements the WebSocket endpoint (`/ws`) for encrypted mobile duplex communication.
  - Implements the loopback-only REST API (`/local/*`) used exclusively by the local Electron frontend.
  - Houses the pairing token rotation and verification state.
- **`internal/crypto`**:
  - Cryptographic primitives: Curve25519 / X25519 key pair generation, scalar multiplication for shared secret derivation, and AEAD cipher sealing.
- **`internal/system`**:
  - Native Windows bindings to hardware and OS subsystems.
  - Wraps `dxva2.dll` for external monitors, WMI for laptop internal displays, and COM interfaces for WASAPI Core Audio and Windows Media SMTC.
- **`internal/transfer`**:
  - Manages asynchronous file uploads and downloads.
  - Enforces chunk validation, stream throttling, and SHA-256 checksum computation.
- **`internal/db`**:
  - Embeds a pure-Go SQLite database engine storing paired device records, identity keys, and configuration.

---

## 💻 2. Desktop Host UI (`frontend/`)

The desktop application is built with **Electron**, **React 18**, and **Tailwind CSS**. It follows strict security practices:

### Security Boundaries
- **Context Isolation**: The Electron renderer operates with `contextIsolation: true` and `nodeIntegration: false`.
- **Preload Sandboxing**: The renderer cannot make arbitrary network requests or invoke Node.js filesystem APIs. It communicates exclusively via `window.switchboard` through the Electron IPC bridge.
- **Loopback-Only Proxy**: The Electron main process communicates with the Go daemon over local loopback (`http://127.0.0.1:9427/local/*`). The daemon validates that incoming requests originate from `127.0.0.1`.
- **Background Execution**: When closed, the window hides to the system tray (`hideToTray()`) so that mobile pairing and daemon operations remain uninterrupted.

---

## 📱 3. Mobile Client (`mobile/`)

The mobile client is a native Android application engineered using modern Android architecture best practices:

### Core Modules
- **UI Layer**: 100% declarative **Jetpack Compose** UI using Material Design 3 Expressive components, custom sliders, animated audio visualizers, and edge-to-edge support.
- **State Management**: Unidirectional Data Flow (UDF) powered by Kotlin Coroutines and `StateFlow`.
- **Storage Access Framework (SAF)**: Avoids demanding dangerous `MANAGE_EXTERNAL_STORAGE` or generic storage permissions by allowing the user to select specific download directories via Android system pickers.
- **Pairing & Camera**: Employs CameraX and ZXing barcode scanning for fast, robust QR code recognition with zero latency.
