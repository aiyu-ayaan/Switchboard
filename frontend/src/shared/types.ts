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
  power?: boolean;
}

export interface DisplayPowerSet {
  displayId: string;
  on: boolean;
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
  mic?: Volume;
  /** Empty on a host without per-application control; see `capabilities`. */
  mixer: AudioSession[];
  /** Endpoints the host can route sound to; exactly one carries `default`. */
  outputs: AudioDevice[];
  inputs?: AudioDevice[];
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
  /** Automatically launch Switchboard when the computer starts up. */
  autoStart: boolean;
}

export interface LocalState {
  host: HostState;
  pairing: PairingInfo;
  devices: PairedDevice[];
  transfers: FileTransfer[];
  settings: HostSettings;
  /** Phones currently watching this desktop's screen. */
  screenViewers?: ScreenViewer[];
}

export interface ScreenViewer {
  deviceId: string;
  since: number;
  settings: { maxWidth: number; quality: number; fps: number; display?: string };
  fps: number;
}

/** A paired phone sharing its own screen with this desktop. */
export interface MirrorSettings {
  maxSize: number;
  bitrate: number;
  fps: number;
}

export interface MirrorState {
  streaming: boolean;
  /** Android's own capture prompt is up on the phone, waiting for the user. */
  awaitingConsent?: boolean;
  width?: number;
  height?: number;
  /** The phone's accessibility service is on, so clicks act on it. */
  control: boolean;
  /** The package in front on the phone. */
  foreground?: string;
  settings: MirrorSettings;
  error?: string;
  deviceId?: string;
  fps?: number;
  bytesPerSec?: number;
}

export interface MirrorApp {
  package: string;
  label: string;
  /** Base64 PNG. */
  icon?: string;
}

export type MirrorNav =
  | 'back'
  | 'home'
  | 'recents'
  | 'notifications'
  | 'quickSettings'
  | 'lock'
  | 'screenshot';

export interface MirrorPoint {
  x: number;
  y: number;
  t?: number;
}

export interface MirrorInput {
  kind: 'tap' | 'longPress' | 'swipe' | 'scroll' | 'text' | 'key' | 'nav';
  points?: MirrorPoint[];
  duration?: number;
  dx?: number;
  dy?: number;
  text?: string;
  key?: string;
  nav?: MirrorNav;
}

export type MediaAction = 'play' | 'pause' | 'toggle' | 'next' | 'prev' | 'stop';

export type PowerAction = 'display_off' | 'sleep' | 'shutdown' | 'abort_shutdown';
export interface PowerCommand { action: PowerAction; seconds?: number; }
export interface InputText { text: string; }
export interface ClipboardSet { text: string; }

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
  setDisplayPower: (displayId: string, on: boolean) => Promise<Display>;
  refreshDisplays(): Promise<Display[]>;
  setVolume(level: number, muted: boolean): Promise<Volume>;
  setMicVolume: (level: number, muted: boolean) => Promise<Volume>;
  setSessionVolume(sessionId: string, level: number, muted: boolean): Promise<AudioSession[]>;
  /** Routes host audio to one endpoint; resolves with the refreshed list. */
  setAudioOutput(deviceId: string): Promise<AudioDevice[]>;
  setAudioInput: (deviceId: string) => Promise<AudioDevice[]>;
  media(action: MediaAction): Promise<void>;
  getMediaArtwork(): Promise<MediaArtwork>;
  lockSystem(): Promise<{ status: string }>;
  power: (action: PowerAction, seconds?: number) => Promise<{ status: string }>;
  inputText: (text: string) => Promise<{ status: string }>;
  setClipboard: (text: string) => Promise<{ status: string }>;
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
  /**
   * The transfer list on its own.
   *
   * Progress needs polling several times a second to read as movement rather
   * than as a bar that jumps; `getState` carries a DDC/CI display probe and an
   * audio-session enumeration that have no business running that often.
   */
  getTransfers(): Promise<FileTransfer[]>;
  updateSettings(patch: Partial<HostSettings>): Promise<HostSettings>;
  /** Opens the OS folder picker and returns the chosen directory, or null. */
  chooseDownloadDir(): Promise<string | null>;
  /** Reveals a completed transfer in the OS file manager. */
  revealTransfer(transferId: string): Promise<void>;
  /**
   * The Explorer "Send to Switchboard" picker.
   *
   * Deliberately asymmetric: the window is told the file *names* so it can
   * show them, and hands back only the device that was clicked. The paths
   * actually sent are the ones the main process collected from Explorer, so
   * this surface cannot be turned into "read any file on disk".
   */
  sendPicker: {
    files(): Promise<string[]>;
    devices(): Promise<PairedDevice[]>;
    send(deviceId: string): Promise<void>;
    cancel(): Promise<void>;
    /** Fires when a further right-click adds files to an open picker. */
    onFiles(handler: (files: string[]) => void): () => void;
  };
  /** This desktop's screen as phones see it, and phones' screens shown here. */
  screen: {
    viewers(): Promise<ScreenViewer[]>;
    /** Ends one phone's view of this screen, or every phone's when omitted. */
    stop(deviceId?: string): Promise<ScreenViewer[]>;
  };
  phone: {
    getState(): Promise<MirrorState>;
    start(deviceId: string): Promise<MirrorState>;
    stop(): Promise<MirrorState>;
    input(input: MirrorInput): Promise<void>;
    apps(): Promise<{ deviceId: string; apps: MirrorApp[] }>;
    refreshApps(deviceId?: string): Promise<void>;
    launch(pkg: string, deviceId?: string): Promise<void>;
    /** Opens a phone-sized window for one app, or for the whole phone. */
    openWindow(pkg: string, label: string): Promise<void>;
    /** Packages with a window open ('' is the whole phone), and changes to them. */
    windows(): Promise<string[]>;
    onWindows(handler: (packages: string[]) => void): () => void;
    focusWindows(): Promise<void>;
    closeWindows(): Promise<void>;
    onVideo(handler: (unit: CameraVideoUnit) => void): () => void;
  };
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
  deck: {
    get(): Promise<DeckConfig>;
    set(config: DeckConfig): Promise<DeckConfig>;
    action(req: DeckActionRequest): Promise<{ status: string }>;
    apps(): Promise<InstalledApp[]>;
    /** Resolves a platform-native icon for an application returned by apps(). */
    appIcon(path: string): Promise<string>;
    /** Resolves a website's own icon as a data URI, or '' if it offers none. */
    siteIcon(url: string): Promise<string>;
  };
  window: {
    minimize(): Promise<void>;
    toggleMaximize(): Promise<boolean>;
    close(): Promise<void>;
  };
  updates: {
    status(): Promise<UpdateStatus>;
    /** Checks now, ignoring the timer, and downloads anything it finds. */
    check(): Promise<UpdateStatus>;
    /** Runs the downloaded installer silently and quits. */
    install(): Promise<{ started: boolean; error?: string }>;
    setChannel(channel: UpdateChannel): Promise<UpdateStatus>;
    channels(): Promise<UpdateChannel[]>;
    /** Progress and phase changes, pushed from the main process. */
    onStatus(handler: (status: UpdateStatus) => void): () => void;
  };
  openExternal(url: string): Promise<void>;
}

/** Which builds this install is willing to be offered. Cumulative: beta takes stable too. */
export type UpdateChannel = 'stable' | 'beta' | 'alpha';

export interface UpdateRelease {
  version: string;
  name: string;
  /** The GitHub release body, as markdown. */
  notes: string;
  publishedAt: string;
  size: number;
}

export interface UpdateStatus {
  phase: 'idle' | 'checking' | 'uptodate' | 'downloading' | 'ready' | 'error';
  channel: UpdateChannel;
  installedVersion: string;
  /** False for a development run, which is never offered an update. */
  packaged?: boolean;
  update?: UpdateRelease;
  /** 0..1 while downloading, or -1 when the total length is unknown. */
  progress?: number;
  /** Where the installer landed, once `phase` is `ready`. */
  file?: string;
  lastCheckedAt?: number;
  error?: string;
}

export interface InstalledApp {
  name: string;
  path: string;
  icon?: string;
}

// ---- Stream Deck Neo Types ----

export interface DeckAction {
  type: 'url' | 'hotkey' | 'media' | 'system' | 'app' | 'page';
  value: string;
}

export interface DeckKey {
  index: number;
  title: string;
  icon: string;
  bgColor?: string;
  iconColor?: string;
  badge?: string;
  /** data: URI of the launch target's real desktop icon, extracted by the host shell. */
  iconData?: string;
  action: DeckAction;
}

export interface DeckInfobar {
  mode: 'clock' | 'media' | 'page' | 'text';
  customText: string;
  format?: string;
}

export interface DeckPage {
  id: string;
  name: string;
  keys: DeckKey[];
}

export interface DeckConfig {
  activePage: number;
  infobar: DeckInfobar;
  pages: DeckPage[];
}

export interface DeckActionRequest {
  pageId?: string;
  keyIndex: number;
  action: DeckAction;
}

