# Switchboard Backend

The host daemon: a single Go binary, no cgo. SQLite is `modernc.org/sqlite` (pure Go), so it cross-compiles for Windows, macOS, and Linux without a C toolchain.

## Responsibilities

- **Transport**: one HTTP listener on port `9427` serving two surfaces.
  - `/ws` — mobile clients. Three plaintext handshake frames, then AES-256-GCM on every frame.
  - `/local/*` — the Electron UI. Refused unless the peer address is loopback.
- **Pairing**: X25519 identities, a five-minute typeable pairing code, and a double Diffie-Hellman key schedule with mutual proofs.
- **System control**: display brightness and contrast, master volume, media transport.
- **Storage**: host identity and paired devices in SQLite.

## Project Structure

```
backend/
├── cmd/server/main.go        # Entry point
├── internal/
│   ├── config/               # Flags, environment, per-user database path
│   ├── crypto/               # X25519, HKDF, AEAD framing  (+ interop vector)
│   ├── db/                   # SQLite storage layer
│   ├── protocol/             # Wire envelope and payload shapes
│   ├── server/               # Handshake, dispatch, local API
│   └── system/               # OS integration behind one Controller
└── go.mod
```

## Display control

Two backends, matching how PowerToys drives the same hardware:

- **External monitors** speak DDC/CI over I2C via `dxva2.dll` — VCP `0x10` (brightness) and `0x12` (contrast). Each panel reports its own minimum, current, and maximum; those limits are often not 0–100, so the daemon publishes the real range and clamps every write to it. Handles are cached because each open costs tens of milliseconds.
- **Internal laptop panels** have no DDC/CI bus and go through `WmiMonitorBrightnessMethods`. Brightness only — there is no contrast channel.

Panel names are read from the EDID block in the registry, so the UI shows `EK240Y P6` rather than `Generic PnP Monitor`.

Windows implementations live in `*_windows.go`. Other platforms build against `unsupported_other.go` and return `ErrUnsupported`.

## Running

```bash
go run ./cmd/server                 # defaults: port 9427, per-user db
go run ./cmd/server --port 9500     # or SWITCHBOARD_PORT / SWITCHBOARD_DB
go build -o ../bin/switchboard.exe ./cmd/server
```

## Tests

```bash
go test ./internal/...
```

Covers the crypto round trip, replay and tamper rejection, the full pairing / resume / revocation / rotation flow over a live socket, and DDC/CI enumeration against real hardware (skipped where no display bus exists).

`go test -v -run TestPrintInteropVector ./internal/crypto/` regenerates the fixed vector that the Android unit tests assert against. Re-run it and update both sides together after any protocol change.
