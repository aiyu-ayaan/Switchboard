# Windows WASAPI Audio Mixer & Media Transport

This document details how Switchboard interacts with Windows audio sessions using the **Windows Audio Session API (WASAPI)** and extracts media playback state via **System Media Transport Controls (SMTC)**.

---

## 🔊 Architecture Overview

Switchboard interfaces with Windows audio subsystems via native COM interfaces:

```
                  ┌─────────────────────────────────────┐
                  │          Go Host Daemon             │
                  └──────────────────┬──────────────────┘
                                     │
           ┌─────────────────────────┴─────────────────────────┐
           ▼                                                   ▼
┌───────────────────────────┐                     ┌───────────────────────────┐
│     Windows Core Audio    │                     │   Windows Runtime (WinRT) │
│          (WASAPI)         │                     │           (SMTC)          │
├───────────────────────────┤                     ├───────────────────────────┤
│ IMMDeviceEnumerator       │                     │ GSMTCSessionManager       │
│ IMMDevice (Default Audio) │                     │ GSMTCSession (Current App)│
│ IAudioEndpointVolume      │                     │ MediaProperties (Metadata)│
│ IAudioSessionManager2     │                     │ MediaTimelineProperties   │
│ IAudioSessionControl2     │                     │ IRandomAccessStreamReference│
│ ISimpleAudioVolume        │                     │ (Album Artwork)           │
└───────────────────────────┘                     └───────────────────────────┘
```

---

## 🎛️ 1. System Master Volume

Master volume controls the hardware audio DAC / endpoint directly:
1. **Device Enumeration**: `IMMDeviceEnumerator::GetDefaultAudioEndpoint(eRender, eMultimedia, &pDevice)` retrieves the primary speaker or headphone output.
2. **Endpoint Interface**: Activates `IAudioEndpointVolume`.
3. **Volume Level**:
   - `GetMasterVolumeLevelScalar(&level)`: Reads current volume as a normalized float between `0.0` and `1.0`.
   - `SetMasterVolumeLevelScalar(level, NULL)`: Sets volume level without introducing perceptual distortion.
4. **Mute State**: `GetMute(&muted)` and `SetMute(muted, NULL)` manage hardware mute status instantly.

---

## 🎚️ 2. Per-Application Audio Session Mixer

Unlike simple remote volume apps that only adjust master system volume, Switchboard exposes independent volume sliders for every program producing sound.

### Session Enumeration Flow
1. **Session Manager**: Activates `IAudioSessionManager2` from the default audio endpoint.
2. **Session Enumeration**: Calls `GetSessionEnumerator(&pSessionEnum)` to obtain all active sound streams.
3. **Session Inspection**: For each session:
   - Queries `IAudioSessionControl2::GetProcessId(&pid)` to discover the owning process.
   - Resolves the process executable name (e.g., `chrome.exe`, `spotify.exe`, `discord.exe`, `game.exe`).
   - Filters out expired, terminated, or dormant sessions.
4. **Volume Adjustment**: Obtains `ISimpleAudioVolume` to read and set volume levels and mute states per application independently of master volume.

---

## 🎵 3. System Media Transport Controls (SMTC)

Switchboard captures media playback status and track details through Windows Runtime (WinRT) `GlobalSystemMediaTransportControlsSessionManager`:

### Media Metadata & Controls
- **Session Focus**: Hooks into the currently active media session across Windows (Spotify, YouTube in Chrome/Edge, VLC, Apple Music).
- **Track Details**: Extracts `Title`, `Artist`, `AlbumTitle`, and the source application name.
- **Playback State**: Monitors real-time state (`Playing`, `Paused`, `Stopped`).
- **Transport Dispatches**: Routes `TryPlayAsync()`, `TryPauseAsync()`, `TrySkipNextAsync()`, `TrySkipPreviousAsync()`, and `TryStopAsync()` directly to the active media player.

### Album Artwork Extraction
- Artwork is retrieved via `IRandomAccessStreamReference` from the active media session.
- The stream is opened in memory, converted into standard JPEG/PNG bytes, cached with a content-addressed SHA-256 hash (`artworkId`), and served efficiently via `GET /local/media/artwork?id={artworkId}`.
