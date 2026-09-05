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

export interface MediaArtwork {
  artworkId: string;
  mimeType: string;
  data: string;
}

/**
 * One program's entry in the host mixer. `id` is the OS session identifier
 * rather than the process — a browser spans several processes behind a single
 * mixer entry — and `active` is false for a session that holds its entry
 * without currently playing.
 */
export interface AudioSession {
  id: string;
  name: string;
  pid: number;
  level: number;
  muted: boolean;
  active: boolean;
}

/**
 * One output endpoint the host can play through. `id` is the OS endpoint
 * identifier, which survives a reboot and a re-plug — unlike the position in
 * the list, which does not.
 */
export interface AudioDevice {
  id: string;
  name: string;
  default: boolean;
}

export interface HostState {
  hostName: string;
  daemonId: string;
  displays: Display[];
  volume: Volume;
  /** Empty on a host without per-application control; see `capabilities`. */
  mixer: AudioSession[];
  /** Endpoints the host can route sound to; exactly one carries `default`. */
  outputs: AudioDevice[];
  media: MediaState;
  capabilities: string[];
  locked?: boolean;
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
/** The phone camera's full control surface, mirrored from the Go protocol. */
export interface CameraSettings {
  facing: 'back' | 'front';
  quality: 'full' | 'balanced' | 'low';
  fps: number;
  rotation: number;
  mirror: boolean;
  /** Normalised 0-1 across the lens's own range, not a ratio. */
  zoom: number;
  torch: boolean;
  autoFocus: boolean;
  focusDistance: number;
  autoExposure: boolean;
  exposure: number;
  whiteBalance: string;
  autoFraming: boolean;
}

/**
 * What the phone reports back: the settings in force plus what its lens can
 * actually honour, so a control the hardware lacks is hidden rather than shown
 * dead.
 */
export interface CameraState {
  streaming: boolean;
  deviceId?: string;
  settings: CameraSettings;
  width: number;
  height: number;
  maxZoomRatio: number;
  minExposure: number;
  maxExposure: number;
  hasTorch: boolean;
  hasManualFocus: boolean;
  hasManualExposure: boolean;
  hasWhiteBalance: boolean;
  /** The presets this lens honours; anything else is ignored by the camera. */
  whiteBalanceModes?: string[];
  hasAutoFraming: boolean;
  hasFrontCamera: boolean;
  /** Measured by the daemon from what the link actually delivered. */
  fps: number;
  bytesPerSec: number;
  /** Which track is feeding the view: `h264` off the phone's hardware
   *  encoder, or `jpeg` when the phone had none. */
  codec?: string;
  vcamInstalled?: boolean;
  error?: string;
}

/**
 * One H.264 access unit from the phone, with what the display still has to do
 * to it. The camera writes into the phone's hardware encoder directly, so
 * rotation and mirroring never got baked into the pixels — which is the point:
 * a canvas transform costs nothing and a per-pixel loop on the phone cost most
 * of the frame rate.
 */
export interface CameraVideoUnit {
  data: ArrayBuffer;
  /** True when this unit decodes on its own. A decoder joining mid-stream must
   *  discard everything before the first one. */
  key: boolean;
  rotation: number;
  mirror: boolean;
  width: number;
  height: number;
  /** The encoder's own monotonic capture time, in microseconds. */
  timestamp: number;
}

export interface SwitchboardBridge {
  getState(): Promise<LocalState>;
  setBrightness(displayId: string, value: number): Promise<Display>;
  setContrast(displayId: string, value: number): Promise<Display>;
  refreshDisplays(): Promise<Display[]>;
  setVolume(level: number, muted: boolean): Promise<Volume>;
  setSessionVolume(sessionId: string, level: number, muted: boolean): Promise<AudioSession[]>;
  /** Routes host audio to one endpoint; resolves with the refreshed list. */
  setAudioOutput(deviceId: string): Promise<AudioDevice[]>;
  media(action: MediaAction): Promise<void>;
  getMediaArtwork(): Promise<MediaArtwork>;
  lockSystem(): Promise<{ status: string }>;
  rotatePairing(): Promise<PairingInfo>;
  revokeDevice(deviceId: string): Promise<void>;
  /**
   * Resolves a dropped or picked `File` to its absolute path.
   *
   * Chromium stopped exposing `File.path` to the renderer, and the renderer has
   * no Node access, so the preload layer answers this with `webUtils`.
   */
  pathForFile(file: File): string;
  /** Queues files for a paired device; paths come from the drag-and-drop tray. */
  sendFiles(deviceId: string, paths: string[]): Promise<FileTransfer[]>;
  controlTransfer(transferId: string, action: 'pause' | 'resume' | 'cancel'): Promise<void>;
  updateSettings(patch: Partial<HostSettings>): Promise<HostSettings>;
  /** Opens the OS folder picker and returns the chosen directory, or null. */
  chooseDownloadDir(): Promise<string | null>;
  /** Reveals a completed transfer in the OS file manager. */
  revealTransfer(transferId: string): Promise<void>;
  camera: {
    getState(): Promise<CameraState>;
    start(deviceId: string, settings?: CameraSettings): Promise<CameraState>;
    stop(): Promise<CameraState>;
    control(settings: CameraSettings): Promise<CameraState>;
    getVcamStatus(): Promise<{ installed: boolean; deviceName: string }>;
    installVcam(): Promise<{ success: boolean; error?: string }>;
    uninstallVcam(): Promise<{ success: boolean; error?: string }>;
    /**
     * Subscribes to the live frames.
     *
     * The renderer makes no network requests of its own, so frames arrive over
     * IPC as raw JPEG bytes and are wrapped in a blob URL here. Returns an
     * unsubscribe function.
     */
    onFrame(handler: (jpeg: ArrayBuffer) => void): () => void;
    onVideo(handler: (unit: CameraVideoUnit) => void): () => void;
  };
  window: {
    minimize(): Promise<void>;
    toggleMaximize(): Promise<boolean>;
    close(): Promise<void>;
  };
}
