# Getting Started with Switchboard

This guide walks you through building and running Switchboard across the desktop host and an Android device.

## Prerequisites

- **Desktop**
  - Node.js 20+ and pnpm 9+
  - Go 1.22+ (no C toolchain needed — SQLite is pure Go)
- **Mobile**
  - Android SDK with **platform 37** and build-tools installed
  - JDK 17+ (Gradle 9.7 also runs on newer JDKs)
  - A device or emulator on API 26+

Phase 1 system control (displays, audio, media) is implemented for **Windows**. The daemon builds and runs on macOS and Linux, where those calls return `not supported on this platform`; pairing, transport, and both UIs work there.

## 1. Install dependencies

```bash
pnpm install
```

## 2. Run the desktop app

```bash
pnpm dev
```

This starts the Go daemon and the Electron UI together. To run them separately:

```bash
pnpm dev:backend     # go run ./cmd/server
pnpm dev:frontend    # vite + electron
```

The daemon listens on port **9427**. Override it with `--port` or `SWITCHBOARD_PORT`.

The Electron app starts a daemon itself only if nothing already answers on the local API, so running both commands does not spawn two daemons.

## 3. Build a release bundle

```bash
pnpm build            # daemon + desktop + Android
pnpm build:backend    # -> bin/switchboard(.exe)
pnpm build:frontend   # -> frontend/dist
```

## 4. Build and run the Android client

Point Gradle at your SDK by creating `mobile/local.properties`:

```properties
sdk.dir=/path/to/Android/sdk
```

Then:

```bash
pnpm android:build    # assembleDebug
pnpm android:run      # install + launch on a connected device
pnpm android:test     # unit tests, including the crypto interop vector
```

Or open `mobile/` in Android Studio.

## 5. Pair a phone

1. Open the desktop app and select **Devices** in the left rail.
2. On the phone, tap **Scan QR code** and point it at the desktop.

   Or tap **Enter code manually** and type the address and code the desktop shows (for example `192.168.1.105:9427` and `S8W6Q7HRKF`).
3. The code is valid for five minutes; **New code** issues a fresh one and invalidates the old.

Both devices must be on the same network. After the first pairing the phone reconnects on its own — the stored key is the credential, so there is no code to re-enter.

To revoke access, use the trash icon beside the device on the desktop, or **Forget** on the phone. Revoking on the desktop drops the device's live connection immediately.

### Running against an emulator

An emulator cannot usually reach the host's LAN address. Bridge the port first, then pair with `127.0.0.1:9427`:

```bash
adb reverse tcp:9427 tcp:9427
```

## Troubleshooting

**"No controllable displays found"** — enable DDC/CI in the monitor's own on-screen menu. Some docks and KVM switches do not pass the control channel through. Use **Rescan** after changing displays.

**Port already in use** — another process may hold 9427. Start the daemon with `--port 9500` and set `SWITCHBOARD_PORT=9500` for the desktop app.

**The phone cannot connect** — confirm both devices are on the same subnet and that the host firewall allows inbound TCP on the daemon port.
