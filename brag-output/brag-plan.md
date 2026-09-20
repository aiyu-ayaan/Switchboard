# Brag Plan: Switchboard

## What is this app?
Switchboard turns your Android phone into a control deck for your PC: real monitor brightness (DDC/CI), per-app volume, media, encrypted file transfer, air mouse, phone-as-webcam, and a Stream Deck-style launcher, all over local Wi-Fi with zero accounts.

## The angle
A feature tour, not a joke. Every scene answers one everyday PC annoyance with one real screen from the app. The claim that earns the video: "Real monitor brightness. Not a screen tint." Consistent structure (problem headline, real desktop + phone UI, one proof chip) so 10 features stay digestible in under a minute.

## Hook (first 3.5s)
"Your PC." then "In your pocket." with the real phone dashboard rising into frame.

## Key moments
- Displays: DDC/CI brightness and contrast per monitor (27I200Q, EK240Y P6, built-in).
- Audio: per-app mixer (Chrome down, game up) and one-tap output switching (Headphones / Speakers / HDMI).
- Media: track, artist, album art, play/pause/skip.
- Files: drop on desktop, encrypted transfer, history.
- Air mouse: phone as trackpad (tap, scroll, pinch, 3-finger gestures).
- Stream Deck-style deck: 8 squircle keys, synced with desktop.
- Phone as webcam: capture size, frame rate, zoom, mirror.
- Pairing and security: QR scan, X25519 + AES-256-GCM, no accounts, multi-PC, forget anytime.

## Outro / punchline
Platform card (Windows, Linux, Android 8+), then the icon, "Your PC, in your pocket.", "Free and open source (MIT)", and the GitHub address held for 2+ seconds.

## User flow worth showing
Entry: desktop shows QR. Key action: phone scans, pairs, dashboard appears. Result: sliders, mixer and files move real hardware. Covered by the pairing scene plus the feature scenes using real desktop and phone screenshots.

## Tone
- Preset: app-store
- Creative direction: polished pro product tour, calm confident, Tokyo Night palette
- Interpretation: smooth feature-card pacing, consistent layout rhythm, restrained motion, no jokes.

## Format: vertical, 1080x1920
## Duration: ~48s (longer than the 15-25s default because the user asked for every feature; 12 beat-locked scenes of 3.3-4.9s each)

## Visual identity (from the project)
- Background: #1a1b26 (canvas), panels #21222d, edge #2f3140, rail #121219
- Text: #c8d1f0 (ink), #8b93b8 (ink-dim)
- Accent: #7aa2f7 (blue), #9ece6a (level green), warn #e0af68, danger #f7768e
- Fonts: Inter (display/body), JetBrains Mono (kickers/chips), as in frontend/tailwind.config.cjs
- Assets: docs/images/screenshot_{android,desktop}_*.png, icon.svg

## Storyboard (beat-snapped to the 110 BPM bed)
| # | Time | Scene | On-screen text |
|---|------|-------|----------------|
| 1 | 0.00-3.55 | Hook | "Your PC." / "In your pocket." |
| 2 | 3.55-7.35 | Reveal | icon + "Switchboard" / "Displays. Audio. Files. From your phone." |
| 3 | 7.35-12.02 | Displays | "Real monitor brightness." / "Not a screen tint. DDC/CI on every panel." |
| 4 | 12.02-16.93 | Audio | "Per-app volume." / "Chrome down, game up. Switch output in one tap." |
| 5 | 16.93-20.19 | Media | "Play. Pause. Skip." / "Track, artist and album art, live." |
| 6 | 20.19-24.56 | Files | "Send files. Encrypted." / "Drop on desktop or phone. SHA-256 verified." |
| 7 | 24.56-27.83 | Air mouse | "Your phone is the trackpad." |
| 8 | 27.83-31.10 | Deck | "A control deck in your pocket." |
| 9 | 31.10-34.38 | Webcam | "Phone as webcam." |
| 10 | 34.38-39.29 | Pairing | "Scan once. Paired." / "No accounts. X25519 + AES-256-GCM." |
| 11 | 39.29-43.65 | Platforms | "Wherever you work." Windows / Linux / Android |
| 12 | 43.65-48.01 | Outro | icon, "Your PC, in your pocket.", "Free. Open source. MIT.", github URL |

## Audio
- Music: happy-beats-business-moves-vol-10 (109.96 BPM, beat grid regular from 8.73s)
- Music treatment: low bed (~0.35), fade in 0.4s, fade out over final 2.5s
- Music cue guidance: bundled preset `vol-10.music-cues.json`; scene cuts snapped to beats (3.55, 7.35, 12.02, 16.93, 20.19, 24.56, 27.83, 31.10, 34.38, 39.29, 43.65, 48.01)
- Audio-reactive: subtle. Bass/RMS drive background glow warmth and the phone's soft halo. No bars, no waveforms.
- SFX: soft impact on scene reveals, click on chips. Low high-frequency-risk files only.
