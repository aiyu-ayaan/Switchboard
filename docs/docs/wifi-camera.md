# Wi-Fi Camera (Phone as Webcam)

Use a paired phone as a wireless webcam for the desktop. The camera streams
JPEG frames over the existing encrypted session — no second app, no pairing
dance, no cloud relay. Guarded by nothing beyond the pairing itself: the
camera is a phone resource, not a desktop capability, so every paired device
can offer it.

---

## 🧠 Where the intelligence lives

| Layer | What it does |
| ----- | ------------ |
| **Android `CameraStreamer`** | Opens CameraX, captures YUV frames, encodes to JPEG, applies rotation/mirror, rate-limits to the requested fps, sends each frame with a `CameraFrame` metadata envelope beside it as a binary blob. |
| **Android `CameraController`** | Holds intent vs. state: the desktop can request a stream before the phone has the screen open or the permission granted; the controller records that and starts capture when both are ready. |
| **Android `CameraScreen`** | Full control surface: quality preset, frame rate, facing, torch, zoom, rotation, mirror, auto-framing, focus (auto/manual), exposure (auto/manual), white balance. Every control is also reachable from the desktop; both edit the same settings block and each sees the other's changes. |
| **Go `camera.Hub`** | Holds the latest frame (not a queue), measures throughput over a sliding window, detects a stale stream (no frame for 5 s), and serves frames to consumers. One stream at a time — a second phone would flicker. |
| **Go local API** | `/local/camera/state`, `/local/camera/start`, `/local/camera/stop`, `/local/camera/control` for the desktop UI. `/local/camera/frame?after=N` long-polls for the next frame. `/local/camera/stream` serves MJPEG for OBS/VLC. |
| **Electron main process** | Pumps frames from the daemon's long-poll endpoint to the renderer over IPC (`camera:frame`). The renderer's strict `default-src 'self'` CSP prevents it from fetching the daemon directly. |
| **Desktop `CameraView`** | Live preview, device picker, start/stop, quality/fps/rotation/zoom/mirror/focus/exposure/white-balance controls, and the MJPEG URL for external apps. |

---

## Wire actions

| Action | Direction | Payload | Blob |
| ------ | --------- | ------- | ---- |
| `camera.start` | desktop → phone | `CameraSettings` | — |
| `camera.stop` | desktop → phone | — | — |
| `camera.control` | desktop → phone | `CameraSettings` | — |
| `camera.frame` | phone → desktop | `CameraFrame` (seq, width, height, ts) | JPEG bytes |
| `camera.state` | phone → desktop | `CameraState` | — |

Settings are sent **whole**, not as patches. The phone applies one camera
reconfiguration per update, and a partial payload would need every field
nullable to distinguish "unset" from "set to zero".

---

## Quality presets

| Preset | Resolution | JPEG quality |
| ------ | ---------- | ------------ |
| `full` (default) | 1920 × 1080 | 90 |
| `balanced` | 1280 × 720 | 75 |
| `low` | 640 × 480 | 55 |

The preset picks both resolution and compression together because they trade
against the same thing — bandwidth — and separating them invites combinations
that make no sense.

---

## Frame transport

Frames ride **beside** the JSON envelope as a binary blob in the same sealed
frame, the same way file chunks do since the Phase 3 binary framing work. At
30 fps this avoids the ~33% inflation that base64 would add to every frame.

The hub holds the **latest** frame, not a queue. A consumer that falls behind
gets the current frame, not one from two seconds ago. This is the right trade
for a video stream: latency matters, and nobody wants the backlog.

---

---

## Using in other apps (Direct Virtual Camera & MJPEG)

Switchboard provides a native DirectShow virtual camera device on Windows:

### 1. Direct System Virtual Camera ("Switchboard Camera")
Register the virtual camera device from the desktop app (click **"Install Virtual Camera"** in the Camera tab, which runs with a standard Windows UAC prompt) or run `driver/vcam/install-camera.bat`.

Once registered, **"Switchboard Camera"** appears directly as a hardware/virtual webcam in:
- **Google Chrome**, Microsoft Edge, Mozilla Firefox
- **Zoom**, **Microsoft Teams**, **Google Meet**, **Discord**, **Skype**
- Any Windows application that supports standard webcams

The desktop backend automatically decodes incoming camera frames and streams them directly into the Windows DirectShow virtual camera filter via high-speed named shared memory (`UnityCapture_Data0`).

### 2. MJPEG Stream (OBS & VLC Bridge)
The daemon also serves a standard **MJPEG** stream at:

```
http://127.0.0.1:9427/local/camera/stream
```

Add this as a **Media Source** in OBS or VLC if you prefer streaming via media URL.

---

## Camera controls

All controls are available from both the phone and the desktop. Changes made
on one side appear on the other because the phone reports its state back after
every apply.

### Capture
- **Quality preset** — full / balanced / low
- **Frame rate** — 5–60 fps (Camera2 `CONTROL_AE_TARGET_FPS_RANGE`)
- **Facing** — back / front (switching front flips mirror automatically)
- **Torch** — on / off (back camera only)

### Framing
- **Zoom** — 0–100%, normalised across the sensor's own range
- **Rotation** — 0° / 90° / 180° / 270°, applied at the source before encoding
- **Mirror** — horizontal flip, follows front camera by default
- **Auto framing** — keeps a detected face centred by cropping

### Image
- **Auto focus / manual focus** — manual sets dioptres via `LENS_FOCUS_DISTANCE`
- **Auto exposure / manual exposure** — manual sets EV compensation index
- Controls hidden when the hardware reports no support (`hasManualFocus`, `hasManualExposure`)

### White balance
- Auto, incandescent, fluorescent, daylight, cloudy, shade
- Maps directly to Camera2 `CONTROL_AWB_MODE_*`

---

## Lifecycle
 
- Streaming starts once granted and requested, backed by **`CameraService` (a dedicated camera foreground service)** with an ongoing notification ("Switchboard Camera Active — Tap to return" and a Stop button).
- **Lock / Turn off display mode**: Tapping "Turn off display" dims the screen to pitch black (`#000000` for OLED battery savings and burn-in prevention) and guards against accidental touches. Double-tapping anywhere restores screen brightness and unlocks the interface.
- **Background capture**: When the user switches to other apps or locks the device with the physical power button, `CameraService` holds a CPU partial wake-lock and keeps CameraX capture streaming to the desktop without interruption.
- Leaving the camera screen or locking the phone does not disconnect the stream until explicitly stopped by the user on the phone, via the notification's Stop button, or from the desktop.
- A phone that drops off Wi-Fi mid-stream sends no goodbye. The hub detects this as 5 seconds of silence and marks the stream stale.

---

## Settings that require a rebind vs. live apply

| Rebind (blanks the picture briefly) | Live (instant) |
| ----------------------------------- | -------------- |
| Facing, quality, frame rate | Zoom, torch, focus, exposure, white balance, mirror, rotation |

The distinction matters because a rebind flashes black for a frame. Slider
drags (zoom, exposure) must not rebind on every tick.

---

## Permissions

The app declares `android.permission.CAMERA`, `android.permission.FOREGROUND_SERVICE_CAMERA`, and `android.permission.WAKE_LOCK` in the manifest. The camera screen requests camera permission at runtime and handles denial gracefully.

---

## Limitations

| Limitation | Why |
| ---------- | --- |
| One stream at a time | A second phone streaming into the same sink would flicker between two rooms. |
| No audio capture | Audio from the phone's microphone is not yet carried; the desktop's own mic is usually closer to the speaker anyway. |
| JPEG only, no H.264 | Every frame stands alone, so a drop costs exactly itself rather than corrupting until the next keyframe. On a LAN, bandwidth is cheap and graceful degradation is worth more than compression. |
