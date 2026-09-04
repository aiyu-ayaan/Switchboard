# Switchboard Backend

Backend service daemon built with Go and SQLite for Switchboard.

## Responsibilities

- **Daemon Server**: Listens for mobile client connections.
- **Pairing & Cryptography**: End-to-end encryption key exchange and QR code identifier generation.
- **System Control**: Adjusts brightness, audio volume, media controls, and DDC/CI monitor parameters.
- **File Transfer**: Manages encrypted file uploads/downloads between client and host.
- **Storage**: Local SQLite database for paired device persistence and configuration.

## Project Structure

```
backend/
├── cmd/
│   └── server/
│       └── main.go       # Entry point
├── internal/
│   ├── config/           # Configuration management
│   ├── crypto/           # E2E encryption and device pairing
│   ├── db/               # SQLite storage layer
│   ├── server/           # HTTP / WebSocket server
│   └── system/           # OS-level control & DDC/CI monitor integrations
├── go.mod
└── README.md
```
