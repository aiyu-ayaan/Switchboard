// The phone's shared screen, on the desktop.
//
// Every window that shows the phone -- the Phone view in the main shell and
// each app window -- gets its own stream from the daemon rather than a copy of
// one. The daemon starts each stream at the newest keyframe it holds, so a
// window opened mid-stream shows a picture immediately; fanning one stream out
// would leave every late window blank until the phone's next keyframe.
import { BrowserWindow, ipcMain, nativeImage } from 'electron';
import type { IpcMainEvent, WebContents } from 'electron';

interface PhoneDeps {
  isDev: boolean;
  devServer: string;
  rendererFile: string;
  preload: string;
  port: number;
  iconPath?: string;
}

const PART_HEADER_END = Buffer.from([0x0d, 0x0a, 0x0d, 0x0a]);

const headerValue = (header: string, name: string): string | undefined =>
  new RegExp(`^${name}:\\s*(.+)$`, 'im').exec(header)?.[1].trim();

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

/** Live streams, by the window they feed. */
const streams = new Map<number, AbortController>();

/** App windows, by package, so opening an app twice focuses its window. */
const appWindows = new Map<string, BrowserWindow>();

let deps: PhoneDeps | null = null;

/**
 * Reads `multipart/x-mixed-replace` H.264 from the daemon into one window
 * until the window goes away or unsubscribes. Each part carries a
 * Content-Length, so the boundary never has to be searched for inside the
 * compressed bytes.
 */
async function pump(target: WebContents, abort: AbortController): Promise<void> {
  const alive = () => !abort.signal.aborted && !target.isDestroyed();
  while (alive()) {
    try {
      const res = await fetch(`http://127.0.0.1:${deps!.port}/local/mirror/video`, {
        signal: abort.signal
      });
      if (!res.ok || !res.body) throw new Error(`mirror stream: ${res.status}`);

      let buffer = Buffer.alloc(0);
      let expected = -1;
      let header = '';
      for await (const chunk of res.body as unknown as AsyncIterable<Uint8Array>) {
        if (!alive()) return;
        buffer = Buffer.concat([buffer, chunk]);
        for (;;) {
          if (expected < 0) {
            const end = buffer.indexOf(PART_HEADER_END);
            if (end < 0) break;
            header = buffer.subarray(0, end).toString('latin1');
            const length = headerValue(header, 'content-length');
            if (!length) throw new Error('mirror stream: part without a length');
            expected = Number(length);
            buffer = buffer.subarray(end + PART_HEADER_END.length);
          }
          if (buffer.length < expected) break;
          const data = new Uint8Array(buffer.subarray(0, expected)).buffer;
          target.send('phone:video', {
            data,
            key: headerValue(header, 'x-key') === '1',
            rotation: 0,
            mirror: false,
            width: Number(headerValue(header, 'x-width') ?? 0),
            height: Number(headerValue(header, 'x-height') ?? 0),
            timestamp: Number(headerValue(header, 'x-timestamp') ?? 0)
          });
          buffer = buffer.subarray(expected);
          expected = -1;
        }
      }
    } catch {
      // Nothing mirroring yet, or the daemon restarted. The loop is the
      // recovery; the pause keeps an idle phone from being a busy wait.
    }
    if (alive()) await sleep(700);
  }
}

function subscribe(event: IpcMainEvent): void {
  const target = event.sender;
  if (streams.has(target.id)) return;
  const abort = new AbortController();
  streams.set(target.id, abort);
  target.once('destroyed', () => unsubscribeId(target.id));
  void pump(target, abort);
}

function unsubscribeId(id: number): void {
  streams.get(id)?.abort();
  streams.delete(id);
}

/**
 * Opens a window for one phone app, or for the whole phone when no package is
 * given. Sized like a phone, and kept per package: asking again focuses it.
 */
function openWindow(pkg: string, label: string): void {
  if (!deps) return;
  const key = pkg || '*';
  const existing = appWindows.get(key);
  if (existing && !existing.isDestroyed()) {
    existing.focus();
    return;
  }

  const win = new BrowserWindow({
    width: 420,
    height: 860,
    minWidth: 260,
    minHeight: 420,
    title: label,
    show: false,
    autoHideMenuBar: true,
    backgroundColor: '#11121a',
    icon: deps.iconPath ? nativeImage.createFromPath(deps.iconPath) : undefined,
    webPreferences: {
      preload: deps.preload,
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false
    }
  });
  appWindows.set(key, win);

  const hash = `phone?pkg=${encodeURIComponent(pkg)}&label=${encodeURIComponent(label)}`;
  if (deps.isDev) win.loadURL(`${deps.devServer}#${hash}`);
  else win.loadFile(deps.rendererFile, { hash });

  // The page sets its own title from the label; keep it rather than letting
  // the document title replace it.
  win.on('page-title-updated', (e) => e.preventDefault());
  win.once('ready-to-show', () => win.show());
  win.on('closed', () => {
    if (appWindows.get(key) === win) appWindows.delete(key);
    announceWindows();
  });
  announceWindows();
}

/** Which phone windows are open, by package ('' for the whole phone). */
const openKeys = () => [...appWindows.keys()].map((key) => (key === '*' ? '' : key));

/**
 * Tells every window which phone windows exist. The main shell uses it to stop
 * drawing the phone itself while the phone is out in a window of its own:
 * two live copies side by side is one too many, and the hidden one would
 * still be decoding every frame.
 */
function announceWindows(): void {
  const keys = openKeys();
  for (const win of BrowserWindow.getAllWindows()) {
    if (!win.isDestroyed()) win.webContents.send('phone:windows', keys);
  }
}

export function registerPhoneBridge(dependencies: PhoneDeps): void {
  deps = dependencies;
  ipcMain.on('phone:subscribe', subscribe);
  ipcMain.on('phone:unsubscribe', (event) => unsubscribeId(event.sender.id));
  ipcMain.handle('phone:windows', () => openKeys());
  ipcMain.handle('phone:focusWindows', () => {
    for (const win of appWindows.values()) if (!win.isDestroyed()) win.focus();
  });
  ipcMain.handle('phone:closeWindows', () => {
    for (const win of [...appWindows.values()]) if (!win.isDestroyed()) win.close();
  });
  ipcMain.handle('phone:openWindow', (_event, pkg: unknown, label: unknown) => {
    // Strings only: the renderer names an app, never a URL or a path.
    if (typeof pkg !== 'string' || typeof label !== 'string') return;
    openWindow(pkg.slice(0, 256), label.slice(0, 128));
  });
}

