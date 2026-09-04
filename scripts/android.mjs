#!/usr/bin/env node

/**
 * Switchboard Android Helper Runner
 * Handles building, running, and testing the Android mobile client.
 */

import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import process from 'node:process';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const rootDir = resolve(__dirname, '..');
const mobileDir = resolve(rootDir, 'mobile');

const isWindows = process.platform === 'win32';

function log(prefix, message) {
  const colors = {
    android: '\x1b[32m', // Green
    system: '\x1b[33m',  // Yellow
    reset: '\x1b[0m'
  };
  const color = colors[prefix] || colors.system;
  console.log(`${color}[${prefix}]${colors.reset} ${message}`);
}

function runCommand(cmd, args, { cwd = mobileDir } = {}) {
  return new Promise((resolvePromise, rejectPromise) => {
    const formattedCmd = isWindows && cmd.includes(' ') ? `"${cmd}"` : cmd;
    log('android', `Executing: ${cmd} ${args.join(' ')} (cwd: ${cwd})`);

    const child = spawn(formattedCmd, args, {
      cwd,
      shell: true,
      stdio: 'inherit',
      env: { ...process.env }
    });

    child.on('error', (err) => {
      log('android', `Execution error: ${err.message}`);
      rejectPromise(err);
    });

    child.on('close', (code) => {
      if (code === 0) {
        log('android', `Task completed successfully.`);
        resolvePromise(code);
      } else {
        const error = new Error(`Task failed with exit code ${code}`);
        error.code = code;
        rejectPromise(error);
      }
    });
  });
}

function getGradleCmd() {
  return isWindows ? '.\\gradlew.bat' : './gradlew';
}

const action = process.argv[2] || 'assembleDebug';

async function main() {
  const gradle = getGradleCmd();

  try {
    if (action === 'run') {
      log('android', 'Installing debug build on connected Android device/emulator...');
      await runCommand(gradle, ['installDebug']);
      
      log('android', 'Launching com.switchboard.app/.MainActivity via adb...');
      try {
        await runCommand('adb', ['shell', 'am', 'start', '-n', 'com.switchboard.app/.MainActivity'], { cwd: rootDir });
        log('android', 'App launched successfully on target device.');
      } catch {
        log('android', 'Note: Make sure an Android device or emulator is connected with USB debugging enabled.');
      }
    } else {
      // Direct gradle task execution (assembleDebug, testDebugUnitTest, etc.)
      log('android', `Running Gradle task: ${action}...`);
      await runCommand(gradle, [action]);
    }
  } catch (err) {
    log('android', `Gradle note: ${err.message}`);
    log('android', 'Tip: You can also open the "mobile/" folder in Android Studio to build, test, and debug.');
    process.exit(err.code || 1);
  }
}

main();
