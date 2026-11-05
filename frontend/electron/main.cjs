// Switchboard desktop shell: runs the Rust daemon as a child over stdio, keeps
// a tray icon, and starts with the computer when asked to.

const { app, BrowserWindow, Tray, Menu, ipcMain, nativeImage, nativeTheme, Notification, dialog, shell } = require('electron')
const path = require('node:path')
const fs = require('node:fs')
const os = require('node:os')
const readline = require('node:readline')
const { spawn } = require('node:child_process')
const { Readable } = require('node:stream')
const { pipeline } = require('node:stream/promises')
const updates = require('./updates.cjs')

const isDev = !!process.env.SWITCHBOARD_DEV
const flavor = process.env.SWITCHBOARD_PROFILE === 'dev' ? 'dev' : 'prod'
if (flavor === 'dev') app.setName('Switchboard Dev')
// The installer's own version drops a pre-release suffix (NSIS takes numbers only);
// the release workflow keeps the full one here.
const version = require('../package.json').releaseVersion || app.getVersion()

if (!app.requestSingleInstanceLock()) {
  app.quit()
  process.exit(0)
}

let win = null
let tray = null
let quitting = false

// --- Settings -------------------------------------------------------------

const settingsFile = () => path.join(app.getPath('userData'), 'settings.json')
const defaults = { closeToTray: true, theme: 'system' }
function readSettings() {
  try {
    return { ...defaults, ...JSON.parse(fs.readFileSync(settingsFile(), 'utf8')) }
  } catch {
    return { ...defaults }
  }
}
let settings = readSettings()
function writeSettings() {
  fs.mkdirSync(path.dirname(settingsFile()), { recursive: true })
  fs.writeFileSync(settingsFile(), JSON.stringify(settings, null, 2))
}

// --- Start with the computer ------------------------------------------------
// Windows keeps login items itself; Linux desktops (GNOME, KDE, XFCE, Cinnamon,
// MATE) read an XDG autostart entry. A login start opens in the tray.

const autostartFile = () =>
  path.join(process.env.XDG_CONFIG_HOME || path.join(os.homedir(), '.config'), 'autostart', flavor === 'dev' ? 'switchboard-dev.desktop' : 'switchboard.desktop')

function autostartEnabled() {
  if (process.platform === 'linux') return fs.existsSync(autostartFile())
  return app.getLoginItemSettings({ args: ['--hidden'] }).openAtLogin
}

function setAutostart(on) {
  if (process.platform !== 'linux') return app.setLoginItemSettings({ openAtLogin: on, args: ['--hidden'] })
  if (!on) return fs.rmSync(autostartFile(), { force: true })
  // Desktop Entry quoting: \ " ` $ escaped inside quotes, and % doubled.
  const quote = (a) => `"${a.replace(/(["\\`$])/g, '\\$1').replace(/%/g, '%%')}"`
  // An AppImage mounts somewhere new each run, so its own file is what starts.
  const command = process.env.APPIMAGE ? [process.env.APPIMAGE] : app.isPackaged ? [process.execPath] : [process.execPath, app.getAppPath()]
  if (flavor === 'dev') command.unshift('env', 'SWITCHBOARD_PROFILE=dev')
  if (process.argv.includes('--no-sandbox')) command.push('--no-sandbox')
  command.push('--hidden')
  fs.mkdirSync(path.dirname(autostartFile()), { recursive: true })
  const lines = [
    '[Desktop Entry]',
    'Type=Application',
    `Name=${app.getName()}`,
    'Comment=Control this computer from your phone',
    `Exec=${command.map(quote).join(' ')}`,
    `Icon=${iconPath()}`,
    'X-GNOME-Autostart-enabled=true',
    'NoDisplay=true',
  ]
  fs.writeFileSync(autostartFile(), `${lines.join('\n')}\n`)
}

const startedHidden = () => process.argv.includes('--hidden')

// --- Daemon -----------------------------------------------------------------

function daemonPath() {
  const exe = process.platform === 'win32' ? 'switchboard.exe' : 'switchboard'
  if (process.env.SWITCHBOARD_BIN) return process.env.SWITCHBOARD_BIN
  if (app.isPackaged) return path.join(process.resourcesPath, 'bin', exe)
  const target = path.join(__dirname, '..', '..', 'target')
  const release = path.join(target, 'release', exe)
  const debug = path.join(target, 'debug', exe)
  // The newer build wins, so `cargo build` alone is enough during development.
  const mtime = (p) => (fs.existsSync(p) ? fs.statSync(p).mtimeMs : 0)
  return mtime(release) >= mtime(debug) ? release : debug
}

const daemon = {
  proc: null,
  nextId: 1,
  pending: new Map(),
  status: null,
  fatal: null,
  restarts: 0,
  asked: new Set(),

  start() {
    const bin = daemonPath()
    if (!fs.existsSync(bin)) {
      this.fatal = `The Switchboard service is missing (${bin}). Reinstall the app.`
      broadcast('fatal', this.fatal)
      return
    }
    const env = { ...process.env, SWITCHBOARD_PROFILE: flavor === 'dev' ? 'dev' : '' }
    this.proc = spawn(bin, ['--stdio'], { env, stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true })
    this.fatal = null
    readline.createInterface({ input: this.proc.stdout }).on('line', (l) => this.onLine(l))
    this.proc.stderr.on('data', (d) => process.stderr.write(`[daemon] ${d}`))
    this.proc.on('exit', (code) => {
      this.proc = null
      for (const p of this.pending.values()) p.reject(new Error('The Switchboard service stopped'))
      this.pending.clear()
      broadcast('daemon', { running: false })
      if (quitting || code === 2) return
      // Back off so a crash loop cannot spin the CPU.
      const delay = Math.min(30000, 1000 * 2 ** this.restarts++)
      setTimeout(() => this.start(), delay)
    })
  },

  onLine(line) {
    let msg
    try {
      msg = JSON.parse(line)
    } catch {
      return
    }
    if (msg.id != null) {
      const p = this.pending.get(msg.id)
      if (!p) return
      this.pending.delete(msg.id)
      clearTimeout(p.timer)
      return msg.ok ? p.resolve(msg.data) : p.reject(new Error(msg.error))
    }
    if (msg.event === 'status' || msg.event === 'ready') {
      // A phone wants to send a file and is waiting for a yes: say so even when the window is hidden.
      const waiting = (msg.data.requests || []).filter((r) => !this.asked.has(r.xfer))
      this.asked = new Set((msg.data.requests || []).map((r) => r.xfer))
      if (waiting.length && Notification.isSupported()) {
        const first = waiting[0]
        const n = new Notification({ title: `${first.device} wants to send a file`, body: waiting.length > 1 ? `${first.name} and ${waiting.length - 1} more` : first.name, icon: iconPath() })
        n.on('click', () => show('/files'))
        n.show()
      }
      this.status = msg.data
      this.restarts = 0
      renderTray()
    }
    if (msg.event === 'fatal') this.fatal = msg.data
    if (msg.event === 'paired' && Notification.isSupported() && !(win && win.isFocused())) {
      new Notification({ title: 'Phone paired', body: `${msg.data.name} can now control this computer.`, icon: iconPath() }).show()
    }
    if (msg.event === 'file_received' && Notification.isSupported() && !(win && win.isFocused())) {
      new Notification({ title: 'File received', body: `${msg.data.name} from ${msg.data.device}`, icon: iconPath() }).show()
    }
    broadcast(msg.event === 'ready' ? 'status' : msg.event, msg.data)
  },

  call(cmd, args = {}) {
    if (!this.proc) return Promise.reject(new Error(this.fatal || 'The Switchboard service is starting'))
    const id = this.nextId++
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id)
        reject(new Error('The Switchboard service did not answer'))
      }, 20000)
      this.pending.set(id, { resolve, reject, timer })
      this.proc.stdin.write(`${JSON.stringify({ id, cmd, ...args })}\n`)
    })
  },

  stop() {
    if (this.proc) this.proc.stdin.end()
  },
}

function broadcast(event, data) {
  if (win && !win.isDestroyed()) win.webContents.send('sb:event', { event, data })
}

// --- Window, theme, tray ----------------------------------------------------

const iconPath = () => path.join(__dirname, '..', 'build', flavor === 'dev' ? 'icon-dev.png' : 'icon.png')

// Matches tokens.css: surface / onSurface.
const overlay = () =>
  nativeTheme.shouldUseDarkColors ? { color: '#131317', symbolColor: '#E4E1E7', height: 44 } : { color: '#FBF8FE', symbolColor: '#1B1B20', height: 44 }

function applyTheme() {
  nativeTheme.themeSource = settings.theme
  if (win && !win.isDestroyed() && process.platform !== 'darwin') {
    win.setTitleBarOverlay(overlay())
    win.setBackgroundColor(overlay().color)
  }
}

function createWindow() {
  win = new BrowserWindow({
    title: app.getName(),
    width: 1180,
    height: 780,
    minWidth: 880,
    minHeight: 600,
    titleBarStyle: 'hidden',
    titleBarOverlay: overlay(),
    backgroundColor: overlay().color,
    icon: iconPath(),
    show: false,
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  })
  win.setMenuBarVisibility(false)
  win.webContents.setWindowOpenHandler(() => ({ action: 'deny' }))
  win.webContents.on('will-navigate', (e) => e.preventDefault())
  if (isDev) win.loadURL('http://localhost:5273')
  else win.loadFile(path.join(__dirname, '..', 'dist', 'index.html'))
  win.once('ready-to-show', () => {
    if (!startedHidden() || !tray) win.show()
  })
  win.on('close', (e) => {
    if (!quitting && settings.closeToTray && tray) {
      e.preventDefault()
      win.hide()
    }
  })
}

function show(route) {
  if (!win || win.isDestroyed()) createWindow()
  win.show()
  win.focus()
  if (route) win.webContents.send('app:navigate', route)
}

function renderTray() {
  if (!tray) return
  const s = daemon.status
  const online = s ? s.devices.filter((d) => d.online) : []
  const line = !s ? 'Starting…' : online.length ? `Connected: ${online.map((d) => d.name).join(', ')}` : 'Ready for your phone'
  tray.setToolTip(`${app.getName()} — ${line}`)
  tray.setContextMenu(
    Menu.buildFromTemplate([
      { label: line, enabled: false },
      { type: 'separator' },
      { label: 'Open Switchboard', click: () => show() },
      { label: 'Pair a phone…', click: () => show('/pair') },
      { type: 'separator' },
      { label: 'Quit', click: () => quit() },
    ]),
  )
}

function createTray() {
  try {
    const img = nativeImage.createFromPath(path.join(__dirname, '..', 'build', 'tray.png')).resize({ width: 22, height: 22 })
    tray = new Tray(img)
    tray.on('click', () => show())
    renderTray()
  } catch (e) {
    console.error('tray unavailable:', e.message)
    tray = null
  }
}

function quit() {
  quitting = true
  daemon.stop()
  app.quit()
}

// --- IPC --------------------------------------------------------------------

const trusted = (e) => e.senderFrame && win && e.sender === win.webContents
const ALLOWED = new Set(['status', 'pair_start', 'pair_cancel', 'forget', 'rename', 'state', 'run', 'history', 'set_remote_input', 'approve', 'set_auto_accept'])

ipcMain.handle('sb:call', (e, cmd, args) => {
  if (!trusted(e) || !ALLOWED.has(cmd)) throw new Error('not allowed')
  return daemon.call(cmd, args)
})
// File paths come from the native dialog (or a real drop, seen by the
// preload) here, never from the page, so a compromised renderer cannot make
// the daemon offer an arbitrary file.
ipcMain.handle('files:send', async (e, device, kind) => {
  if (!trusted(e) || typeof device !== 'string') throw new Error('not allowed')
  const props = kind === 'folder' ? ['openDirectory'] : ['openFile', 'multiSelections']
  const r = await dialog.showOpenDialog(win, { properties: props })
  if (r.canceled || !r.filePaths.length) return 0
  const { sent } = await daemon.call('send_files', { device, paths: r.filePaths })
  return sent
})

// What the user dropped on the window, waiting for them to pick a phone.
let dropped = null
ipcMain.on('files:dropped', (e, paths) => {
  if (!trusted(e) || !Array.isArray(paths) || !paths.length || !paths.every((p) => typeof p === 'string' && p)) return
  dropped = { paths, at: Date.now() }
  broadcast('files_dropped', { count: paths.length })
})
ipcMain.handle('files:send_dropped', async (e, device) => {
  if (!trusted(e) || typeof device !== 'string') throw new Error('not allowed')
  const d = dropped
  dropped = null
  if (!d || Date.now() - d.at > 120000) throw new Error('Nothing was dropped. Drop the files again.')
  const { sent } = await daemon.call('send_files', { device, paths: d.paths })
  return sent
})
ipcMain.handle('files:clear_dropped', (e) => {
  if (trusted(e)) dropped = null
})
ipcMain.handle('files:open_inbox', async (e) => {
  if (!trusted(e)) throw new Error('not allowed')
  const err = await shell.openPath(await daemon.call('inbox'))
  if (err) throw new Error(err)
})
ipcMain.handle('sb:snapshot', (e) => (trusted(e) ? { status: daemon.status, fatal: daemon.fatal, running: !!daemon.proc } : null))
ipcMain.handle('app:settings', (e) =>
  trusted(e)
    ? {
        autostart: autostartEnabled(),
        closeToTray: settings.closeToTray,
        theme: settings.theme,
        version,
        platform: process.platform,
        flavor,
        tray: !!tray,
      }
    : null,
)
ipcMain.handle('app:set', (e, key, value) => {
  if (!trusted(e)) return
  if (key === 'autostart') setAutostart(!!value)
  else if (key === 'closeToTray') settings.closeToTray = !!value
  else if (key === 'theme' && ['system', 'light', 'dark'].includes(value)) {
    settings.theme = value
    applyTheme()
  } else return
  writeSettings()
})

// --- Updates ------------------------------------------------------------------
// GitHub releases on a channel (alpha / beta / stable). An AppImage replaces its
// own file and restarts; a .deb or Windows installer is downloaded and opened.

let offer = null // the last check's answer; install takes it from here, never from the page
const updateChannel = () => (settings.channel in updates.STAGE ? settings.channel : updates.channelOf(version))
// A dev build or an unpackaged run updates by rebuilding it.
const canUpdate = () => app.isPackaged && !isDev && flavor === 'prod' && !!updates.versionKey(version)

async function checkForUpdate() {
  if (!canUpdate()) return (offer = null)
  const res = await fetch(updates.RELEASES, { headers: { Accept: 'application/vnd.github+json' } })
  if (!res.ok) throw new Error(`GitHub answered ${res.status}`)
  // What this install can take: its own AppImage, else the installer for the platform.
  const suffix = process.env.APPIMAGE ? '.AppImage' : process.platform === 'win32' ? '.exe' : '.deb'
  offer = updates.pick(await res.json(), version, updateChannel(), suffix)
  return offer
}

/** Download progress for the page and the taskbar: 0..1, null once it is over. */
function report(fraction) {
  if (!win || win.isDestroyed()) return
  win.setProgressBar(fraction ?? -1)
  broadcast('update_progress', fraction)
}

let installing = false
/** Returns why it failed, or '' (the app restarts, or the installer was opened from Downloads). */
async function installUpdate() {
  if (!offer) return 'No update to install'
  if (installing) return ''
  installing = true
  const appImage = process.env.APPIMAGE
  const target = appImage ?? path.join(app.getPath('downloads'), path.basename(new URL(offer.url).pathname))
  const part = `${target}.part`
  try {
    report(0)
    const res = await fetch(offer.url)
    if (!res.ok) throw new Error(`The download answered ${res.status}`)
    // shortcut: without a Content-Length the bar stays at 0; GitHub's release assets always send one.
    const total = Number(res.headers.get('content-length')) || 0
    let done = 0
    let shown = 0
    await pipeline(
      Readable.fromWeb(res.body),
      async function* (chunks) {
        for await (const chunk of chunks) {
          done += chunk.length
          const percent = total ? Math.floor((done * 100) / total) : 0
          if (percent !== shown) report((shown = percent) / 100)
          yield chunk
        }
      },
      fs.createWriteStream(part, { mode: appImage ? 0o755 : 0o644 }),
    )
    if (appImage) {
      // An error page saved under the right name must not become the app.
      const head = Buffer.alloc(4)
      const fd = fs.openSync(part, 'r')
      fs.readSync(fd, head, 0, 4, 0)
      fs.closeSync(fd)
      if (head.toString('latin1') !== '\x7fELF') throw new Error('The download is not an AppImage')
    }
    // A rename within one directory: a failure before it leaves the old build in place.
    fs.renameSync(part, target)
  } catch (error) {
    fs.rmSync(part, { force: true })
    return `The update failed: ${error.message}`
  } finally {
    installing = false
    report(null)
  }
  if (appImage) {
    app.relaunch({ execPath: target })
    quit()
  } else if (await shell.openPath(target)) {
    // Nothing on this computer opens the installer: show where it is.
    shell.showItemInFolder(target)
  }
  return ''
}

ipcMain.handle('update:info', (e) => (trusted(e) ? { version, channel: updateChannel(), canUpdate: canUpdate(), selfInstall: !!process.env.APPIMAGE, skipped: settings.skipped ?? null, offer: offer && offer.version !== settings.skipped ? offer : null } : null))
ipcMain.handle('update:check', (e) => (trusted(e) ? checkForUpdate() : null))
ipcMain.handle('update:install', (e) => (trusted(e) ? installUpdate() : ''))
// "Later" on the offer at start is remembered for that version.
ipcMain.handle('update:skip', (e) => {
  if (!trusted(e) || !offer) return
  settings.skipped = offer.version
  writeSettings()
})
ipcMain.handle('update:set_channel', (e, channel) => {
  if (!trusted(e) || !(channel in updates.STAGE)) return
  settings.channel = channel
  writeSettings()
})

/** At start: tell the page (and, when it is hidden in the tray, the desktop) about a newer release. */
async function offerUpdateAtStart() {
  const found = await checkForUpdate().catch(() => null)
  if (!found || found.version === settings.skipped) return
  broadcast('update_available', found)
  if ((!win || !win.isVisible()) && Notification.isSupported()) {
    const n = new Notification({ title: 'Switchboard update', body: `Version ${found.version} is ready to install.`, icon: iconPath() })
    n.on('click', () => show('/settings'))
    n.show()
  }
}

// --- Lifecycle --------------------------------------------------------------

app.on('second-instance', () => show())
app.on('before-quit', () => {
  quitting = true
  daemon.stop()
})
app.on('window-all-closed', () => {
  if (!tray) quit()
})

app.whenReady().then(() => {
  nativeTheme.themeSource = settings.theme
  nativeTheme.on('updated', applyTheme)
  daemon.start()
  createTray()
  createWindow()
  // Give the page time to load so it can hear the offer.
  setTimeout(offerUpdateAtStart, 8000)
})
