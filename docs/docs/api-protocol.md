# Switchboard API & Wire Protocol Reference

This document provides the complete technical specification for Switchboard''s network wire protocol, JSON-RPC envelopes, WebSocket events, and the local loopback HTTP interface.

---

## 🌐 Protocol Overview

Switchboard utilizes two network interfaces:
1. **Encrypted WebSocket (`/ws`)**: Bidirectional full-duplex connection between Android client and Go host daemon over the local network (port `9427`).
2. **Loopback REST API (`/local/*`)**: HTTP/JSON interface bound strictly to `127.0.0.1`, used exclusively by the Electron desktop frontend.

---

## 📡 1. WebSocket Protocol (`/ws`)

### Frame Format

Every sealed frame begins with a **kind byte**:

| Kind | Layout | Used by |
| :--- | :--- | :--- |
| `0x00` | `0x00` + envelope JSON | every control message |
| `0x01` | `0x01` + `uint32` big-endian metadata length + envelope JSON + raw bytes | file chunks, camera frames |

Bulk payloads ride *beside* the envelope rather than base64 inside it. The
frame is a binary WebSocket message carrying an AEAD ciphertext either way, so
encoding the bytes would inflate them by a third and cost an encode on one CPU
and a decode on the other for no benefit. A metadata length that overruns the
frame is rejected rather than sliced — it is the one input that turns a
length field into a crash.

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

#### 8. `input.move` / `input.button` / `input.scroll` / `input.gesture`
The air mouse. Every gesture is recognised on the phone; the desktop receives
resolved intents and never learns a finger was involved. All four are
fire-and-forget — the pointer moving on screen is the acknowledgement — and
failures still return an error frame. Guarded by the `input` capability. See
[Air Mouse](./air-mouse.md) for the full gesture vocabulary.

```json
{
  "type": "input.move",
  "id": "req-007",
  "payload": { "dx": 4.25, "dy": -1.5 }
}
```

Motion is fractional and the host accumulates the remainder: at 120 Hz a
careful drag moves under a pixel per frame, and truncating each frame would
stop the cursor creeping at all.

```json
{ "type": "input.button", "payload": { "button": "left", "action": "double" } }
{ "type": "input.scroll", "payload": { "dx": 0, "dy": -2, "ctrl": true } }
{ "type": "input.gesture", "payload": { "name": "taskView" } }
```

*Buttons*: `left`, `right`, `middle`. *Button actions*: `down`, `up`, `click`, `double`.
*Scroll* is in wheel notches; positive `dy` scrolls up, and `ctrl` makes it zoom.
*Gesture names*: `taskView`, `showDesktop`, `desktopLeft`, `desktopRight`, `back`, `forward` —
and that table is the entire keyboard surface the air mouse exposes, so a
malformed frame cannot turn the pointer channel into a general keyboard.

#### 9. `system.lock`
Locks the host workstation console session (equivalent to `user32!LockWorkStation` or Win+L). Guarded by the `lock` capability.

```json
{
  "type": "command",
  "action": "system.lock",
  "id": "req-008"
}
```

#### 10. `file_transfer_init`
Initiates a peer-to-peer file transfer.

```json
{
  "type": "file_transfer_init",
  "id": "req-009",
  "payload": {
    "name": "photo.jpg",
    "size": 4194304,
    "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
    "direction": "upload"
  }
}
```

#### 11. `deck.get`
Requests the current Stream Deck Neo configuration including pages, keys, and infobar settings. Guarded by the `deck` capability.

```json
{
  "type": "deck.get",
  "id": "req-010"
}
```

#### 12. `deck.set`
Saves and atomically broadcasts an updated Stream Deck Neo configuration across all connected clients.

```json
{
  "type": "deck.set",
  "id": "req-011",
  "payload": {
    "activePage": 0,
    "pages": [
      {
        "id": "page-1",
        "name": "Productivity",
        "keys": [
          {
            "index": 0,
            "title": "Browser",
            "icon": "google",
            "backgroundColor": "#1a1a2e",
            "textColor": "#ffffff",
            "action": {
              "type": "url",
              "value": "https://google.com"
            }
          }
        ]
      }
    ],
    "infobar": {
      "showClock": true,
      "showDate": true,
      "textColor": "#000000",
      "fontSize": 14
    }
  }
}
```

#### 13. `deck.action`
Triggers immediate execution of a Stream Deck Neo key action on the host machine.

```json
{
  "type": "deck.action",
  "id": "req-012",
  "payload": {
    "keyIndex": 0,
    "action": {
      "type": "hotkey",
      "value": "ctrl+c"
    },
    "pageId": "page-1"
  }
}
```

#### 14. `system.apps`
Requests the list of applications installed on the host system (scanned from Windows Start Menu shortcuts and standard system tools).

```json
{
  "type": "system.apps",
  "id": "req-013"
}
```

#### 15. `system.power`
Triggers workstation power and session operations: display power down, ACPI sleep, timed shutdown, or countdown abort. Guarded by the `power` capability.

```json
{
  "type": "system.power",
  "id": "req-014",
  "payload": {
    "action": "shutdown",
    "seconds": 1800
  }
}
```
*Supported actions*: `"display_off"`, `"sleep"`, `"shutdown"`, `"abort_shutdown"`. `seconds` is optional and specifies the countdown before shutdown.

#### 16. `audio.mic.set`
Adjusts the master recording microphone volume and mute state. Guarded by the `mic` capability.

```json
{
  "type": "audio.mic.set",
  "id": "req-015",
  "payload": {
    "level": 85,
    "muted": false
  }
}
```

#### 17. `audio.input.set`
Selects the default system audio capture endpoint (recording device). Guarded by the `inputs` capability.

```json
{
  "type": "audio.input.set",
  "id": "req-016",
  "payload": {
    "deviceId": "{0.0.1.00000000}.{e14b4334-a145-4df3-8c46-992383bbccb2}"
  }
}
```

#### 18. `input.text`
Injects unicode text keystrokes into the active focused window. Guarded by the `input` capability.

```json
{
  "type": "input.text",
  "id": "req-017",
  "payload": {
    "text": "Hello from Switchboard! 🚀"
  }
}
```

#### 19. `clipboard.set`
Directly writes text content to the host workstation system clipboard. Guarded by the `clipboard` capability.

```json
{
  "type": "clipboard.set",
  "id": "req-018",
  "payload": {
    "text": "Copied text content from mobile"
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
    "mic": {
      "level": 85,
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
    "inputs": [
      {
        "id": "{0.0.1.00000000}.{e14b4334-a145-4df3-8c46-992383bbccb2}",
        "name": "Microphone (Realtek(R) Audio)",
        "default": true
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

#### 4. `deck.state`
Emitted asynchronously when Stream Deck Neo configuration is saved or modified from any client.

```json
{
  "type": "deck.state",
  "payload": {
    "activePage": 0,
    "pages": [ ... ],
    "infobar": {
      "showClock": true,
      "showDate": true,
      "textColor": "#000000",
      "fontSize": 14
    }
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
| `POST` | `/local/settings` | Update host preferences. | `{"downloadDir": "...", "runInBackground": true, "autoStart": true}` |
| `POST` | `/local/system/lock` | Lock the host workstation session. | `{}` |
| `GET` | `/local/deck` | Get current Stream Deck Neo configuration. | None |
| `POST` | `/local/deck` | Save and broadcast updated Stream Deck Neo configuration. | `DeckConfig` JSON object |
| `POST` | `/local/deck/action` | Execute Stream Deck Neo key action immediately. | `DeckActionRequest` JSON object |
| `GET` | `/local/system/apps` | Enumerate applications installed on the system. | None |
| `POST` | `/local/system/power` | Trigger power actions (display_off, sleep, shutdown, abort_shutdown). | `{"action": "shutdown", "seconds": 1800}` |
| `POST` | `/local/audio/mic` | Set system microphone volume and mute state. | `{"level": 80, "muted": false}` |
| `POST` | `/local/audio/input` | Set default audio recording device endpoint. | `{"deviceId": "..."}` |
| `POST` | `/local/input/text` | Inject unicode text strings into focused application. | `{"text": "Hello"}` |
| `POST` | `/local/clipboard` | Set host system clipboard text content. | `{"text": "Copied content"}` |
