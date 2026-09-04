# Switchboard Mobile

Native Android client: Kotlin, Jetpack Compose, Material 3. `minSdk 26`, `compileSdk 37`.

## Features

- Pair by scanning the desktop QR code, or by typing the address and code.
- Control per-monitor brightness and contrast over DDC/CI, master volume, and media transport.
- Switch between multiple paired desktops; reconnect to the last one automatically.
- "Forget" a desktop, purging its stored keys.

## Structure

```
mobile/app/src/main/java/com/switchboard/app/
├── MainActivity.kt          # Compose entry, scanner launcher
├── SwitchboardViewModel.kt  # Connection lifecycle, optimistic edits
├── crypto/SessionCrypto.kt  # Mirror of backend/internal/crypto
├── data/HostStore.kt        # Identity + known hosts, EncryptedSharedPreferences
├── net/
│   ├── Protocol.kt          # Wire types
│   └── SwitchboardClient.kt # OkHttp WebSocket, handshake, framing
└── ui/                      # Theme, PairingScreen, ControlScreen
```

## Notes

- X25519 comes from BouncyCastle: the platform `XDH` provider only exists from API 33, and this app supports 26.
- QR scanning is delegated to `zxing-android-embedded`, whose activity requests the camera permission itself — the app never holds `CAMERA` outside a scan.
- Material You is deliberately **off**: the app mirrors the desktop palette, and a wallpaper-derived scheme would break that.
- `network_security_config.xml` permits cleartext because hosts are raw LAN addresses with no certifiable domain. Confidentiality and authentication come from the application-layer handshake, not TLS.

## Development

Create `local.properties`:

```properties
sdk.dir=/path/to/Android/sdk
```

Then from the workspace root:

```bash
pnpm android:build   # assembleDebug
pnpm android:run     # install + launch
pnpm android:test    # unit tests
```

Or open `mobile/` in Android Studio.

### Emulator

An emulator usually cannot reach the host's LAN address. Bridge the port and pair with `127.0.0.1:9427`:

```bash
adb reverse tcp:9427 tcp:9427
```

## Tests

`SessionCryptoInteropTest` pins the handshake to the Go daemon byte-for-byte: public keys, both proofs, an outbound frame, and a host-sealed frame that must decrypt. The expected values come from `TestPrintInteropVector` in `backend/internal/crypto`; regenerate and update both sides together after a protocol change.
