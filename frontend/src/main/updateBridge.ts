// The part of the updater that needs the app: where the download waits, what
// the channel is set to, and running the installer.
//
// The rules and the transfer live in `updates.ts`, which imports no Electron
// so it can be exercised from plain Node. This file is the app around them.
import { app, ipcMain, shell, BrowserWindow } from 'electron';
import { spawn } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import type { UpdateStatus } from '../shared/types';
import {
  CHANNELS,
  channelOf,
  downloadAsset,
  findUpdate,
  isChannel,
  sweepDownloads,
  type Channel,
  type UpdateOffer
} from './updates';

/** Launch, then every six hours. The API allows sixty calls an hour. */
const CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000;

/**
 * The channel lives beside the app rather than in the daemon's settings: it is
 * a property of this copy of Switchboard, not of the pairing, and a phone has
 * no business choosing which desktop build gets installed.
 */
interface UpdatePreferences {
  channel: Channel;
}

let preferencesFile = '';
let downloadDirectory = '';
let status: UpdateStatus = { phase: 'idle', channel: 'stable', installedVersion: '0.0.0' };

function readPreferences(): UpdatePreferences {
  try {
    const parsed = JSON.parse(readFileSync(preferencesFile, 'utf8')) as Partial<UpdatePreferences>;
    if (isChannel(parsed.channel)) return { channel: parsed.channel };
  } catch {
    // No file yet, or one written by a build that stored something else.
  }
  return { channel: channelOf(app.getVersion()) };
}

function writePreferences(preferences: UpdatePreferences): void {
  try {
    writeFileSync(preferencesFile, JSON.stringify(preferences, null, 2));
  } catch {
    // A machine-local convenience: failing to remember the channel must not
    // stop the check that uses it.
  }
}

/** Pushes the current status at the renderer, which draws it in Settings. */
function publish(next: Partial<UpdateStatus>): UpdateStatus {
  status = { ...status, ...next };
  for (const window of BrowserWindow.getAllWindows()) {
    window.webContents.send('updates:status', status);
  }
  return status;
}

/**
 * Ask GitHub what exists, and start the download if any of it is newer.
 *
 * The download is not waited for by the caller and is never asked about first.
 * Waiting for a click put the button that matters — Restart and install — a
 * hundred megabytes behind the one somebody pressed; nothing is ever installed
 * unasked, and the transfer is the slow half that can happen quietly.
 *
 * `manual` is the difference between the button in Settings and the check on a
 * timer: the button says so when there is nothing new, the timer stays quiet.
 */
async function check(manual: boolean): Promise<UpdateStatus> {
  if (status.phase === 'checking' || status.phase === 'downloading') return status;
  publish({ phase: 'checking', error: undefined });

  let offer: UpdateOffer | null;
  try {
    offer = await findUpdate(app.getVersion(), status.channel, app.isPackaged);
  } catch (error) {
    return publish({
      phase: 'error',
      error: error instanceof Error ? error.message : 'The update check failed'
    });
  }

  status = { ...status, lastCheckedAt: Date.now() };
  if (!offer) {
    return publish({ phase: manual || app.isPackaged ? 'uptodate' : 'idle', update: undefined });
  }

  // Already on the disk from a previous run: nothing to fetch.
  if (status.phase === 'ready' && status.update?.version === offer.version) return status;

  publish({
    phase: 'downloading',
    progress: 0,
    update: {
      version: offer.version,
      name: offer.name,
      notes: offer.notes,
      publishedAt: offer.publishedAt,
      size: offer.asset.size
    }
  });

  try {
    const file = await downloadAsset(offer.asset, downloadDirectory, (fraction) => {
      if (status.phase === 'downloading') publish({ progress: fraction });
    });
    return publish({ phase: 'ready', progress: 1, file });
  } catch (error) {
    return publish({
      phase: 'error',
      error: error instanceof Error ? error.message : 'The download failed'
    });
  }
}

/**
 * Run the downloaded installer and get out of its way.
 *
 *   --updated    replacing an install rather than making one
 *   /S           silent: no wizard, and the chosen directory is kept
 *   --force-run  start Switchboard again once the files are in place
 *
 * Started bare, the installer opens its wizard behind a running Switchboard and
 * waits for a click nobody can see, so the button appears to do nothing at all.
 *
 * The app quits rather than being killed by the installer: quitting is what
 * stops the Go daemon, and an installer writing over a running daemon's
 * directory while it holds port 9427 and a SQLite handle is how an install ends
 * up half-applied.
 */
function install(): { started: boolean; error?: string } {
  if (status.phase !== 'ready' || !status.file || !existsSync(status.file)) {
    return { started: false, error: 'There is no downloaded update to install' };
  }
  try {
    const child = spawn(status.file, ['--updated', '/S', '--force-run'], {
      detached: true,
      stdio: 'ignore'
    });
    child.unref();
  } catch (error) {
    // The file is on the disk and runnable, so show it rather than swallowing
    // the failure: the user can finish the update by hand.
    void shell.showItemInFolder(status.file);
    return {
      started: false,
      error: error instanceof Error ? error.message : 'The installer could not be started'
    };
  }
  setTimeout(() => app.quit(), 400);
  return { started: true };
}

/**
 * Wires the IPC and starts checking. Called once, after `whenReady`.
 *
 * A development run has no install for a release to replace, so it never
 * checks: `findUpdate` refuses an unpackaged build, and the timer would only
 * spend GitHub's rate limit to be told so.
 */
export function registerUpdateBridge(): void {
  const userData = app.getPath('userData');
  preferencesFile = join(userData, 'updates.json');
  downloadDirectory = join(userData, 'updates');
  mkdirSync(downloadDirectory, { recursive: true });

  const installedVersion = app.getVersion();
  status = {
    phase: 'idle',
    channel: readPreferences().channel,
    installedVersion,
    packaged: app.isPackaged
  };

  // A download survives a quit, so the previous run's may already be here.
  const pending = sweepDownloads(downloadDirectory, installedVersion);
  if (pending) {
    status = {
      ...status,
      phase: 'ready',
      progress: 1,
      file: pending.file,
      update: { version: pending.version, name: `v${pending.version}`, notes: '', publishedAt: '', size: 0 }
    };
  }

  ipcMain.handle('updates:status', () => status);
  ipcMain.handle('updates:check', () => check(true));
  ipcMain.handle('updates:install', () => install());
  ipcMain.handle('updates:setChannel', (_event, channel: unknown) => {
    if (!isChannel(channel) || channel === status.channel) return status;
    writePreferences({ channel });
    // What was on offer came from the old channel, and on a narrower one it may
    // not be on offer at all. Applied here rather than waited for, so a slow or
    // refused check does not leave the old channel looking selected.
    status = { ...status, channel, update: undefined, file: undefined, phase: 'idle' };
    publish({});
    return check(true);
  });
  ipcMain.handle('updates:channels', () => CHANNELS);

  if (!app.isPackaged) return;
  void check(false);
  const timer = setInterval(() => void check(false), CHECK_INTERVAL_MS);
  app.on('before-quit', () => clearInterval(timer));
}
