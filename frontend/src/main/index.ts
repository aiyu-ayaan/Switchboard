// Electron main process.
//
// Owns the frameless window and the lifetime of the Go daemon. The renderer
// never talks to the daemon directly: it goes through the preload bridge,
// which reaches the loopback-only local API.
import { app, BrowserWindow, ipcMain, shell } from 'electron';
import { spawn, ChildProcess } from 'node:child_process';
import { existsSync } from 'node:fs';
import { join, resolve } from 'node:path';

const DAEMON_PORT = Number(process.env.SWITCHBOARD_PORT ?? 9427);
const DEV_SERVER = 'http://127.0.0.1:5273';
const isDev = process.env.SWITCHBOARD_DEV === '1';

let mainWindow: BrowserWindow | null = null;
let daemon: ChildProcess | null = null;

/** Resolves the compiled daemon binary shipped alongside the app. */
function daemonPath(): string | null {
  const name = process.platform === 'win32' ? 'switchboard.exe' : 'switchboard';
  const candidates = isDev
    ? [resolve(app.getAppPath(), '..', 'bin', name)]
    : [join(process.resourcesPath, 'bin', name), join(app.getAppPath(), 'bin', name)];
  return candidates.find(existsSync) ?? null;
}

/** True when a daemon is already answering on the local API. */
async function daemonRunning(): Promise<boolean> {
  try {
    const res = await fetch(`http://127.0.0.1:${DAEMON_PORT}/local/state`, {
      signal: AbortSignal.timeout(1000)
    });
    return res.ok;
  } catch {
    return false;
  }
}

/**
 * Starts the daemon unless one is already up. During `pnpm dev` the runner
 * spins the daemon with `go run`, so this is a no-op there; in a packaged app
 * it is what actually launches the backend.
 */
async function startDaemon(): Promise<void> {
  if (await daemonRunning()) {
    console.log('[switchboard] using the daemon already listening on', DAEMON_PORT);
    return;
  }
  const bin = daemonPath();
  if (!bin) {
    console.warn('[switchboard] daemon binary not found; run `pnpm build:backend`');
    return;
  }
  daemon = spawn(bin, ['--port', String(DAEMON_PORT)], { stdio: 'inherit' });
  daemon.on('exit', (code) => {
    console.warn('[switchboard] daemon exited with', code);
    daemon = null;
  });
}

function stopDaemon(): void {
  daemon?.kill();
  daemon = null;
}

function createWindow(): void {
  mainWindow = new BrowserWindow({
    width: 1120,
    height: 720,
    minWidth: 840,
    minHeight: 520,
    show: false,
    frame: false,
    backgroundColor: '#1a1b26',
    webPreferences: {
      preload: join(__dirname, '..', 'preload', 'index.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false
    }
  });

  mainWindow.once('ready-to-show', () => mainWindow?.show());

  // Keep navigation inside the app; anything external opens in the browser.
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url);
    return { action: 'deny' };
  });

  if (isDev) {
    mainWindow.loadURL(DEV_SERVER);
  } else {
    mainWindow.loadFile(join(__dirname, '..', 'renderer', 'index.html'));
  }

  mainWindow.on('closed', () => {
    mainWindow = null;
  });
}

/** Window chrome is drawn by the renderer, so the buttons route back here. */
function registerWindowControls(): void {
  ipcMain.handle('window:minimize', () => mainWindow?.minimize());
  ipcMain.handle('window:toggleMaximize', () => {
    if (!mainWindow) return false;
    if (mainWindow.isMaximized()) mainWindow.unmaximize();
    else mainWindow.maximize();
    return mainWindow.isMaximized();
  });
  ipcMain.handle('window:close', () => mainWindow?.close());
}

/** Endpoints the renderer may reach, so a compromised page cannot pick its own. */
const ALLOWED_ROUTES = new Set([
  '/state',
  '/displays/refresh',
  '/display/brightness',
  '/display/contrast',
  '/volume',
  '/media',
  '/pairing/rotate',
  '/devices/revoke'
]);

/**
 * Proxies the renderer's daemon calls.
 *
 * The renderer keeps a strict `default-src 'self'` policy and makes no network
 * requests of its own; this runs in the main process, outside Chromium's
 * network stack, and only for the fixed route list above.
 */
function registerDaemonBridge(): void {
  ipcMain.handle('daemon:request', async (_event, route: string, body?: unknown) => {
    if (!ALLOWED_ROUTES.has(route)) {
      throw new Error(`route not allowed: ${route}`);
    }
    const res = await fetch(`http://127.0.0.1:${DAEMON_PORT}/local${route}`, {
      method: body === undefined ? 'GET' : 'POST',
      headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(10_000)
    });
    if (!res.ok) {
      const detail = (await res.json().catch(() => null)) as { error?: string } | null;
      throw new Error(detail?.error ?? `request failed: ${res.status} ${res.statusText}`);
    }
    return res.json();
  });
}

app.whenReady().then(async () => {
  registerWindowControls();
  registerDaemonBridge();
  await startDaemon();
  createWindow();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});

app.on('before-quit', stopDaemon);
