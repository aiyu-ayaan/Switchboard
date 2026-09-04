// Preload bridge.
//
// The renderer runs with context isolation, no Node access and a strict
// `default-src 'self'` policy, so it makes no network requests at all. Every
// daemon call is forwarded to the main process, which owns the only route to
// the loopback API.
import { contextBridge, ipcRenderer, webUtils } from 'electron';
import type {
  Display,
  FileTransfer,
  HostSettings,
  LocalState,
  MediaAction,
  PairingInfo,
  SwitchboardBridge,
  Volume
} from '../shared/types';

const call = <T>(route: string, body?: unknown): Promise<T> =>
  ipcRenderer.invoke('daemon:request', route, body) as Promise<T>;

const bridge: SwitchboardBridge = {
  getState: () => call<LocalState>('/state'),
  setBrightness: (displayId, value) => call<Display>('/display/brightness', { displayId, value }),
  setContrast: (displayId, value) => call<Display>('/display/contrast', { displayId, value }),
  refreshDisplays: () => call<Display[]>('/displays/refresh', {}),
  setVolume: (level, muted) => call<Volume>('/volume', { level, muted }),
  media: async (action: MediaAction) => {
    await call('/media', { action });
  },
  rotatePairing: () => call<PairingInfo>('/pairing/rotate', {}),
  revokeDevice: async (deviceId) => {
    await call('/devices/revoke', { deviceId });
  },
  // Synchronous and Node-side on purpose: this is the only way back from a
  // `File` handed to the renderer by a drop or a picker to a path the daemon
  // can open.
  pathForFile: (file) => webUtils.getPathForFile(file),
  sendFiles: (deviceId, paths) => call<FileTransfer[]>('/files/send', { deviceId, paths }),
  controlTransfer: async (transferId, action) => {
    await call('/files/control', { transferId, action });
  },
  updateSettings: (patch) => call<HostSettings>('/settings', patch),
  chooseDownloadDir: () => ipcRenderer.invoke('dialog:downloadDir'),
  revealTransfer: (transferId) => ipcRenderer.invoke('transfer:reveal', transferId),
  window: {
    minimize: () => ipcRenderer.invoke('window:minimize'),
    toggleMaximize: () => ipcRenderer.invoke('window:toggleMaximize'),
    close: () => ipcRenderer.invoke('window:close')
  }
};

contextBridge.exposeInMainWorld('switchboard', bridge);
