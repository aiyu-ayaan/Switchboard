# WebSocket Wire Protocol Specification

Switchboard uses a framed JSON wire protocol transmitted over an encrypted WebSocket connection.

## 1. Frame Envelope Structure

Every message transmitted over the encrypted stream conforms to the standard envelope:

```json
{
  "id": "uuid-v4-string",
  "type": "command | event | response | error",
  "action": "system.volume.set | system.display.brightness | ...",
  "payload": {},
  "timestamp": 1725450000000
}
```

## 2. Common Actions

### System Audio
- **`system.volume.get`**: Query master audio level.
- **`system.volume.set`**:
  ```json
  {
    "level": 75,
    "muted": false
  }
  ```

### Display & DDC/CI Controls
- **`display.list`**: Returns all detected physical monitors and capabilities.
- **`display.brightness.set`**:
  ```json
  {
    "displayId": "MONITOR_01",
    "brightness": 80
  }
  ```

### Media Control
- **`media.playback.command`**:
  ```json
  {
    "action": "play | pause | next | prev | toggle"
  }
  ```

### File Transfer
- **`file.transfer.init`**:
  ```json
  {
    "fileName": "example.pdf",
    "fileSizeBytes": 1048576,
    "sha256": "abcdef123456..."
  }
  ```
- **`file.transfer.chunk`**: Encrypted binary chunk streaming.
- **`file.transfer.complete`**: Verification and checksum confirmation.
