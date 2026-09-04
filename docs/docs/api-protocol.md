# WebSocket Wire Protocol Specification

Switchboard uses a framed JSON protocol carried inside an encrypted WebSocket connection on port **9427**.

## 1. Connection Phases

A connection has exactly three plaintext frames, then switches to binary:

| # | Direction       | Type   | Message      |
| - | --------------- | ------ | ------------ |
| 1 | host → client   | text   | `hello`      |
| 2 | client → host   | text   | `auth`       |
| 3 | host → client   | text   | `authResult` |
| … | both            | binary | encrypted envelopes |

A plaintext frame received after the handshake is ignored. A binary frame received before it is dropped and the socket closed.

### `hello`

```json
{
  "type": "hello",
  "daemonId": "uuid",
  "hostName": "ROOT",
  "identityKey": "base64url",
  "ephemeralKey": "base64url",
  "challenge": "base64url"
}
```

### `auth`

```json
{
  "mode": "pair | resume",
  "identityKey": "base64url",
  "ephemeralKey": "base64url",
  "deviceName": "Google Pixel 8",
  "proof": "base64url"
}
```

`pair` requires the current pairing code to have been folded into the key schedule. `resume` requires the client's identity key to already be stored on the host.

### `authResult`

```json
{ "type": "authResult", "ok": true, "deviceId": "uuid", "hostName": "ROOT", "proof": "base64url" }
```

On failure: `{ "type": "authResult", "ok": false, "error": "authentication failed" }`.

The client must verify the host's `proof` before trusting the connection. See [security-pairing.md](../../development/devdocs/security-pairing.md).

## 2. Encrypted Frame Layout

```
┌──────────────┬──────────────────────────────────────────┐
│ nonce (12 B) │ AES-256-GCM ciphertext ‖ tag (16 B)      │
└──────────────┴──────────────────────────────────────────┘
   4 zero bytes + big-endian uint64 counter
```

Each direction uses its own key. The counter must strictly increase; a repeated or lower counter is rejected as a replay.

## 3. Envelope

The plaintext inside every frame:

```json
{
  "id": "uuid-v4",
  "type": "command | event | response | error",
  "action": "display.brightness.set",
  "payload": {},
  "timestamp": 1725450000000
}
```

A `response` reuses the `id` of the command that caused it. Clients match on that id and skip the `event` frames interleaved with it.

## 4. Actions

### Displays

| Action                   | Payload                              | Response          |
| ------------------------ | ------------------------------------ | ----------------- |
| `display.list`           | –                                    | `Display[]`       |
| `display.brightness.set` | `{ "displayId": "…", "value": 60 }`  | the updated `Display` |
| `display.contrast.set`   | `{ "displayId": "…", "value": 50 }`  | the updated `Display` |

```json
{
  "id": "\\\\.\\DISPLAY4",
  "name": "27I200Q",
  "internal": false,
  "brightness": 72,
  "minBrightness": 0,
  "maxBrightness": 100,
  "hasContrast": true,
  "contrast": 50,
  "minContrast": 0,
  "maxContrast": 100
}
```

`minBrightness` / `maxBrightness` are the panel's **real capability range** read from the hardware, not an assumed 0–100. Clients must render against this range and the host clamps every write to it. An internal panel reports `internal: true` and `hasContrast: false`, and should show a single slider.

### Audio

| Action              | Payload                              | Response |
| ------------------- | ------------------------------------ | -------- |
| `system.volume.get` | –                                    | `Volume` |
| `system.volume.set` | `{ "level": 75, "muted": false }`    | `Volume` |

`level` is 0–100.

### Media

| Action                    | Payload                    |
| ------------------------- | -------------------------- |
| `media.playback.command`  | `{ "action": "toggle" }`   |

Accepted actions: `play`, `pause`, `toggle`, `next`, `prev`, `stop`. `play` and `pause` both map to the platform toggle key, since the transport state lives in the media application rather than in Switchboard.

### Events

| Action       | Direction     | Payload     |
| ------------ | ------------- | ----------- |
| `host.state` | host → client | `HostState` |

Pushed on connect and after every state change, so multiple clients converge rather than drift.

```json
{
  "hostName": "ROOT",
  "daemonId": "uuid",
  "displays": [ … ],
  "volume": { "level": 50, "muted": false },
  "capabilities": ["display", "volume", "media"]
}
```

`capabilities` reports what this host can actually do. Clients should hide controls the host does not list rather than showing dead UI — a machine with no audio endpoint still drives its monitors.

### Errors

```json
{ "id": "<request id>", "type": "error", "action": "display.contrast.set",
  "payload": { "message": "display \"…\" has no contrast control" } }
```

A failed command never closes the connection.

## 5. Local Desktop API

The Electron UI talks to the daemon over plain HTTP on loopback. These routes return `403` for any non-loopback peer and carry no pairing credentials.

| Method | Route                       | Body                                 |
| ------ | --------------------------- | ------------------------------------ |
| GET    | `/local/state`              | –                                    |
| POST   | `/local/displays/refresh`   | `{}`                                 |
| POST   | `/local/display/brightness` | `{ "displayId": "…", "value": 60 }`  |
| POST   | `/local/display/contrast`   | `{ "displayId": "…", "value": 50 }`  |
| POST   | `/local/volume`             | `{ "level": 50, "muted": false }`    |
| POST   | `/local/media`              | `{ "action": "next" }`               |
| POST   | `/local/pairing/rotate`     | `{}`                                 |
| POST   | `/local/devices/revoke`     | `{ "deviceId": "uuid" }`             |

`GET /local/state` returns `{ host, pairing, devices }` — everything the desktop renders in one poll.

## 6. Phase 2 (not yet implemented)

- `file.transfer.init` — `{ "fileName", "fileSizeBytes", "sha256" }`
- `file.transfer.chunk` — encrypted binary chunk streaming
- `file.transfer.complete` — checksum confirmation
