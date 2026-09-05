# Switchboard API & Wire Protocol Reference

This document provides the complete technical specification for Switchboard''s network wire protocol, JSON-RPC envelopes, WebSocket events, and the local loopback HTTP interface.

---

## 🌐 Protocol Overview

Switchboard utilizes two network interfaces:
1. **Encrypted WebSocket (`/ws`)**: Bidirectional full-duplex connection between Android client and Go host daemon over the local network (port `9427`).
2. **Loopback REST API (`/local/*`)**: HTTP/JSON interface bound strictly to `127.0.0.1`, used exclusively by the Electron desktop frontend.

---

## 📡 1. WebSocket Protocol (`/ws`)

### Envelope Format

All WebSocket messages follow a standardized JSON envelope structure:

```json
{
  "type": "<message_type>",
  "id": "<optional_request_uuid>",
  "payload": { ... }
}
```

---

### Client Requests (Phone ➔ Desktop)

#### 1. `get_state`
Requests the full current state of displays, audio volume, app mixer, active media, and transfer status.

```json
{
  "type": "get_state",
  "id": "req-001"
}
```

#### 2. `set_brightness`
Sets the hardware brightness on a specific display.

```json
{
  "type": "set_brightness",
  "id": "req-002",
  "payload": {
    "displayId": "\\\\.\\DISPLAY4",
    "value": 75
  }
}
```

#### 3. `set_contrast`
Sets the hardware contrast on a DDC/CI-capable display.

```json
{
  "type": "set_contrast",
  "id": "req-003",
  "payload": {
    "displayId": "\\\\.\\DISPLAY4",
    "value": 50
  }
}
```

#### 4. `set_volume`
Sets the system master audio volume and/or mute state.

```json
{
  "type": "set_volume",
  "id": "req-004",
  "payload": {
    "level": 65,
    "muted": false
  }
}
```

#### 5. `set_mixer_volume`
Adjusts the volume level for an individual active application session.

```json
{
  "type": "set_mixer_volume",
  "id": "req-005",
  "payload": {
    "sessionId": "{0.0.0.00000000}.{...}|chrome.exe",
    "level": 80,
    "muted": false
  }
}
```

#### 6. `audio.output.set`
Moves the host's default playback endpoint. The reply is the refreshed device
list, because changing the default changes which entry is marked, not just one.

```json
{
  "type": "audio.output.set",
  "id": "req-006",
  "payload": {
    "deviceId": "{0.0.0.00000000}.{87039890-5e96-4cc8-83f8-1aeca934c199}"
  }
}
```

All three Windows endpoint roles — console, multimedia and communications —
move together, so the whole host follows the choice rather than splitting
playback from calls. Guarded by the `outputs` capability.

#### 7. `media_control`
Dispatches a playback transport action to the active media session.

```json
{
  "type": "media_control",
  "id": "req-006",
  "payload": {
    "action": "play_pause"
  }
}
```
*Supported actions*: `"play"`, `"pause"`, `"play_pause"`, `"next"`, `"previous"`, `"stop"`.

#### 8. `file_transfer_init`
Initiates a peer-to-peer file transfer.

```json
{
  "type": "file_transfer_init",
  "id": "req-007",
  "payload": {
    "name": "photo.jpg",
    "size": 4194304,
    "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
    "direction": "upload"
  }
}
```

---

### Host Broadcasts & Events (Desktop ➔ Phone)

#### 1. `host_state`
Pushed upon connection and whenever host state (displays, volume, mixer, output routing) changes.

```json
{
  "type": "host_state",
  "payload": {
    "hostName": "ROOT",
    "daemonId": "d19795f7-fa89-4779-abb1-320207c92dcf",
    "displays": [
      {
        "id": "\\\\.\\DISPLAY4",
        "name": "27I200Q",
        "internal": false,
        "brightness": 75,
        "minBrightness": 0,
        "maxBrightness": 100,
        "hasContrast": true,
        "contrast": 50,
        "minContrast": 0,
        "maxContrast": 100
      }
    ],
    "volume": {
      "level": 65,
      "muted": false
    },
    "mixer": [
      {
        "id": "{0.0.0.00000000}.{...}|chrome.exe",
        "name": "Chrome",
        "pid": 10984,
        "level": 80,
        "muted": false,
        "active": true
      }
    ],
    "outputs": [
      {
        "id": "{0.0.0.00000000}.{87039890-5e96-4cc8-83f8-1aeca934c199}",
        "name": "Speakers (AB13X USB Audio)",
        "default": true
      },
      {
        "id": "{0.0.0.00000000}.{20325cd4-f5f6-487f-8f91-147998a8b9c4}",
        "name": "Headphones (Realtek(R) Audio)",
        "default": false
      }
    ],
    "media": {
      "active": true,
      "status": "playing",
      "title": "Song Title",
      "artist": "Artist Name",
      "source": "Spotify",
      "artworkId": "9fda916f0da10db2"
    }
  }
}
```

#### 2. `media_changed`
Emitted asynchronously whenever the active song, artist, playback state, or artwork changes.

```json
{
  "type": "media_changed",
  "payload": {
    "active": true,
    "status": "paused",
    "title": "New Track",
    "artist": "New Artist",
    "source": "Chrome",
    "artworkId": "8abc1234..."
  }
}
```

#### 3. `transfer_progress`
Real-time progress update during active file transfers.

```json
{
  "type": "transfer_progress",
  "payload": {
    "transferId": "1754fb18-c748-436b-9b4e-2d583a9b607a",
    "transferred": 2097152,
    "total": 4194304,
    "bytesPerSec": 15728640,
    "status": "active"
  }
}
```

---

## 🖥️ 2. Local Loopback REST API (`/local/*`)

The Electron frontend communicates with the Go backend over `http://127.0.0.1:9427/local`. External network requests to `/local/*` are rejected with `403 Forbidden`.

| Method | Endpoint | Description | Request Payload |
| :--- | :--- | :--- | :--- |
| `GET` | `/local/state` | Returns complete host state, active pairing info, and connected devices. | None |
| `POST` | `/local/displays/refresh` | Force rescan of physical display monitors. | `{}` |
| `POST` | `/local/display/brightness` | Set monitor brightness. | `{"displayId": "...", "value": 80}` |
| `POST` | `/local/display/contrast` | Set monitor contrast. | `{"displayId": "...", "value": 50}` |
| `POST` | `/local/volume` | Set master system volume. | `{"level": 50, "muted": false}` |
| `POST` | `/local/mixer` | Set per-app audio volume. | `{"sessionId": "...", "level": 100, "muted": false}` |
| `POST` | `/local/audio/output` | Route host audio to one endpoint. | `{"deviceId": "..."}` |
| `POST` | `/local/media` | Send media transport command. | `{"action": "play_pause"}` |
| `GET` | `/local/media/artwork` | Returns raw binary image bytes for current album artwork. | Query: `?id=...` |
| `POST` | `/local/pairing/rotate` | Generates a fresh pairing code and QR payload. | `{}` |
| `POST` | `/local/devices/revoke` | Revokes access for a paired mobile device. | `{"deviceId": "..."}` |
| `POST` | `/local/files/send` | Queue desktop files to send to a connected device. | `{"deviceId": "...", "paths": ["..."]}` |
| `POST` | `/local/files/control` | Pause, resume, or cancel a transfer. | `{"transferId": "...", "action": "cancel"}` |
| `GET` | `/local/settings` | Retrieve host preferences. | None |
| `POST` | `/local/settings` | Update host preferences. | `{"downloadDir": "...", "runInBackground": true}` |
