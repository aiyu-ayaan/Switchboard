# Switchboard

Control your computer from your phone: brightness, volume, media playback and file transfer, over an end-to-end encrypted connection.

## Layout

```
backend/   Rust daemon (desktop host)
core/      Rust crate shared by the backend and the Android app (JNI)
frontend/  Electron + React + Tailwind desktop UI
mobile/    Android app (Kotlin + Jetpack Compose, Rust core)
development/  Docs submodule (roadmap, commit rules)
```

## Setup

```
pnpm install
cargo build --workspace
```
