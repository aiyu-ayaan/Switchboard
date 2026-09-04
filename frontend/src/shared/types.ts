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

export interface LocalState {
  host: HostState;
  pairing: PairingInfo;
  devices: PairedDevice[];
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
  window: {
    minimize(): Promise<void>;
    toggleMaximize(): Promise<boolean>;
    close(): Promise<void>;
  };
}
