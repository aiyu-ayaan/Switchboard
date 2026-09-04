# Switchboard Desktop Frontend

Desktop UI client built using **Electron**, **React**, and **Tailwind CSS**.

## Structure

```
frontend/
├── src/
│   ├── main/          # Electron main process
│   ├── preload/       # Electron context bridge / preload script
│   └── renderer/      # React user interface with Tailwind CSS
├── package.json
└── README.md
```

## Getting Started

Run the dev server from workspace root:
```bash
pnpm dev:frontend
```
or inside `frontend/`:
```bash
pnpm dev
```
