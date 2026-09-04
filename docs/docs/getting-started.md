# Getting Started with Switchboard

This guide walks through configuring your environment, building all components, and running Switchboard across desktop and mobile devices.

---

## 🛠️ Prerequisites

Before building Switchboard, ensure you have the following tools installed:

### Host Machine
- **Git**: With submodule support.
- **Go**: Version 1.22 or newer ([Download Go](https://go.dev/dl/)).
- **Node.js**: Version 20 LTS or newer ([Download Node.js](https://nodejs.org/)).
- **pnpm**: Version 9 or newer (`npm install -g pnpm`).
- **C Compiler / Windows SDK** (Optional, for native cgo bindings if rebuilding custom system DLLs).

### Mobile Environment
- **Android Studio**: Ladybug (2024.2.1+) or newer.
- **Android SDK**: API 34+ installed.
- **JDK**: Java 17 or Java 21 (bundled with Android Studio).
- **Physical Android device or Emulator**: Running Android 8.0+ (API 26+) connected via USB or Wi-Fi debugging.

---

## 📥 1. Repository Setup

Clone the repository and its documentation submodules:

```bash
git clone --recurse-submodules https://github.com/aiyu-ayaan/Switchboard.git
cd Switchboard
```

If you previously cloned without `--recurse-submodules`:
```bash
git submodule update --init --recursive
```

Install workspace JavaScript/TypeScript dependencies:
```bash
pnpm install
```

---

## 💻 2. Running the Desktop Host

Switchboard Desktop consists of a **Go backend daemon** and an **Electron frontend**.

### Development Mode

You can run both concurrently using the workspace runner:
```bash
pnpm dev
```

Or run them in separate terminals for independent logging:

```bash
# Terminal 1: Backend Daemon
cd backend
go run cmd/server/main.go
```

```bash
# Terminal 2: Electron Frontend
pnpm dev:frontend
```

### Environment Variables

| Variable | Default | Purpose |
| :--- | :--- | :--- |
| `SWITCHBOARD_PORT` | `9427` | The local network port the Go daemon binds to for mobile WebSocket & HTTP connections. |
| `SWITCHBOARD_DB` | OS AppData (`%APPDATA%/switchboard/host.db`) | SQLite database path for persistent pairing keys and device registrations. |
| `SWITCHBOARD_DEV` | `0` | Set to `1` when developing to reload Vite dev server and unpackaged daemon binaries. |

---

## 📱 3. Running the Android Client

### Using Android Studio
1. Launch Android Studio.
2. Select **Open** and select the `mobile/` directory.
3. Allow Gradle to sync dependencies.
4. Select your target device or emulator from the device toolbar.
5. Click **Run** (`Shift + F10`).

### Using Command Line
```bash
# Build debug APK
pnpm android:build

# Install and launch debug APK on connected device/emulator
pnpm android:run

# Run unit tests
pnpm android:test
```

---

## 🔗 4. First-Time Pairing Workflow

1. Ensure your PC and Android device are connected to the same local Wi-Fi network or subnet.
2. Launch the desktop app and select the **Paired Devices** tab.
3. Open Switchboard on Android.
4. If this is your first time, the connection screen will prompt:
   - **Scan QR Code**: Grants camera access, scans the desktop QR code, and connects immediately.
   - **Enter code manually**: Enter the computer's local IP address and the 10-character pairing code shown on desktop.
5. Once paired, your mobile app displays the live dashboard with your computer's monitors, volume levels, and file transfer options.

---

## 📦 5. Building for Production

### Desktop Production Build
```bash
# Build the Go backend binary
pnpm build:backend

# Build the Electron frontend distribution package
pnpm build:frontend
```
Production output will be generated in `frontend/dist/`.

### Android Production Build
```bash
cd mobile
./gradlew assembleRelease
```
The signed APK will be located in `mobile/app/build/outputs/apk/release/`.
