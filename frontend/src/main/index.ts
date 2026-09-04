// Electron main process.
//
// Owns the frameless window and the lifetime of the Go daemon. The renderer
// never talks to the daemon directly: it goes through the preload bridge,
// which reaches the loopback-only local API.
import {
  app,
  BrowserWindow,
  dialog,
  ipcMain,
  Menu,
  nativeImage,
  Notification,
  shell,
  Tray
} from 'electron';
import { spawn, ChildProcess } from 'node:child_process';
import { existsSync } from 'node:fs';
import { join, resolve } from 'node:path';
import type { HostSettings, LocalState } from '../shared/types';

const DAEMON_PORT = Number(process.env.SWITCHBOARD_PORT ?? 9427);
const DEV_SERVER = 'http://127.0.0.1:5273';
const isDev = process.env.SWITCHBOARD_DEV === '1';

/** A 16px accent ring, inlined so the tray needs no packaged asset. */
const TRAY_ICON =
  'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAN0lEQVR4nGNgoAaoWvT9PzmY+gaQ6mLaG0C0ODaFOP1LjAGEAo32BlDsBaoEIj5AOwMGLi9QAgAh9HCA9v/DnAAAAABJRU5ErkJggg==';

let mainWindow: BrowserWindow | null = null;
let daemon: ChildProcess | null = null;
let tray: Tray | null = null;

// The daemon owns this preference, but the close handler needs it locally. It
// is refreshed from every /state poll and /settings write, so there is no
// second copy to keep in sync.
let runInBackground = false;
// A window that vanishes into the tray without a word is a window the user
// believes they quit.
let backgroundNoticeShown = false;
let quitting = false;

/** Resolves the compiled daemon binary shipped alongside the app. */
function daemonPath(): string | null {
  const name = process.platform === 'win32' ? 'switchboard.exe' : 'switchboard';
  const candidates = isDev
    ? [resolve(app.getAppPath(), '..', 'bin', name)]
    : [join(process.resourcesPath, 'bin', name), join(app.getAppPath(), 'bin', name)];
  return candidates.find(existsSync) ?? null;
}

/** Resolves the branded application icon. */
function appIconPath(): string | undefined {
  const icoCandidates = [
    join(app.getAppPath(), 'resources', 'icon.ico'),
    join(app.getAppPath(), 'dist', 'renderer', 'assets', 'icon.ico'),
    join(__dirname, '..', 'renderer', 'assets', 'icon.ico'),
    join(app.getAppPath(), 'src', 'renderer', 'assets', 'icon.ico')
  ];
  const pngCandidates = [
    join(app.getAppPath(), 'resources', 'icon.png'),
    join(app.getAppPath(), 'dist', 'renderer', 'assets', 'icon.png'),
    join(__dirname, '..', 'renderer', 'assets', 'icon.png'),
    join(app.getAppPath(), 'src', 'renderer', 'assets', 'icon.png')
  ];
  const candidates = process.platform === 'win32' ? [...icoCandidates, ...pngCandidates] : pngCandidates;
  return candidates.find(existsSync);
}

/** Resolves the tray icon asset. */
function trayIconPath(): string | undefined {
  const candidates = [
    join(app.getAppPath(), 'resources', 'tray.png'),
    join(app.getAppPath(), 'dist', 'renderer', 'assets', 'tray.png'),
    join(__dirname, '..', 'renderer', 'assets', 'tray.png'),
    join(app.getAppPath(), 'src', 'renderer', 'assets', 'tray.png')
  ];
  return candidates.find(existsSync);
}

/** True when a daemon is already answering on the local API. */
async function daemonRunning(): Promise<boolean> {
  try {
    // Generous: the first fetch in the main process pays a cold-start cost of
    // over a second, and a false negative here starts a second daemon that
    // then fails to bind the port.
    const res = await fetch(`http://127.0.0.1:${DAEMON_PORT}/local/state`, {
      signal: AbortSignal.timeout(5000)
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

/** Brings the window back and retires the tray icon until the next hide. */
function showWindow(): void {
  mainWindow?.show();
  mainWindow?.focus();
  tray?.destroy();
  tray = null;
}

/** Closing with background mode on parks the app instead of ending it. */
function hideToTray(): void {
  mainWindow?.hide();
  if (!tray) {
    const tIcon = trayIconPath();
    const trayImg = tIcon ? nativeImage.createFromPath(tIcon) : nativeImage.createFromDataURL(TRAY_ICON);
    tray = new Tray(trayImg);
    tray.setToolTip('Switchboard');
    tray.setContextMenu(
      Menu.buildFromTemplate([
        { label: 'Show Switchboard', click: showWindow },
        { type: 'separator' },
        {
          label: 'Quit',
          click: () => {
            quitting = true;
            app.quit();
          }
        }
      ])
    );
    tray.on('click', showWindow);
  }
  if (!backgroundNoticeShown && Notification.isSupported()) {
    backgroundNoticeShown = true;
    new Notification({
      title: 'Switchboard is still running',
      body: 'Paired devices stay connected. Use the tray icon to reopen or quit.'
    }).show();
  }
}

function createWindow(): void {
  const iconPath = appIconPath();
  mainWindow = new BrowserWindow({
    width: 1120,
    height: 720,
    minWidth: 840,
    minHeight: 520,
    show: false,
    frame: false,
    icon: iconPath,
    backgroundColor: '#1a1b26',
    webPreferences: {
      preload: join(__dirname, '..', 'preload', 'index.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false
    }
  });

  if (iconPath) {
    try {
      mainWindow.setIcon(nativeImage.createFromPath(iconPath));
    } catch {
      // Ignore fallback
    }
  }

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

  // Catches the renderer's own titlebar button too, since window:close routes
  // through close() rather than destroying the window.
  mainWindow.on('close', (event) => {
    if (quitting || !runInBackground) return;
    event.preventDefault();
    hideToTray();
  });

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
  '/mixer',
  '/media',
  '/media/artwork',
  '/pairing/rotate',
  '/devices/revoke',
  '/files/send',
  '/files/control',
  '/settings'
]);

async function daemonFetch<T>(route: string, body?: unknown): Promise<T> {
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
  return res.json() as Promise<T>;
}

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
    const result = await daemonFetch<unknown>(route, body);
    if (route === '/state') {
      runInBackground = (result as LocalState).settings?.runInBackground ?? runInBackground;
    } else if (route === '/settings') {
      runInBackground = (result as HostSettings).runInBackground;
    }
    return result;
  });
}

/** The file-system reach the renderer must not have: a picker and a reveal. */
function registerFileBridge(): void {
  ipcMain.handle('dialog:downloadDir', async () => {
    if (!mainWindow) return null;
    const { canceled, filePaths } = await dialog.showOpenDialog(mainWindow, {
      title: 'Choose where received files are saved',
      properties: ['openDirectory', 'createDirectory']
    });
    return canceled ? null : (filePaths[0] ?? null);
  });

  // The renderer only ever holds transfer IDs, so the path is looked up from
  // daemon state here rather than accepted from the page.
  ipcMain.handle('transfer:reveal', async (_event, transferId: string) => {
    const state = await daemonFetch<LocalState>('/state');
    const match = state.transfers.find((t) => t.transferId === transferId);
    if (match?.path) {
      shell.showItemInFolder(match.path);
      return;
    }
    // An outgoing transfer, or one whose row predates the path column, has no
    // file of its own to point at. Opening the download directory still lands
    // the user somewhere useful rather than doing nothing.
    await shell.openPath(state.settings.downloadDir);
  });
}

app.whenReady().then(async () => {
  registerWindowControls();
  registerDaemonBridge();
  registerFileBridge();
  await startDaemon();
  createWindow();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
    else showWindow();
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});

app.on('before-quit', () => {
  quitting = true;
  stopDaemon();
});
