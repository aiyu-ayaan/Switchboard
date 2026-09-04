// Preload bridge.
//
// The renderer runs with context isolation, no Node access and a strict
// `default-src 'self'` policy, so it makes no network requests at all. Every
// daemon call is forwarded to the main process, which owns the only route to
// the loopback API.
import { contextBridge, ipcRenderer } from 'electron';
import type {
  Display,
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
  window: {
    minimize: () => ipcRenderer.invoke('window:minimize'),
    toggleMaximize: () => ipcRenderer.invoke('window:toggleMaximize'),
    close: () => ipcRenderer.invoke('window:close')
  }
};

contextBridge.exposeInMainWorld('switchboard', bridge);
