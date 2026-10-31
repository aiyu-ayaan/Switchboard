// The renderer's whole view of the system: a few named calls, no Node.
const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('switchboard', {
  call: (cmd, args) => ipcRenderer.invoke('sb:call', cmd, args),
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
