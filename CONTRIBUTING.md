# Contributing to Switchboard

Thank you for your interest in contributing to **Switchboard**! This document provides complete instructions for developers and contributors looking to set up the local codebase, run applications in development mode, and build release packages from source.

If you simply want to **use** Switchboard, you do not need to build from source. Download pre-compiled binaries from the [GitHub Releases](https://github.com/aiyu-ayaan/Switchboard/releases) page.

---

## 📋 Table of Contents

1. [Prerequisites](#prerequisites)
2. [Repository Setup](#repository-setup)
3. [Running the Desktop Host](#running-the-desktop-host)
4. [Running the Android Client](#running-the-android-client)
5. [Building for Production](#building-for-production)
6. [Monorepo Architecture](#monorepo-architecture)
7. [Commit & Contribution Standards](#commit--contribution-standards)

---

## 🛠️ Prerequisites

Before building Switchboard, make sure you have installed the necessary toolchains for your platform:

### Host Machine (Desktop)
- **Git**: With submodule support.
- **Go**: Version 1.22 or newer ([Download Go](https://go.dev/dl/)).
- **Node.js**: Version 20 LTS or newer ([Download Node.js](https://nodejs.org/)).
- **pnpm**: Version 9 or newer:
  ```bash
  npm install -g pnpm
  ```
- **C Compiler / Windows SDK** (Optional, only needed if modifying native Windows C/C++ bindings).

### Mobile Environment (Android)
- **Android Studio**: Ladybug (2024.2.1+) or newer ([Download Android Studio](https://developer.android.com/studio)).
- **Android SDK**: API 34+ installed.
- **JDK**: Java 17 or Java 21 (bundled with Android Studio).
- **Physical Android Device or Emulator**: Running Android 8.0+ (API 26+) with USB or Wi-Fi debugging enabled.

---

## 📥 Repository Setup

Clone the repository along with its documentation submodules:

```bash
git clone --recurse-submodules https://github.com/aiyu-ayaan/Switchboard.git
cd Switchboard
```

If you previously cloned without `--recurse-submodules`, initialize them manually:

```bash
git submodule update --init --recursive
```

Install the root and workspace Node.js dependencies:

```bash
pnpm install
```

---

## 💻 Running the Desktop Host

Switchboard Desktop consists of two tightly coupled components:
1. **Backend Daemon (`backend/`)**: A lightweight Go service that interfaces with OS APIs (VESA DDC/CI, Windows WASAPI, SMTC, mDNS) and manages SQLite storage.
2. **Frontend GUI (`frontend/`)**: An Electron + React application styled with Tailwind CSS.

### Unified Development Runner
Run both the Go backend daemon and the Electron UI simultaneously with live reloading:

```bash
pnpm dev
```

### Running in Separate Terminals
If you need isolated logging or want to debug the backend independently:

```bash
# Terminal 1: Go Backend Daemon
cd backend
go run cmd/server/main.go
```

```bash
# Terminal 2: Electron Frontend
pnpm dev:frontend
```

### Environment Variables

| Variable | Default | Description |
| :--- | :--- | :--- |
| `SWITCHBOARD_PORT` | `9427` | Local network port for mobile WebSocket and HTTP connections. |
| `SWITCHBOARD_DB` | OS AppData (`%APPDATA%/switchboard/host.db`) | SQLite database path for persistent pairing records and keys. |
| `SWITCHBOARD_DEV` | `0` | Set to `1` during development to reload the Vite dev server. |

---

## 📱 Running the Android Client

The mobile app is written in **Kotlin** using **Jetpack Compose** and Material Design 3.

### Option A: Using Android Studio (Recommended)
1. Launch Android Studio.
2. Choose **Open** and select the `mobile/` directory.
3. Allow Gradle to sync dependencies.
4. Select your connected Android device or emulator in the toolbar.
5. Click **Run** (`Shift + F10`) or **Debug** (`Shift + F9`).

### Option B: Using Command Line
You can also run Gradle tasks using workspace npm scripts from the repo root:

```bash
# Build the debug APK
pnpm android:build

# Install and launch debug APK on connected device/emulator
pnpm android:run

# Run Android unit tests
pnpm android:test
```

---

## 📦 Building for Production

### Desktop Production Package
To build the compiled Go daemon and bundle the production Electron distribution:

```bash
# Build the Go backend binary
pnpm build:backend

# Build the Electron frontend package
pnpm build:frontend

# Package installer executable (electron-builder)
pnpm package
```
Production artifacts will be generated in `frontend/dist/`.

### Android Production Package
To compile and assemble a signed or unsigned release APK:

```bash
# From workspace root
pnpm android:release

# Or directly from mobile/
cd mobile
./gradlew assembleRelease
```
The output APK will be located at `mobile/app/build/outputs/apk/release/`.

---

## 🏛️ Monorepo Architecture

```
Switchboard/
├── backend/          # Go daemon, VESA DDC/CI, WASAPI audio mixer, mDNS, SQLite
├── frontend/         # Electron host, React UI, Tailwind CSS, Frameless window
├── mobile/           # Native Android app (Kotlin, Jetpack Compose, Material 3)
├── docs/             # Docusaurus documentation portal
├── development/      # Submodule (Switchboard-Docs): Architecture ADRs & specifications
├── scripts/          # Workspace build, dev runner, and packaging scripts
└── package.json      # pnpm workspace definition
```

---

## 📝 Commit & Contribution Standards

To maintain clean history and automated CI/CD releases, all contributions must adhere to these guidelines:

### 1. Commit Message Format
We follow the Conventional Commits specification:

```
<type>(<scope>): <short summary>

[optional description]
```

- **Allowed Types**: `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`, `perf`, `ci`, `revert`, `build`
- **Allowed Scopes**: `android`, `desktop`, `backend`, `devdocs`, `docs`, `root`, `crypto`, `system`, `release`, `submodule`
- **Imperative Mood**: Use "add feature", not "added feature". Keep summary under 72 characters.
- **Keep Commits Atomic**: Break distinct changes into smaller, logical, single-purpose commits.

### 2. Submodule Dual-Commit Workflow
If you make changes to files inside the `development/` directory:
1. Verify you are on the `main` branch inside `development/` (`git branch --show-current`).
2. Commit inside `development/` first.
3. Commit in the root repository second (staging `development`).
For full details, see [`development/devdocs/submodule-workflow.md`](development/devdocs/submodule-workflow.md).

### 3. Rules for AI Assistants
- **Never push to remote**: Never execute `git push`. Commits remain strictly local.
- **No AI attribution**: Commit authors must reflect the repository user (`aiyu-ayaan`). Do not add co-author tags for AI assistants.

### 4. Code Exploration
For symbol discovery and dynamic call path analysis across components, reach for **CodeGraph** (`codegraph_explore`) before using standard grep.

---

## ❓ Need Help?
- Browse our online documentation portal: [Switchboard Docs](https://aiyu-ayaan.github.io/Switchboard/)
- Read engineering design docs in [`development/devdocs/`](development/devdocs/)
- Open an issue or discussion on [GitHub](https://github.com/aiyu-ayaan/Switchboard/issues)
