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

| Action                    | Payload                    | Response       |
| ------------------------- | -------------------------- | -------------- |
| `media.playback.command`  | `{ "action": "toggle" }`   | `{ "action" }` |
| `media.artwork`           | -                          | `MediaArtwork` |

Accepted actions: `play`, `pause`, `toggle`, `next`, `prev`, `stop`.

Commands are sent to the host's OS media session — on Windows, the System Media Transport Controls session that Spotify, browsers and native players publish to. Driving that session keeps `play` and `pause` distinct and lets the host *read* what is playing. Players that claim a global hotkey but publish no session fall back to synthesised multimedia keys, where `play` and `pause` both land on the toggle key.

The session is also what `host.state` reports as `media`:

```json
{
  "active": true,
  "status": "playing",
  "title": "Sakhiyaan",
  "artist": "Maninder Buttar",
  "album": "Sakhiyaan",
  "source": "Spotify",
  "artworkId": "5f2c91a0d3be47aa"
}
```

`status` is one of `playing`, `paused` or `stopped`. `active` is false when nothing holds the session, in which case every other field is empty — an idle host, not an error.

#### Artwork

`artworkId` names the current track's cover art without carrying it. Cover art is an order of magnitude larger than the rest of the snapshot and changes only when the track does, so it is pulled once with `media.artwork` and cached against this ID rather than pushed with every broadcast:

```json
{ "artworkId": "5f2c91a0d3be47aa", "mimeType": "image/png", "data": "<base64>" }
```

`artworkId` is empty when the track has no artwork. A client should request artwork only when the ID in a snapshot differs from the one it holds, and should discard a reply whose ID no longer matches the current track — a late reply would otherwise be shown against the wrong song.

The daemon polls the session once a second and broadcasts `host.state` when it changes, so a track paused at the desktop reaches every connected phone.

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
  "media": { "active": true, "status": "playing", "title": "…", "artist": "…",
             "album": "…", "source": "Spotify", "artworkId": "5f2c91a0d3be47aa" },
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

## 6. File Transfer

Files move over the same encrypted envelope channel as every other command. There is no second socket and no second key exchange: a transfer is a sequence of ordinary envelopes, so it inherits the session's confidentiality, authentication and replay protection unchanged.

Both directions use the same frames. Whichever side holds the file sends the offer; the other side receives. `direction` is named from the mobile client's point of view — `upload` is phone → desktop, `download` is desktop → phone — so a stored history row reads the same on both ends.

### Frames

| Action          | Direction         | Payload        |
| --------------- | ----------------- | -------------- |
| `file.offer`    | sender → receiver | `FileOffer`    |
| `file.accept`   | receiver → sender | `FileAccept`   |
| `file.chunk`    | sender → receiver | `FileChunk`    |
| `file.ack`      | receiver → sender | `FileAck`      |
| `file.complete` | receiver → sender | `FileComplete` |
| `file.control`  | either            | `FileControl`  |
| `file.progress` | event             | `FileProgress` |
| `file.list`     | client → host     | `FileHistory`  |

### Sequence

```
sender                                receiver
  │  file.offer   {id, name, size, sha256}   │
  │ ───────────────────────────────────────► │  size the destination,
  │                                          │  look for a resumable .part
  │  file.accept  {id, offset, accepted}     │
  │ ◄─────────────────────────────────────── │
  │  file.chunk   {id, offset, data}         │
  │ ───────────────────────────────────────► │  WriteAt(offset), hash
  │                    ⋮                     │
  │  file.ack     {id, received}             │
  │ ◄─────────────────────────────────────── │  paces the sender
  │                    ⋮                     │
  │  file.chunk   {id, offset, data, last}   │
  │ ───────────────────────────────────────► │  verify digest, publish
  │  file.complete{id, ok, sha256}           │
  │ ◄─────────────────────────────────────── │
```

### `file.offer`

```json
{
  "transferId": "uuid",
  "name": "quarterly-report.pdf",
  "size": 8123456,
  "mimeType": "application/pdf",
  "sha256": "9f86d081…",
  "direction": "upload"
}
```

`name` arrives from an untrusted peer. The receiver takes the base name only and rejects path separators, `..` and absolute paths — an offer is a filename, never a location. A name that collides with an existing file is de-duplicated (`report.pdf` → `report (1).pdf`) rather than overwriting.

### `file.accept`

```json
{ "transferId": "uuid", "offset": 2097152, "accepted": true }
```

`offset` is how many bytes the receiver already holds. A fresh transfer sends `0`; one resuming after a disconnect sends the length of the partial file it kept, and the sender seeks there instead of restarting. A refusal sets `accepted: false` and carries a `reason` — no disk space, a declined permission, a rejected name.

### `file.chunk`

```json
{ "transferId": "uuid", "offset": 2097152, "data": "<base64>", "last": false }
```

Chunks are **256 KiB** of file bytes, base64 to roughly 341 KB on the wire. The offset is authoritative: the receiver writes *at* it rather than appending, so a duplicated or reordered frame cannot corrupt the output. Both ends stream against storage a chunk at a time, so a multi-GB file never sits in memory on either side.

### `file.ack`

```json
{ "transferId": "uuid", "received": 4194304 }
```

Acks exist for flow control, not reliability — the transport already guarantees delivery. Without them a fast desktop disk outruns a slow phone and the excess piles up in socket buffers on both ends. The sender stays within a small window of the last ack, and the receiver acks on a window rather than per chunk so acknowledgement traffic stays negligible.

### `file.complete`

```json
{ "transferId": "uuid", "ok": true, "sha256": "9f86d081…" }
```

The receiver hashes as bytes land and compares against the offer before publishing the file. A mismatch means the bytes are bad: the partial file is discarded, `ok` is false, and `error` says why. Only a verified file is renamed out of its `.part` staging name into place, so a failed or interrupted transfer never leaves something that looks complete.

### `file.control`

```json
{ "transferId": "uuid", "action": "pause" }
```

`pause`, `resume` or `cancel`, honoured from either side. Pause keeps the `.part` file so the transfer can resume from its offset; cancel deletes it.

### `file.progress`

```json
{
  "transferId": "uuid",
  "name": "quarterly-report.pdf",
  "direction": "upload",
  "status": "active",
  "transferred": 4194304,
  "size": 8123456,
  "bytesPerSec": 5242880,
  "startedAt": 1725450000000
}
```

`status` is one of `pending`, `active`, `paused`, `completed`, `failed` or `cancelled`. `bytesPerSec` is smoothed and always in **bytes** per second — each client formats it as MB/s or Mb/s according to the user's own preference rather than the daemon choosing a unit. Progress is emitted on a timer, not per chunk: a 256 KiB chunk at LAN speed would otherwise raise hundreds of events a second to move one progress bar.

