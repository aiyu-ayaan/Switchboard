// The "Send to Switchboard" picker window.
//
// A small always-on-top list of paired devices: click one and the files
// Explorer handed us start moving. It is deliberately not the main window —
// right-clicking a file is a two-second action, and raising a 1120x720 shell
// over whatever the user was doing to complete it is not.
import { BrowserWindow, ipcMain, nativeImage } from 'electron';
import { statSync } from 'node:fs';
import { basename } from 'node:path';
import type { PairedDevice } from '../shared/types';

interface PickerDeps {
  isDev: boolean;
  devServer: string;
  rendererFile: string;
  preload: string;
  iconPath?: string;
  getDevices: () => Promise<PairedDevice[]>;
  sendFiles: (deviceId: string, paths: string[]) => Promise<unknown>;
  /**
   * The picker closed and this process has never sent anything.
   *
   * A launch that exists only to show the picker has nothing left to do once
   * it is dismissed: its window is hidden, so nothing would ever bring the app
   * back, and it would sit on the single-instance lock — swallowing every
   * later right-click into a process the user cannot see.
   */
  onCancelled: () => void;
}

/**
 * How long to gather paths before opening.
 *
 * Explorer's default multi-select model invokes a verb once *per selected
 * file*, so a five-file selection arrives as five separate launches a few
 * milliseconds apart. Without this window they would stack five pickers. The
 * `MultiSelectModel=Player` hint asks Explorer for a single invocation
 * instead, but it is a hint: this is what makes the batch reliable either way.
 */
const BATCH_MS = 400;

let picker: BrowserWindow | null = null;
let pending: string[] = [];
let batchTimer: NodeJS.Timeout | null = null;
let deps: PickerDeps | null = null;
// Once anything has been sent the process has a transfer to look after, so a
// later dismissal must never take it down.
let everSent = false;

/** What the window is allowed to know about the files: their names. */
const names = () => pending.map((path) => basename(path));

function pushFiles(): void {
  picker?.webContents.send('sendPicker:files', names());
}

function closePicker(): void {
  pending = [];
  picker?.close();
}

function openPicker(): void {
  if (!deps) return;
  if (picker && !picker.isDestroyed()) {
    pushFiles();
    picker.focus();
    return;
  }

  picker = new BrowserWindow({
    width: 380,
    height: 460,
    show: false,
    frame: false,
    resizable: false,
    minimizable: false,
    maximizable: false,
    // The user invoked this from Explorer and is looking at Explorer: a
    // picker behind that window is a picker they never see.
    alwaysOnTop: true,
    skipTaskbar: true,
    backgroundColor: '#1a1b26',
    icon: deps.iconPath ? nativeImage.createFromPath(deps.iconPath) : undefined,
    webPreferences: {
      preload: deps.preload,
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false
    }
  });

  // Same bundle as the main window, picked apart by the hash. A second Vite
  // entry point would be a second build target for one 90-line component.
  if (deps.isDev) picker.loadURL(`${deps.devServer}#send`);
  else picker.loadFile(deps.rendererFile, { hash: 'send' });

  picker.once('ready-to-show', () => {
    picker?.show();
    pushFiles();
  });

  // Deliberately not dismiss-on-blur. A shell menu behaves that way, but this
  // window is opened by a process the shell has just launched, and focus does
  // not reliably settle on it — it lands back on Explorer often enough that the
  // picker closed itself before it could be clicked. Escape and the close
  // button dismiss it; sending closes it.
  picker.on('closed', () => {
    picker = null;
    pending = [];
    if (!everSent) deps?.onCancelled();
  });
}

/**
 * Whether a command-line argument is actually a file we can offer to send.
 *
 * A launch's arguments are not only the shell's: unpackaged, electron.exe is
 * handed the app directory too, and it would otherwise be listed as a file to
 * send. Directories are refused by the daemon regardless, so the picker should
 * never show one — and anything unreadable is not worth offering either.
 */
function isSendableFile(path: string): boolean {
  try {
    return statSync(path).isFile();
  } catch {
    return false;
  }
}

/**
 * Adds paths to the picker, opening it once the batch settles.
 *
 * De-duplicated because a selection that arrives one file per invocation can
 * legitimately repeat a path if Explorer retries.
 */
export function queueSendPaths(paths: string[]): void {
  const files = paths.filter(isSendableFile);
  if (files.length === 0) return;
  pending = [...new Set([...pending, ...files])];
  if (batchTimer) clearTimeout(batchTimer);
  batchTimer = setTimeout(() => {
    batchTimer = null;
    openPicker();
  }, BATCH_MS);
}

export function registerSendPicker(dependencies: PickerDeps): void {
  deps = dependencies;

  // The window asks for its own list on mount: `ready-to-show` can beat the
  // renderer's first render, and a push nobody is listening for is lost.
  ipcMain.handle('sendPicker:files', () => names());
  ipcMain.handle('sendPicker:devices', () => dependencies.getDevices());
  ipcMain.handle('sendPicker:cancel', () => closePicker());

  // The renderer names a device, never a path. The files sent are the ones
  // this process collected from Explorer, so a compromised page cannot turn
  // the picker into "read any file on disk and post it to a phone".
  ipcMain.handle('sendPicker:send', async (_event, deviceId: string) => {
    const paths = pending;
    if (paths.length === 0) return;
    await dependencies.sendFiles(deviceId, paths);
    everSent = true;
    closePicker();
  });
}
