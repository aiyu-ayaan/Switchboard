# Getting Started with Switchboard

This guide walks you through building and running Switchboard across desktop host and Android mobile devices.

## Prerequisites

- **Desktop**:
  - Node.js (v20+) and pnpm (v9+)
  - Go (v1.22+)
  - GCC / CGO toolchain (if compiling SQLite C-bindings on Windows/Linux)
- **Mobile**:
  - Android Studio Hedgehog (2023.1.1+) or newer
  - Android SDK 34 (Android 14)
  - Minimum device SDK 26 (Android 8.0 Oreo)

## Setup Steps

### 1. Desktop Host & Frontend

1. Install desktop frontend dependencies:
   ```bash
   pnpm install
   ```

2. Start desktop frontend in development mode:
   ```bash
   pnpm dev:frontend
   ```

### 2. Backend Daemon Service

1. Navigate to the backend directory:
   ```bash
   cd backend
   ```

2. Run the Go server:
   ```bash
   go run cmd/server/main.go
   ```

### 3. Android Mobile Application

1. Open the `mobile/` directory in **Android Studio**.
2. Sync Gradle dependencies.
3. Deploy to a connected physical Android device or emulator with network connectivity to your computer.

## Pairing Your Device

1. Launch both the desktop host application and the mobile app on the same local network.
2. The desktop UI will display an active pairing QR code.
3. Open Switchboard on your Android device and tap **Scan QR Code**.
4. Once scanned, the secure channel will establish, and your remote controls will activate immediately.
