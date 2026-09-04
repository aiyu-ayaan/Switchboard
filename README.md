# Switchboard

Switchboard is an application that allows you to control your computer system with your mobile device. It provides system controls (brightness, volume, monitor settings via DDC/CI), media playback control, and secure file transfer between your mobile device and computer with end-to-end encryption.

## Repository Architecture

The project is structured as a monorepo:

```
├── backend/          # Go backend daemon & SQLite service
├── frontend/         # Desktop application (Electron + React + Tailwind CSS)
├── mobile/           # Native Android client (Kotlin + Jetpack Compose)
├── development/      # Development docs & submodule documentation
│   └── devdocs/
└── docs/             # Project documentation
    └── docs/
```

## Technologies Used

- **Desktop Frontend**: Electron, React, Tailwind CSS
- **Mobile Frontend**: Native Android (Kotlin, Jetpack Compose)
- **Backend Service**: Go
- **Storage**: SQLite
- **Package Manager**: pnpm (workspaces)

## Getting Started

### Desktop Frontend
```bash
pnpm install
pnpm dev:frontend
```

### Backend Service
```bash
cd backend
go run cmd/server/main.go
```

### Mobile App
Open `mobile/` in Android Studio to build and run the native Android app.
