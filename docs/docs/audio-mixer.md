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

## 🔀 3. Output Device Routing

Switchboard can move the host's default playback endpoint, so the phone can send
sound to speakers, a headset or an HDMI sink without anyone touching the desktop.

### Enumeration
1. **Endpoint Walk**: `IMMDeviceEnumerator::EnumAudioEndpoints(eRender, DEVICE_STATE_ACTIVE, &pCollection)` lists every usable output.
2. **Identity**: `IMMDevice::GetId()` yields the endpoint identifier, which survives reboots and re-plugs; selection is always sent by this id rather than by list position.
3. **Label**: `IPropertyStore::GetValue(PKEY_Device_FriendlyName)` gives the `"Speakers (Realtek(R) Audio)"` form the Windows volume flyout shows — description plus adapter, which is what distinguishes two otherwise identical sinks. An endpoint with no readable name is dropped rather than listed blank.
4. **Current Device**: `GetDefaultAudioEndpoint(eRender, eConsole)` marks exactly one entry as `default`.

Results are cached for five seconds. Endpoints change only when hardware is
plugged or unplugged, while the host snapshot is rebuilt at least once a second,
so an uncached walk would open a property store per device per second.

### Selection: `IPolicyConfig`

Windows exposes **no public API** for changing the default audio endpoint. The
shell's own Sound page drives the undocumented `IPolicyConfig`
(`CLSID_CPolicyConfigClient` `{870AF99C-…}`, `IID_IPolicyConfig`
`{F8679F50-…}`), and every tool that moves the default — nircmd, EarTrumpet,
SoundSwitch — calls the same interface. Switchboard invokes
`SetDefaultEndpoint` at vtable slot 13, pinned in code because the interface is
undocumented and cannot be discovered at runtime.

All three roles move together:

| Role | Covers |
| :--- | :--- |
| `eConsole` | General playback |
| `eMultimedia` | Music and video |
| `eCommunications` | Voice and video chat |

A user who picks "Headphones" means their sound, not "their sound except in
calls" — leaving communications behind is exactly the split that makes the
Windows Sound page confusing.

After a successful write both the endpoint cache and the session cache are
invalidated: the mixer list belonged to the old device, and the endpoint list
still marked the old default.

**Capability**: `outputs`. Clients hide the picker entirely on a host that does
not report it, so non-Windows daemons degrade rather than showing a dead control.

---

## 🎵 4. System Media Transport Controls (SMTC)

Switchboard captures media playback status and track details through Windows Runtime (WinRT) `GlobalSystemMediaTransportControlsSessionManager`:

### Media Metadata & Controls
- **Session Focus**: Hooks into the currently active media session across Windows (Spotify, YouTube in Chrome/Edge, VLC, Apple Music).
- **Track Details**: Extracts `Title`, `Artist`, `AlbumTitle`, and the source application name.
- **Playback State**: Monitors real-time state (`Playing`, `Paused`, `Stopped`).
- **Transport Dispatches**: Routes `TryPlayAsync()`, `TryPauseAsync()`, `TrySkipNextAsync()`, `TrySkipPreviousAsync()`, and `TryStopAsync()` directly to the active media player.

### Album Artwork Extraction
- Artwork is retrieved via `IRandomAccessStreamReference` from the active media session.
- The stream is opened in memory, converted into standard JPEG/PNG bytes, cached with a content-addressed SHA-256 hash (`artworkId`), and served efficiently via `GET /local/media/artwork?id={artworkId}`.
