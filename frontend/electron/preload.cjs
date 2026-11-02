// The renderer's whole view of the system: a few named calls, no Node.
const { contextBridge, ipcRenderer, webUtils } = require('electron')

// A drop is read here, in the preload, and only when the user really did it
// (`isTrusted`): a script in the page cannot fake one to name arbitrary paths.
window.addEventListener('dragover', (e) => e.preventDefault())
window.addEventListener('drop', (e) => {
  e.preventDefault()
  if (!e.isTrusted || !e.dataTransfer) return
  const paths = [...e.dataTransfer.files].map((f) => webUtils.getPathForFile(f)).filter(Boolean)
  if (paths.length) ipcRenderer.send('files:dropped', paths)
})

contextBridge.exposeInMainWorld('switchboard', {
  call: (cmd, args) => ipcRenderer.invoke('sb:call', cmd, args),
  sendFiles: (device, kind) => ipcRenderer.invoke('files:send', device, kind),
  sendDropped: (device) => ipcRenderer.invoke('files:send_dropped', device),
  clearDropped: () => ipcRenderer.invoke('files:clear_dropped'),
  openInbox: () => ipcRenderer.invoke('files:open_inbox'),
  snapshot: () => ipcRenderer.invoke('sb:snapshot'),
  settings: () => ipcRenderer.invoke('app:settings'),
  set: (key, value) => ipcRenderer.invoke('app:set', key, value),
  onEvent: (fn) => {
    const h = (_e, msg) => fn(msg)
    ipcRenderer.on('sb:event', h)
    return () => ipcRenderer.removeListener('sb:event', h)
  },
  onNavigate: (fn) => {
    const h = (_e, route) => fn(route)
    ipcRenderer.on('app:navigate', h)
    return () => ipcRenderer.removeListener('app:navigate', h)
  },
})
