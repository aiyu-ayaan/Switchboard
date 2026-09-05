// Preload bridge.
//
// The renderer runs with context isolation, no Node access and a strict
// `default-src 'self'` policy, so it makes no network requests at all. Every
// daemon call is forwarded to the main process, which owns the only route to
// the loopback API.
import { contextBridge, ipcRenderer, webUtils } from 'electron';
import type {
  AudioDevice,
  CameraState,
  AudioSession,
  Display,
  FileTransfer,
  HostSettings,
  LocalState,
  MediaAction,
  MediaArtwork,
  PairingInfo,
  SwitchboardBridge,
  Volume
} from '../shared/types';

const call = <T>(route: string, body?: unknown): Promise<T> =>
  ipcRenderer.invoke('daemon:request', route, body) as Promise<T>;

/**
 * Listens on a pushed camera channel for as long as the caller keeps the
 * returned handle.
 *
 * One subscribe count covers both tracks: the renderer decides which to draw
 * from, and holding only the one it happens to prefer would leave the other
 * unavailable the moment the phone turned out not to have a hardware encoder.
 */
const subscribe = <T>(channel: string, handler: (value: T) => void): (() => void) => {
  const listener = (_event: unknown, value: T) => handler(value);
  ipcRenderer.on(channel, listener);
  ipcRenderer.send('camera:subscribe');
  return () => {
    ipcRenderer.removeListener(channel, listener);
    ipcRenderer.send('camera:unsubscribe');
  };
};

const bridge: SwitchboardBridge = {
  getState: () => call<LocalState>('/state'),
  setBrightness: (displayId, value) => call<Display>('/display/brightness', { displayId, value }),
  setContrast: (displayId, value) => call<Display>('/display/contrast', { displayId, value }),
  refreshDisplays: () => call<Display[]>('/displays/refresh', {}),
  setVolume: (level, muted) => call<Volume>('/volume', { level, muted }),
  setSessionVolume: (sessionId, level, muted) =>
    call<AudioSession[]>('/mixer', { sessionId, level, muted }),
  setAudioOutput: (deviceId) => call<AudioDevice[]>('/audio/output', { deviceId }),
  media: async (action: MediaAction) => {
    await call('/media', { action });
  },
  getMediaArtwork: () => call<MediaArtwork>('/media/artwork'),
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
  camera: {
    getState: () => call<CameraState>('/camera/state'),
    start: (deviceId, settings) => call<CameraState>('/camera/start', { deviceId, settings }),
    stop: () => call<CameraState>('/camera/stop', {}),
    control: (settings) => call<CameraState>('/camera/control', settings),
    getVcamStatus: () => ipcRenderer.invoke('camera:vcamStatus'),
    installVcam: () => ipcRenderer.invoke('camera:installVcam'),
    uninstallVcam: () => ipcRenderer.invoke('camera:uninstallVcam'),
    // Frames are pushed rather than requested: the main process holds the
    // streams open against the daemon, so the renderer never waits and never
    // polls. Subscribing to either track starts both, since the renderer picks
    // between them by which one is actually arriving.
    onFrame: (handler) => subscribe('camera:frame', handler),
    onVideo: (handler) => subscribe('camera:video', handler)
  },
  window: {
    minimize: () => ipcRenderer.invoke('window:minimize'),
    toggleMaximize: () => ipcRenderer.invoke('window:toggleMaximize'),
    close: () => ipcRenderer.invoke('window:close')
  }
};

contextBridge.exposeInMainWorld('switchboard', bridge);
