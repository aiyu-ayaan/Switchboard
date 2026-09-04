#!/usr/bin/env node

/**
 * Switchboard Android Emulator & Device Gateway
 *
 * Automatically detects running Android emulators and USB-connected devices,
 * establishing and maintaining ADB reverse port forwarding (tcp:9427 -> host:9427).
 *
 * This allows Android emulators and physical devices to seamlessly connect to
 * the desktop daemon using 127.0.0.1:9427 or localhost:9427 as if they were
 * running directly on the host machine.
 */

import { spawn, execSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { resolve } from 'node:path';
import process from 'node:process';

const isWindows = process.platform === 'win32';
const PORT = Number(process.env.SWITCHBOARD_PORT ?? 9427);
const POLL_INTERVAL_MS = 3000;

function log(message) {
  const cyan = '\x1b[36m';
  const reset = '\x1b[0m';
  console.log(`${cyan}[gateway]${reset} ${message}`);
}

function findAdb() {
  const candidatePaths = [
    'D:\\AndroidWorkSpace\\sdk\\platform-tools\\adb.exe',
    process.env.ANDROID_HOME && resolve(process.env.ANDROID_HOME, 'platform-tools', isWindows ? 'adb.exe' : 'adb'),
    process.env.ANDROID_SDK_ROOT && resolve(process.env.ANDROID_SDK_ROOT, 'platform-tools', isWindows ? 'adb.exe' : 'adb'),
    process.env.LOCALAPPDATA && resolve(process.env.LOCALAPPDATA, 'Android', 'Sdk', 'platform-tools', isWindows ? 'adb.exe' : 'adb')
  ].filter(Boolean);

  for (const p of candidatePaths) {
    if (existsSync(p)) {
      return p;
    }
  }

  // Fallback to adb in PATH
  return isWindows ? 'adb.exe' : 'adb';
}

const adbPath = findAdb();

function execAdb(args) {
  return new Promise((resolvePromise, rejectPromise) => {
    const cmd = isWindows && adbPath.includes(' ') ? `"${adbPath}"` : adbPath;
    const child = spawn(cmd, args, { shell: true });

    let stdout = '';
    let stderr = '';

    child.stdout.on('data', (data) => { stdout += data.toString(); });
    child.stderr.on('data', (data) => { stderr += data.toString(); });

    child.on('error', rejectPromise);
    child.on('close', (code) => {
      if (code === 0) resolvePromise(stdout.trim());
      else rejectPromise(new Error(stderr.trim() || `adb exited with code ${code}`));
    });
  });
}

const forwardedDevices = new Set();

async function syncReverseTunnels() {
  try {
    const output = await execAdb(['devices']);
    const lines = output.split('\n').map(l => l.trim()).filter(Boolean);

    const currentOnlineDevices = new Set();

    for (let i = 1; i < lines.length; i++) {
      const parts = lines[i].split(/\s+/);
      if (parts.length >= 2) {
        const [deviceId, status] = parts;
        if (status === 'device') {
          currentOnlineDevices.add(deviceId);

          if (!forwardedDevices.has(deviceId)) {
            try {
              await execAdb(['-s', deviceId, 'reverse', `tcp:${PORT}`, `tcp:${PORT}`]);
              forwardedDevices.add(deviceId);
              log(`Established reverse proxy for device "${deviceId}" (emulator 127.0.0.1:${PORT} -> host 127.0.0.1:${PORT})`);
            } catch (err) {
              log(`Failed to reverse port for ${deviceId}: ${err.message}`);
            }
          }
        }
      }
    }

    // Clean up disconnected devices from tracking
    for (const dev of forwardedDevices) {
      if (!currentOnlineDevices.has(dev)) {
        forwardedDevices.delete(dev);
        log(`Device "${dev}" disconnected. Removed from active gateway tracking.`);
      }
    }
  } catch (err) {
    // ADB server may be starting or offline
    if (err.message && !err.message.includes('daemon not running')) {
      // transient adb warning
    }
  }
}

log(`Android Gateway initialized for port ${PORT}.`);
log(`Using ADB: ${adbPath}`);

// Run initial sync
syncReverseTunnels();

// Continually sync to capture new emulators and reconnects
const interval = setInterval(syncReverseTunnels, POLL_INTERVAL_MS);

function cleanup() {
  clearInterval(interval);
  log('Shutting down Android gateway.');
  process.exit(0);
}

process.on('SIGINT', cleanup);
process.on('SIGTERM', cleanup);
