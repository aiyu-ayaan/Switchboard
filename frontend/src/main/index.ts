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
import { spawn, exec, ChildProcess } from 'node:child_process';
import { existsSync } from 'node:fs';
import { join, resolve } from 'node:path';
import type { HostSettings, LocalState } from '../shared/types';
import { installExplorerVerb, pathsFromArgv } from './shellIntegration';
import { queueSendPaths, registerSendPicker } from './sendPicker';

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
  // Every location, rather than a branch on isDev. The Explorer verb launches
  // the app without SWITCHBOARD_DEV set, so from a checkout this looked only
  // in the packaged locations, found nothing, and opened the picker with no
  // daemon behind it — every device call refused the connection. existsSync is
  // what decides; whichever one is present is the right one.
  const candidates = [
    join(process.resourcesPath, 'bin', name),
    join(app.getAppPath(), 'bin', name),
    resolve(app.getAppPath(), '..', 'bin', name)
  ];
  return candidates.find(existsSync) ?? null;
}

/** Resolves the branded application icon. */
function appIconPath(): string | undefined {
  const icoCandidates = [
    join(process.cwd(), 'resources', 'icon.ico'),
    join(process.cwd(), 'frontend', 'resources', 'icon.ico'),
    join(app.getAppPath(), 'resources', 'icon.ico'),
    join(app.getAppPath(), 'dist', 'renderer', 'assets', 'icon.ico'),
    join(__dirname, '..', 'renderer', 'assets', 'icon.ico'),
    join(app.getAppPath(), 'src', 'renderer', 'assets', 'icon.ico')
  ];
  const pngCandidates = [
    join(process.cwd(), 'resources', 'icon.png'),
    join(process.cwd(), 'frontend', 'resources', 'icon.png'),
    join(app.getAppPath(), 'resources', 'icon.png'),
    join(app.getAppPath(), 'dist', 'renderer', 'assets', 'icon.png'),
    join(__dirname, '..', 'renderer', 'assets', 'icon.png'),
    join(app.getAppPath(), 'src', 'renderer', 'assets', 'icon.png')
  ];
  // Prefer PNG for high quality scaling on Windows taskbar in dev
  const candidates = [...pngCandidates, ...icoCandidates];
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

/** Resolves virtual camera installer scripts. */
function vcamScriptPath(scriptName: string): string | undefined {
  const candidates = [
    join(app.getAppPath(), 'driver', 'vcam', scriptName),
    join(app.getAppPath(), '..', 'driver', 'vcam', scriptName),
    join(__dirname, '..', '..', 'driver', 'vcam', scriptName),
    join(__dirname, '..', '..', '..', 'driver', 'vcam', scriptName),
    join(process.cwd(), 'driver', 'vcam', scriptName)
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
  // windowsHide, because the daemon is a console-subsystem binary: spawned
  // from a packaged Electron app, which has no console of its own, Windows
  // hands it a fresh one and an empty terminal window sits beside the app for
  // the whole session. Inherited stdio still reaches a dev terminal when there
  // is one, so this costs no logging.
  daemon = spawn(bin, ['--port', String(DAEMON_PORT)], {
    stdio: 'inherit',
    windowsHide: true
  });
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
  if (!mainWindow) {
    createWindow();
  } else {
    if (mainWindow.isMinimized()) mainWindow.restore();
    mainWindow.show();
    mainWindow.focus();
  }
  tray?.destroy();
  tray = null;
}

/** Puts the tray icon up, so a window-less app is still visible and quittable. */
function ensureTray(): void {
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
}

/** Closing with background mode on parks the app instead of ending it. */
function hideToTray(): void {
  mainWindow?.hide();
  ensureTray();
  if (!backgroundNoticeShown && Notification.isSupported()) {
    backgroundNoticeShown = true;
    new Notification({
      title: 'Switchboard is still running',
      body: 'Paired devices stay connected. Use the tray icon to reopen or quit.'
    }).show();
  }
}

function createWindow(options: { show?: boolean } = {}): void {
  const visible = options.show ?? true;
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
      const img = nativeImage.createFromPath(iconPath);
      if (!img.isEmpty()) {
        mainWindow.setIcon(img);
      }
    } catch {
      // Ignore fallback
    }
  }

  mainWindow.once('ready-to-show', () => {
    if (visible) mainWindow?.show();
  });

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
    if (quitting || isDev || !runInBackground) return;
    event.preventDefault();
    hideToTray();
  });

  mainWindow.on('closed', () => {
    mainWindow = null;
    if (isDev) {
      quitting = true;
      app.quit();
    }
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
  ipcMain.handle('shell:openExternal', async (_event, url: string) => {
    if (typeof url === 'string' && (url.startsWith('https://') || url.startsWith('http://'))) {
      await shell.openExternal(url);
    }
  });
}

function syncAutoStart(autoStart: boolean, background: boolean): void {
  try {
    if (isDev && !app.isPackaged) {
      app.setLoginItemSettings({
        openAtLogin: autoStart,
        path: process.execPath,
        args: [app.getAppPath(), '--hidden']
      });
    } else {
      app.setLoginItemSettings({
        openAtLogin: autoStart,
        openAsHidden: background,
        args: ['--hidden']
      });
    }
  } catch (err) {
    console.warn('Failed to sync login item settings:', err);
  }
}

/** Endpoints the renderer may reach, so a compromised page cannot pick its own. */
const ALLOWED_ROUTES = new Set([
  '/state',
  '/displays/refresh',
  '/display/brightness',
  '/display/contrast',
  '/volume',
  '/mixer',
  '/audio/output',
  '/media',
  '/media/artwork',
  '/pairing/rotate',
  '/devices/revoke',
  '/files/send',
  '/files/control',
  '/files/history',
  '/settings',
  '/system/lock',
  '/camera/state',
  '/camera/vcam/status',
  '/camera/start',
  '/camera/stop',
  '/camera/control',
  '/deck',
  '/deck/action',
  '/system/apps'
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
      const state = result as LocalState;
      if (state?.settings) {
        runInBackground = state.settings.runInBackground ?? runInBackground;
        if (typeof state.settings.autoStart === 'boolean') {
          syncAutoStart(state.settings.autoStart, runInBackground);
        }
      }
    } else if (route === '/settings') {
      const settings = result as HostSettings;
      runInBackground = settings.runInBackground;
      if (typeof settings.autoStart === 'boolean') {
        syncAutoStart(settings.autoStart, runInBackground);
      }
    }
    return result;
  });
}

/**
 * Pumps camera frames from the daemon to the renderer.
 *
 * The renderer keeps `default-src 'self'` and makes no network requests, so it
 * cannot open the daemon's MJPEG stream itself. This holds the stream open on
 * its behalf and forwards each frame over IPC.
 *
 * One connection for the whole session, not a request per frame. The previous
 * long poll asked for the next frame only after handing the last one over, so
 * every frame paid a fresh request and anything the phone produced inside that
 * gap was dropped by the daemon's latest-frame holder. Thirty times a second
 * that is not a bandwidth problem, it is a *timing* one: frames arrived in an
 * uneven rhythm, which is exactly what stutter is. A held stream delivers them
 * at the rate they were captured.
 *
 * Only one pump runs however many subscribers there are, and it stops when the
 * last one leaves — a camera nobody is watching should cost nothing.
 */
let cameraSubscribers = 0;
let cameraPump: Promise<void> | null = null;

/**
 * Cuts the held connections when the last viewer leaves.
 *
 * Without it, a phone that drops off Wi-Fi mid-stream leaves the daemon's
 * handler waiting on frames that will never come and this side blocked reading
 * a body that will never produce another chunk — a connection neither end has
 * any reason to close.
 */
const cameraAborts = new Set<AbortController>();

const abortCameraStreams = () => {
  for (const abort of cameraAborts) abort.abort();
  cameraAborts.clear();
};

/**
 * The two tracks the daemon publishes.
 *
 * `video` is H.264 off the phone's hardware encoder and is what the live view
 * renders: a software JPEG per frame could not keep up with the rates the UI
 * offers, and cost a few hundred kilobytes each where these cost a few. The
 * MJPEG track stays because it is the fallback for a phone with no hardware
 * encoder, and it is what the virtual camera and OBS read.
 */
const CAMERA_TRACKS = [
  { path: '/local/camera/video', channel: 'camera:video' },
  { path: '/local/camera/stream', channel: 'camera:frame' }
] as const;

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

const pumpAlive = () => cameraSubscribers > 0 && Boolean(mainWindow) && !mainWindow!.isDestroyed();

async function pumpCameraFrames(): Promise<void> {
  await Promise.all(CAMERA_TRACKS.map((track) => pumpTrack(track.path, track.channel)));
  cameraPump = null;
}

async function pumpTrack(path: string, channel: string): Promise<void> {
  while (pumpAlive()) {
    try {
      await readParts(path, channel);
    } catch {
      // The daemon restarted, or nothing is streaming yet. Either way the
      // loop is the recovery; the pause keeps a dead camera from becoming a
      // busy wait.
    }
    if (pumpAlive()) await sleep(1000);
  }
}

/**
 * Reads `multipart/x-mixed-replace` until the stream ends, emitting each part.
 *
 * The daemon always sends a `Content-Length`, so the boundary marker itself
 * never has to be searched for inside the payload — which is just as well,
 * since a boundary string can legitimately occur inside compressed data.
 *
 * The video track adds `X-` headers describing the access unit. They ride in
 * the part header rather than a parallel channel so one frame's metadata can
 * never arrive out of step with its bytes.
 */
const PART_HEADER_END = Buffer.from([0x0d, 0x0a, 0x0d, 0x0a]); // CRLF CRLF

const headerValue = (header: string, name: string): string | undefined =>
  new RegExp(`^${name}:\\s*(.+)$`, 'im').exec(header)?.[1].trim();

async function readParts(path: string, channel: string): Promise<void> {
  const abort = new AbortController();
  cameraAborts.add(abort);
  try {
    const res = await fetch(`http://127.0.0.1:${DAEMON_PORT}${path}`, { signal: abort.signal });
    if (!res.ok || !res.body) throw new Error(`camera stream: ${res.status}`);

    let buffer = Buffer.alloc(0);
    let expected = -1; // bytes of payload still to collect, or -1 while in headers
    let header = '';

    for await (const chunk of res.body as unknown as AsyncIterable<Uint8Array>) {
      if (!pumpAlive()) return;
      buffer = Buffer.concat([buffer, chunk]);

      // A single chunk can hold the tail of one frame and the head of the next,
      // so each pass drains everything complete rather than one part per chunk.
      for (;;) {
        if (expected < 0) {
          const headerEnd = buffer.indexOf(PART_HEADER_END);
          if (headerEnd < 0) break;
          header = buffer.subarray(0, headerEnd).toString('latin1');
          const length = headerValue(header, 'content-length');
          if (!length) throw new Error('camera stream: part without a length');
          expected = Number(length);
          buffer = buffer.subarray(headerEnd + PART_HEADER_END.length);
        }
        if (buffer.length < expected) break;

        // Copied out of the accumulator: subarray shares memory with a buffer
        // that is about to be concatenated over.
        const payload = new Uint8Array(buffer.subarray(0, expected)).buffer;
        if (channel === 'camera:video') {
          mainWindow?.webContents.send(channel, {
            data: payload,
            key: headerValue(header, 'x-key') === '1',
            rotation: Number(headerValue(header, 'x-rotation') ?? 0),
            mirror: headerValue(header, 'x-mirror') === '1',
            width: Number(headerValue(header, 'x-width') ?? 0),
            height: Number(headerValue(header, 'x-height') ?? 0),
            timestamp: Number(headerValue(header, 'x-timestamp') ?? 0)
          });
        } else {
          mainWindow?.webContents.send(channel, payload);
        }
        buffer = buffer.subarray(expected);
        expected = -1;
      }
    }
  } finally {
    cameraAborts.delete(abort);
  }
}

function registerCameraBridge(): void {
  ipcMain.on('camera:subscribe', () => {
    cameraSubscribers += 1;
    if (!cameraPump) cameraPump = pumpCameraFrames();
  });
  ipcMain.on('camera:unsubscribe', () => {
    cameraSubscribers = Math.max(0, cameraSubscribers - 1);
    // The pump is parked on a read that may never return on its own.
    if (cameraSubscribers === 0) abortCameraStreams();
  });

  ipcMain.handle('camera:vcamStatus', async () => {
    try {
      return await daemonFetch<{ installed: boolean; deviceName: string }>('/camera/vcam/status');
    } catch {
      return { installed: false, deviceName: 'Switchboard Camera' };
    }
  });

  ipcMain.handle('camera:installVcam', async () => {
    const script = vcamScriptPath('install-camera.bat');
    if (!script) {
      return { success: false, error: 'Installation script install-camera.bat not found' };
    }
    return new Promise((resolveResult) => {
      exec(
        `powershell -Command "Start-Process cmd -ArgumentList '/c \\"\\"${script}\\" /silent\\"' -Verb RunAs -Wait"`,
        (error) => {
          if (error) {
            resolveResult({ success: false, error: error.message });
          } else {
            resolveResult({ success: true });
          }
        }
      );
    });
  });

  ipcMain.handle('camera:uninstallVcam', async () => {
    const script = vcamScriptPath('uninstall-camera.bat');
    if (!script) {
      return { success: false, error: 'Uninstallation script uninstall-camera.bat not found' };
    }
    return new Promise((resolveResult) => {
      exec(
        `powershell -Command "Start-Process cmd -ArgumentList '/c \\"\\"${script}\\" /silent\\"' -Verb RunAs -Wait"`,
        (error) => {
          if (error) {
            resolveResult({ success: false, error: error.message });
          } else {
            resolveResult({ success: true });
          }
        }
      );
    });
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

  // Keep host filesystem access in the main process while allowing the deck
  // picker to show the actual Windows shell icon for each discovered shortcut.
  //
  // The Start Menu catalogue is made of `.lnk` shortcuts, and asking the shell
  // for a shortcut's own icon yields the generic "shortcut" document glyph --
  // every entry looked identical. The icon a user recognises belongs to what
  // the shortcut points at, so resolve the link first and read the icon from
  // its declared icon location, or failing that from its target executable.
  ipcMain.handle('deck:appIcon', async (_event, path: string) => {
    if (typeof path !== 'string' || path.length === 0 || path.length > 32_768) return '';

    const candidates: string[] = [];
    if (process.platform === 'win32' && path.toLowerCase().endsWith('.lnk')) {
      try {
        const link = shell.readShortcutLink(path);
        if (link.icon) candidates.push(link.icon);
        if (link.target) candidates.push(link.target);
      } catch {
        // Unreadable shortcut: fall through to the shortcut file itself.
      }
    }
    candidates.push(path);

    for (const candidate of candidates) {
      try {
        // 48px. A deck key renders the icon far larger than the 16px 'small'
        // variant the picker used to ask for, and Windows hands back genuinely
        // more detail at this size for any binary that ships an icon resource.
        const image = await app.getFileIcon(candidate, { size: 'large' });
        if (!image.isEmpty()) return image.toDataURL();
      } catch {
        // Try the next candidate.
      }
    }
    return '';
  });
}

async function bootstrap(): Promise<void> {
  // Read before `whenReady`, because Explorer's verb is how this launch was
  // started: the paths are already on our own command line.
  const coldSendPaths = pathsFromArgv(process.argv);
  const isAutoBoot = process.argv.includes('--hidden') || app.getLoginItemSettings().wasOpenedAtLogin;

  await app.whenReady();
  registerWindowControls();
  registerDaemonBridge();
  registerCameraBridge();
  registerFileBridge();
  registerSendPicker({
    isDev,
    devServer: DEV_SERVER,
    rendererFile: join(__dirname, '..', 'renderer', 'index.html'),
    preload: join(__dirname, '..', 'preload', 'index.js'),
    iconPath: appIconPath(),
    getDevices: async () => (await daemonFetch<LocalState>('/state')).devices,
    sendFiles: (deviceId, paths) => daemonFetch('/files/send', { deviceId, paths }),
    onCancelled: () => {
      // Only a launch the verb made, and only while the shell is still hidden:
      // a running app whose window the user can see stays running.
      if (coldSendPaths.length > 0 && !mainWindow?.isVisible()) {
        quitting = true;
        app.quit();
      }
    }
  });
  await startDaemon();

  try {
    const initialState = await daemonFetch<LocalState>('/state');
    if (initialState?.settings) {
      runInBackground = initialState.settings.runInBackground ?? runInBackground;
      if (typeof initialState.settings.autoStart === 'boolean') {
        syncAutoStart(initialState.settings.autoStart, runInBackground);
      }
    }
  } catch {
    // Daemon will be polled by renderer anyway
  }

  // Launched by the Explorer verb rather than by the user: the picker is the
  // entire interaction, so the shell stays out of the way. The window is still
  // created, hidden — closing the last one would quit the app and take the
  // daemon, and the transfer, down with it — and the tray icon is what keeps
  // that running app visible and quittable.
  // Similarly, on automatic boot launch with background mode enabled, stay in tray.
  const shouldShow = coldSendPaths.length === 0 && !(isAutoBoot && runInBackground);
  createWindow({ show: shouldShow });
  if (coldSendPaths.length > 0) {
    ensureTray();
    queueSendPaths(coldSendPaths);
  } else if (!shouldShow) {
    ensureTray();
  }

  // Unpackaged, `execPath` is electron.exe and needs the app directory handed
  // to it, or the menu entry opens a blank Electron. Registering in both modes
  // is what makes the verb testable without packaging first.
  if (process.platform === 'win32') {
    void installExplorerVerb(
      process.execPath,
      app.isPackaged ? null : app.getAppPath(),
      appIconPath()
    );
  }

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
    else showWindow();
  });
}

// One instance, always. The daemon binds port 9427 and holds the SQLite
// identity store, so a second copy would fail to bind and un-pair nothing but
// itself — and Explorer's verb launches the app afresh on every right-click.
// Those launches hand their paths to the instance already running.
if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', (_event, argv) => {
    const paths = pathsFromArgv(argv);
    if (paths.length > 0) queueSendPaths(paths);
    else showWindow();
  });
  void bootstrap();
}

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});

app.on('before-quit', () => {
  quitting = true;
  stopDaemon();
});
