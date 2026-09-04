const { app, BrowserWindow, ipcMain } = require('electron');
const path = require('path');
const fs = require('fs');

const DAEMON_PORT = Number(process.env.SWITCHBOARD_PORT ?? 9427);

async function daemonFetch(route, body) {
  const res = await fetch(`http://127.0.0.1:${DAEMON_PORT}/local${route}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(10000)
  });
  if (!res.ok) {
    const detail = await res.json().catch(() => null);
    throw new Error(detail?.error ?? `request failed: ${res.status} ${res.statusText}`);
  }
  return res.json();
}

app.whenReady().then(async () => {
  ipcMain.handle('window:minimize', () => {});
  ipcMain.handle('window:toggleMaximize', () => false);
  ipcMain.handle('window:close', () => {});
  ipcMain.handle('dialog:downloadDir', async () => null);
  ipcMain.handle('transfer:reveal', async () => {});

  ipcMain.handle('daemon:request', async (_event, route, body) => {
    return await daemonFetch(route, body);
  });

  const win = new BrowserWindow({
    width: 1120,
    height: 720,
    show: false,
    frame: false,
    backgroundColor: '#1a1b26',
    webPreferences: {
      preload: path.resolve(__dirname, '../frontend/dist/preload/index.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false
    }
  });

  const htmlPath = path.resolve(__dirname, '../frontend/dist/renderer/index.html');
  await win.loadFile(htmlPath);

  // Wait for initial render and data fetch
  await new Promise((r) => setTimeout(r, 2000));

  const outDir = path.resolve(__dirname, '../docs/images');
  if (!fs.existsSync(outDir)) {
    fs.mkdirSync(outDir, { recursive: true });
  }

  // 1. Displays View
  console.log('Capturing Displays View...');
  let img = await win.webContents.capturePage();
  fs.writeFileSync(path.join(outDir, 'screenshot_desktop_displays.png'), img.toPNG());
  fs.writeFileSync(path.join(outDir, 'screenshot_desktop_main.png'), img.toPNG());

  // 2. Audio View
  console.log('Capturing Audio View...');
  await win.webContents.executeJavaScript(`document.querySelector('button[title="Audio and media"]')?.click()`);
  await new Promise((r) => setTimeout(r, 600));
  img = await win.webContents.capturePage();
  fs.writeFileSync(path.join(outDir, 'screenshot_desktop_audio.png'), img.toPNG());

  // 3. Files View
  console.log('Capturing Files View...');
  await win.webContents.executeJavaScript(`document.querySelector('button[title="File transfers"]')?.click()`);
  await new Promise((r) => setTimeout(r, 600));
  img = await win.webContents.capturePage();
  fs.writeFileSync(path.join(outDir, 'screenshot_desktop_files.png'), img.toPNG());

  // 4. Paired Devices View (Pairing / QR)
  console.log('Capturing Devices View...');
  await win.webContents.executeJavaScript(`document.querySelector('button[title="Paired devices"]')?.click()`);
  await new Promise((r) => setTimeout(r, 600));
  img = await win.webContents.capturePage();
  fs.writeFileSync(path.join(outDir, 'screenshot_desktop_devices.png'), img.toPNG());

  // 5. Settings View
  console.log('Capturing Settings View...');
  await win.webContents.executeJavaScript(`document.querySelector('button[title="Settings"]')?.click()`);
  await new Promise((r) => setTimeout(r, 600));
  img = await win.webContents.capturePage();
  fs.writeFileSync(path.join(outDir, 'screenshot_desktop_settings.png'), img.toPNG());

  console.log('All desktop screenshots captured successfully!');
  win.destroy();
  app.quit();
  process.exit(0);
});
