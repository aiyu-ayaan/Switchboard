# Switchboard Desktop Frontend

Electron + React 18 + Tailwind CSS, bundled by Vite. A dark IDE shell: activity rail, source sidebar, content pane, status bar.

## Structure

```
frontend/
├── src/
│   ├── main/          # Electron main: window, daemon lifetime, IPC route allow-list
│   ├── preload/       # contextBridge -> window.switchboard
│   ├── renderer/      # React UI
│   │   ├── components/  Shell, LevelSlider, Displays / Audio / Devices views
│   │   └── useHostState.ts
│   └── shared/        # Wire types mirroring backend/internal/protocol
├── tailwind.config.cjs
└── vite.config.ts
```

## Security model

The renderer runs with context isolation, no Node access, and a strict `default-src 'self'` policy, so it makes **no network requests at all**. Every daemon call is an IPC invoke handled by the main process against a fixed list of allowed routes. The daemon's own API is loopback-only.

## Getting Started

From the workspace root:

```bash
pnpm dev            # daemon + desktop together
pnpm dev:frontend   # this package only
```

Inside `frontend/`:

```bash
pnpm dev
pnpm build
pnpm typecheck
```

`pnpm dev` runs Vite on port 5273 and launches Electron once it is up. The main process starts a daemon only if nothing already answers on the local API, so running the daemon separately does not produce a second one.

Set `SWITCHBOARD_PORT` to match a daemon on a non-default port.
