#!/usr/bin/env node

/**
 * Switchboard Unified Runner
 * Orchestrates Go backend, Electron frontend, and Android Gradle builds in one place.
 */

import { spawn } from 'node:child_process';
import { connect } from 'node:net';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import process from 'node:process';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const rootDir = resolve(__dirname, '..');

const isWindows = process.platform === 'win32';
const activeChildren = new Set();
const BACKEND_PORT = Number(process.env.SWITCHBOARD_PORT ?? 9427);

function log(prefix, message) {
  const colors = {
    backend: '\x1b[36m', // Cyan
    frontend: '\x1b[35m', // Magenta
    android: '\x1b[32m', // Green
    gateway: '\x1b[34m', // Blue
    system: '\x1b[33m', // Yellow
    reset: '\x1b[0m'
  };
  const color = colors[prefix] || colors.system;
  console.log(`${color}[${prefix}]${colors.reset} ${message}`);
}

function runProcess(cmd, args, { cwd, name, streamPrefix } = {}) {
  return new Promise((resolvePromise, rejectPromise) => {
    const formattedCmd = isWindows && cmd.includes(' ') ? `"${cmd}"` : cmd;
    log(name || 'system', `Starting: ${cmd} ${args.join(' ')} (cwd: ${cwd || rootDir})`);

    const child = spawn(formattedCmd, args, {
      cwd: cwd || rootDir,
      shell: true,
      stdio: streamPrefix ? ['inherit', 'pipe', 'pipe'] : 'inherit',
      env: { ...process.env }
    });

    activeChildren.add(child);

    if (streamPrefix) {
      child.stdout.on('data', (data) => {
        data.toString().trimEnd().split('\n').forEach(line => {
          log(name, line);
        });
      });
      child.stderr.on('data', (data) => {
        data.toString().trimEnd().split('\n').forEach(line => {
          log(name, line);
        });
      });
    }

    child.on('error', (err) => {
      log(name || 'system', `Error: ${err.message}`);
      activeChildren.delete(child);
      rejectPromise(err);
    });

    child.on('close', (code) => {
      activeChildren.delete(child);
      if (code === 0) {
        log(name || 'system', `Completed successfully.`);
        resolvePromise(code);
      } else {
        const error = new Error(`Process ${name} exited with code ${code}`);
        error.code = code;
        rejectPromise(error);
      }
    });
  });
}

function getAndroidGradleCmd() {
  if (isWindows) {
    return '.\\gradlew.bat';
  }
  return './gradlew';
}

// Signal handling
function shutdown() {
  log('system', 'Shutting down all active processes...');
  for (const child of activeChildren) {
    try {
      if (isWindows) {
        spawn('taskkill', ['/pid', child.pid.toString(), '/f', '/t']);
      } else {
        child.kill('SIGTERM');
      }
    } catch {
      // Ignore already terminated processes
    }
  }
  process.exit(0);
}

process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);

async function spinBackend() {
  return runProcess('go', ['run', './cmd/server'], {
    cwd: resolve(rootDir, 'backend'),
    name: 'backend',
    streamPrefix: true
  });
}

async function buildBackend() {
  log('backend', 'Compiling Go backend daemon...');
  const outPath = isWindows ? '..\\bin\\switchboard.exe' : '../bin/switchboard';
  // -s -w drop the symbol table and DWARF; -trimpath keeps build-machine
  // paths out of the binary. Together they take the daemon from ~18 MB to
  // ~12 MB, and nothing in the shipped product reads either.
  const flags = ['-trimpath', '"-ldflags=-s -w"'];
  return runProcess('go', ['build', ...flags, '-o', outPath, './cmd/server'], {
    cwd: resolve(rootDir, 'backend'),
    name: 'backend'
  });
}

async function spinFrontend() {
  return runProcess('pnpm', ['--filter', 'frontend', 'dev'], {
    cwd: rootDir,
    name: 'frontend',
    streamPrefix: true
  });
}

async function buildFrontend() {
  log('frontend', 'Building Electron frontend...');
  return runProcess('pnpm', ['--filter', 'frontend', 'build'], {
    cwd: rootDir,
    name: 'frontend'
  });
}

async function buildAndroid() {
  log('android', 'Building Android client (assembleDebug)...');
  const gradleCmd = getAndroidGradleCmd();
  try {
    return await runProcess(gradleCmd, ['assembleDebug'], {
      cwd: resolve(rootDir, 'mobile'),
      name: 'android',
      streamPrefix: true
    });
  } catch (err) {
    log('android', `Android build note: ${err.message}. If Android SDK is not in PATH, open mobile/ in Android Studio.`);
  }
}

/** Resolves once something is listening on the port, or after the deadline. */
function waitForPort(port, timeoutMs = 60000) {
  const deadline = Date.now() + timeoutMs;
  return new Promise((resolvePromise) => {
    const attempt = () => {
      const socket = connect({ port, host: '127.0.0.1' });
      socket.once('connect', () => {
        socket.destroy();
        resolvePromise(true);
      });
      socket.once('error', () => {
        socket.destroy();
        if (Date.now() > deadline) resolvePromise(false);
        else setTimeout(attempt, 250);
      });
    };
    attempt();
  });
}

async function spinGateway() {
  return runProcess('node', ['scripts/gateway.mjs'], {
    cwd: rootDir,
    name: 'gateway',
    streamPrefix: true
  });
}

async function devAll() {
  log('system', '=== Spinning Backend Daemon, Frontend UI & Emulator Gateway ===');

  const gateway = spinGateway().catch(err => log('gateway', `Gateway notice: ${err.message}`));
  const backend = spinBackend().catch(err => log('backend', `Backend notice: ${err.message}`));

  // Electron starts the daemon itself when none is answering, so the frontend
  // must not race ahead of `go run` -- two daemons cannot bind the same port.
  const ready = await waitForPort(BACKEND_PORT);
  if (!ready) log('backend', 'Daemon did not come up in time; starting the frontend anyway.');

  const frontend = spinFrontend().catch(err => log('frontend', `Frontend notice: ${err.message}`));
  return Promise.allSettled([backend, frontend, gateway]);
}

async function buildAll() {
  log('system', '=== Building Backend, Frontend, and Android ===');
  try {
    await buildBackend();
  } catch (e) {
    log('backend', `Go build note: ${e.message}`);
  }

  try {
    await buildFrontend();
  } catch (e) {
    log('frontend', `Frontend build note: ${e.message}`);
  }

  try {
    await buildAndroid();
  } catch (e) {
    log('android', `Android build note: ${e.message}`);
  }
  log('system', '=== Build Sequence Complete ===');
}

// Command dispatcher
const action = process.argv[2] || 'dev';

switch (action) {
  case 'dev':
  case 'dev:all':
    devAll();
    break;
  case 'dev:backend':
    spinBackend().catch(() => {});
    break;
  case 'dev:frontend':
    spinFrontend().catch(() => {});
    break;
  case 'gateway':
  case 'dev:gateway':
    spinGateway().catch(() => {});
    break;
  case 'build':
  case 'build:all':
    buildAll();
    break;
  case 'build:backend':
    buildBackend().catch(() => {});
    break;
  case 'build:frontend':
    buildFrontend().catch(() => {});
    break;
  case 'build:android':
  case 'android':
    buildAndroid();
    break;
  case 'all':
    (async () => {
      await buildAll();
      devAll();
    })();
    break;
  default:
    log('system', `Unknown command: ${action}`);
    console.log(`
Usage: node scripts/runner.mjs [action]

Available Actions:
  dev              Spin backend daemon and frontend dev server concurrently
  dev:backend      Spin backend service only (go run)
  dev:frontend     Spin frontend Electron dev server only
  build            Build backend, frontend, and Android client
  build:backend    Compile backend Go binary
  build:frontend   Build frontend bundle
  build:android    Build Android debug APK (assembleDebug)
  all              Build all components then spin dev servers
`);
}
