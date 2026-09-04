// Wire types mirroring backend/internal/protocol. Kept in one place so the
// main, preload and renderer layers cannot drift apart.

export interface Display {
  id: string;
  name: string;
  internal: boolean;
  brightness: number;
  minBrightness: number;
  maxBrightness: number;
  hasContrast: boolean;
  contrast: number;
  minContrast: number;
  maxContrast: number;
}

export interface Volume {
  level: number;
  muted: boolean;
}

/** What the host is playing, read from its OS media session. */
export interface MediaState {
  active: boolean;
  status: 'playing' | 'paused' | 'stopped';
  title: string;
  artist: string;
  album: string;
  /** The application the sound is coming from. */
  source: string;
  /** Names the cover art without carrying it; fetched separately, cached by ID. */
  artworkId: string;
}

export interface HostState {
  hostName: string;
  daemonId: string;
  displays: Display[];
  volume: Volume;
  media: MediaState;
  capabilities: string[];
}

export interface PairingInfo {
  daemonId: string;
  hostName: string;
  host: string;
  port: number;
  hostKey: string;
  code: string;
  expiresAt: number;
  qrPayload: string;
}

export interface PairedDevice {
  id: string;
  name: string;
  pairedAt: string;
  lastSeen: string;
  online: boolean;
}

/** Direction, named from the mobile client's point of view. */
export type TransferDirection = 'upload' | 'download';

export type TransferStatus =
  | 'pending'
  | 'active'
  | 'paused'
  | 'completed'
  | 'failed'
  | 'cancelled';

/** One transfer, live or historical. Mirrors protocol.FileProgress. */
export interface FileTransfer {
  transferId: string;
  name: string;
  direction: TransferDirection;
  status: TransferStatus;
  transferred: number;
  size: number;
  /** Smoothed rate; the UI formats it per the user's MB/s or Mb/s preference. */
  bytesPerSec: number;
  error?: string;
  startedAt: number;
  finishedAt?: number;
  deviceId?: string;
  deviceName?: string;
  path?: string;
}

/** Daemon-side preferences the desktop UI owns. */
export interface HostSettings {
  /** Where received files land. Defaults to Downloads/Switchboard. */
  downloadDir: string;
  /** Rate unit shown in the UI: bytes or bits per second. */
  rateUnit: 'MBps' | 'Mbps';
  /** Keep the daemon alive with the window closed. */
  runInBackground: boolean;
}

export interface LocalState {
  host: HostState;
  pairing: PairingInfo;
  devices: PairedDevice[];
  transfers: FileTransfer[];
  settings: HostSettings;
}

export type MediaAction = 'play' | 'pause' | 'toggle' | 'next' | 'prev' | 'stop';

/** The surface the preload bridge exposes on `window.switchboard`. */
export interface SwitchboardBridge {
  getState(): Promise<LocalState>;
  setBrightness(displayId: string, value: number): Promise<Display>;
  setContrast(displayId: string, value: number): Promise<Display>;
  refreshDisplays(): Promise<Display[]>;
  setVolume(level: number, muted: boolean): Promise<Volume>;
  media(action: MediaAction): Promise<void>;
  rotatePairing(): Promise<PairingInfo>;
  revokeDevice(deviceId: string): Promise<void>;
  /** Queues files for a paired device; paths come from the drag-and-drop tray. */
  sendFiles(deviceId: string, paths: string[]): Promise<FileTransfer[]>;
  controlTransfer(transferId: string, action: 'pause' | 'resume' | 'cancel'): Promise<void>;
  updateSettings(patch: Partial<HostSettings>): Promise<HostSettings>;
  /** Opens the OS folder picker and returns the chosen directory, or null. */
  chooseDownloadDir(): Promise<string | null>;
  /** Reveals a completed transfer in the OS file manager. */
  revealTransfer(transferId: string): Promise<void>;
  window: {
    minimize(): Promise<void>;
    toggleMaximize(): Promise<boolean>;
    close(): Promise<void>;
  };
}
