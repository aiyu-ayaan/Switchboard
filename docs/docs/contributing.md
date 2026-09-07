# Contributor & Developer Setup

This guide walks contributors and developers through configuring the local development environment, running inner-loop dev processes, and building Switchboard packages from source.

:::tip Looking to Just Use Switchboard?
If you want to run Switchboard as an end-user, you do **not** need to set up this development environment. See the **[Download & Installation Guide](/getting-started)** to grab pre-compiled binaries from GitHub Releases!
:::

---

## 🛠️ Prerequisites

Before building Switchboard, ensure you have the following toolchains installed on your development machine:

### Host Machine (Desktop)
- **Git**: With submodule support.
- **Go**: Version 1.22 or newer ([Download Go](https://go.dev/dl/)).
- **Node.js**: Version 20 LTS or newer ([Download Node.js](https://nodejs.org/)).
- **pnpm**: Version 9 or newer (`npm install -g pnpm`).
- **C Compiler / Windows SDK** (Optional, for native cgo bindings if rebuilding custom system DLLs).

### Mobile Environment (Android)
- **Android Studio**: Ladybug (2024.2.1+) or newer ([Download Android Studio](https://developer.android.com/studio)).
- **Android SDK**: API 34+ installed.
- **JDK**: Java 17 or Java 21 (bundled with Android Studio).
- **Physical Android device or Emulator**: Running Android 8.0+ (API 26+) with USB or Wi-Fi debugging enabled.

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

Or run them in separate terminals for independent logging and faster debugging:

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
2. Select **Open** and choose the `mobile/` directory.
3. Allow Gradle to sync dependencies.
4. Select your target physical device or emulator from the device toolbar.
5. Click **Run** (`Shift + F10`) or **Debug** (`Shift + F9`).

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

## 📦 4. Building for Production

### Desktop Production Build
```bash
# Build the Go backend binary
pnpm build:backend

# Build the Electron frontend distribution package
pnpm build:frontend

# Package the installer executable
pnpm package
```
Production output will be generated in `frontend/dist/`.

### Android Production Build
```bash
# From repository root
pnpm android:release

# Or directly in the mobile directory
cd mobile
./gradlew assembleRelease
```
The signed APK will be located in `mobile/app/build/outputs/apk/release/`.

---

## 🏛️ Monorepo Topology

The project is arranged as a clean monorepo:

```
Switchboard/
├── backend/          # Go daemon: VESA DDC/CI, WASAPI audio mixer, SMTC, mDNS, SQLite
├── frontend/         # Electron host + React UI: Frameless window, Tokyo Night styling
├── mobile/           # Native Android app: Kotlin, Jetpack Compose, Material 3 Expressive
├── docs/             # Docusaurus documentation portal
├── development/      # Submodule (Switchboard-Docs): Architecture ADRs & specifications
├── scripts/          # Workspace build, dev runner, and packaging scripts
└── package.json      # pnpm workspace definition
```

---

## 📝 Contribution & Commit Standards

All commits to Switchboard must adhere to the Conventional Commits format:

```
<type>(<scope>): <short summary>

[optional description]
```

- **Allowed Types**: `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`, `perf`, `ci`, `revert`, `build`
- **Allowed Scopes**: `android`, `desktop`, `backend`, `devdocs`, `docs`, `root`, `crypto`, `system`, `release`, `submodule`
- **Submodule Dual-Commit**: If editing files inside `development/`, ensure you are on the `main` branch inside `development/`, commit inside the submodule first, then commit in the parent repository.
- **AI Agent Guidelines**: AI assistants must **never** execute `git push` and must never include AI attribution tags. Commits must reflect the repository author (`aiyu-ayaan`).
