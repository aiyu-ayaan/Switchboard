import { useCallback, useEffect, useRef, useState } from 'react';
import type { CameraSettings, CameraState, LocalState } from '../../shared/types';

const QUALITIES: Array<[CameraSettings['quality'], string]> = [
  ['full', 'Full (1080p)'],
  ['balanced', '720p'],
  ['low', '480p']
];

const WHITE_BALANCE = ['auto', 'incandescent', 'fluorescent', 'daylight', 'cloudy', 'shade'];

const STREAM_URL = 'http://127.0.0.1:9427/local/camera/stream';

/** How long the JPEG track stands aside after the last H.264 access unit. */
const VIDEO_TRACK_GRACE_MS = 2000;

/**
 * The `avc1.PPCCLL` string for an Annex-B stream, read out of its first SPS.
 *
 * `VideoDecoder.configure` needs a codec string, and profile and level differ
 * between phones: a guess that disagrees with the bytes configures cleanly and
 * then decodes nothing, which looks exactly like a camera that is not sending.
 * The three bytes after the SPS header are profile_idc, the constraint flags
 * and level_idc, which is precisely what the string encodes.
 */
const codecFromAnnexB = (data: Uint8Array): string | null => {
  for (let i = 0; i + 5 < data.length; i += 1) {
    if (data[i] !== 0 || data[i + 1] !== 0) continue;
    const start = data[i + 2] === 1 ? i + 3 : data[i + 2] === 0 && data[i + 3] === 1 ? i + 4 : -1;
    if (start < 0) continue;
    // NAL type 7 is the sequence parameter set.
    if ((data[start] & 0x1f) === 7 && start + 3 < data.length) {
      const hex = [data[start + 1], data[start + 2], data[start + 3]]
        .map((byte) => byte.toString(16).padStart(2, '0'))
        .join('');
      return `avc1.${hex}`;
    }
    i = start - 1;
  }
  return null;
};

/**
 * Live view and controls for a phone acting as a webcam.
 *
 * Frames arrive over IPC rather than through an `<img>` or a `<video>` pointed
 * at the daemon: the renderer runs with `default-src 'self'` and makes no
 * network requests of its own, and relaxing that for a video feed would be a
 * poor trade.
 *
 * The phone sends two tracks and this prefers H.264, decoded by `VideoDecoder`
 * on the GPU. That is what makes the high frame rates the panel offers real:
 * the JPEG track costs the phone a software compression per picture and could
 * not keep up, so a 60 fps request used to arrive as about 14. The JPEG track
 * is still read, and takes over whenever the video track has gone quiet — a
 * phone with no hardware encoder has nothing else to send.
 *
 * Either way the picture is painted to a canvas rather than swapped into an
 * `<img src>`. React state is the wrong home for sixty arrivals a second —
 * every frame re-ran this whole component and its control panel — and a blob
 * URL per frame made the browser start a fresh async decode each time, so
 * frames could paint out of order or not at all.
 *
 * Rotation and mirroring are applied here, on the video track. The camera
 * writes straight into the phone's hardware encoder, so there is no pass on
 * that side in which to bake them into the pixels — and a canvas transform
 * costs nothing where the per-pixel loops it replaced cost most of the frame
 * rate. The JPEG track still arrives with them applied, because the virtual
 * camera and OBS have nowhere to put a transform.
 */
export const CameraView = ({ state }: { state: LocalState }) => {
  const [camera, setCamera] = useState<CameraState | null>(null);
  const [busy, setBusy] = useState(false);
  const [vcamBusy, setVcamBusy] = useState(false);
  const [vcamMessage, setVcamMessage] = useState<string | null>(null);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);

  const online = state.devices.filter((d) => d.online);
  const [deviceId, setDeviceId] = useState('');
  const selected = deviceId || online[0]?.id || '';

  const refresh = useCallback(async () => {
    setCamera(await window.switchboard.camera.getState());
  }, []);

  const handleInstallVcam = async () => {
    setVcamBusy(true);
    setVcamMessage(null);
    try {
      const res = await window.switchboard.camera.installVcam();
      if (!res.success && res.error) {
        setVcamMessage(`Installation failed: ${res.error}`);
      } else {
        setVcamMessage('Installed! "Switchboard Camera" is now ready.');
        await refresh();
      }
    } finally {
      setVcamBusy(false);
    }
  };

  const handleUninstallVcam = async () => {
    setVcamBusy(true);
    setVcamMessage(null);
    try {
      const res = await window.switchboard.camera.uninstallVcam();
      if (!res.success && res.error) {
        setVcamMessage(`Uninstallation failed: ${res.error}`);
      } else {
        setVcamMessage('Uninstalled virtual camera.');
        await refresh();
      }
    } finally {
      setVcamBusy(false);
    }
  };

  useEffect(() => {
    void refresh();
    const timer = setInterval(refresh, 1000);
    return () => clearInterval(timer);
  }, [refresh]);

  useEffect(() => {
    let disposed = false;

    /**
     * Draws one frame, applying whatever orientation the sender left to us.
     *
     * The canvas is sized to the *displayed* picture, so a portrait phone at
     * 90 degrees produces a portrait canvas rather than a landscape one with
     * the picture spilling out of it.
     */
    const paint = (
      source: CanvasImageSource,
      width: number,
      height: number,
      rotation: number,
      mirror: boolean
    ) => {
      const canvas = canvasRef.current;
      if (!canvas || disposed || !width || !height) return;

      const swapped = rotation === 90 || rotation === 270;
      const displayWidth = swapped ? height : width;
      const displayHeight = swapped ? width : height;
      if (canvas.width !== displayWidth || canvas.height !== displayHeight) {
        canvas.width = displayWidth;
        canvas.height = displayHeight;
      }

      const ctx = canvas.getContext('2d');
      if (!ctx) return;
      ctx.save();
      ctx.translate(displayWidth / 2, displayHeight / 2);
      // Called outermost-first: the last transform set is the first applied to
      // the source, so this is mirror-of-rotated, matching what the phone does
      // to the JPEG track in software.
      if (mirror) ctx.scale(-1, 1);
      if (rotation) ctx.rotate((rotation * Math.PI) / 180);
      ctx.drawImage(source, -width / 2, -height / 2, width, height);
      ctx.restore();
    };

    // ---- H.264 ----

    let decoder: VideoDecoder | null = null;
    let configured = false;
    // A decoder cannot start on a delta frame, and neither can it resume on one
    // after an error or a drop.
    let needsKey = true;
    let orientation = { rotation: 0, mirror: false };
    let lastUnitAt = 0;

    const resetDecoder = () => {
      needsKey = true;
      configured = false;
      if (decoder && decoder.state !== 'closed') {
        try {
          decoder.close();
        } catch {
          // Already torn down by the error that got us here.
        }
      }
      decoder = null;
    };

    const stopVideo = window.switchboard.camera.onVideo((unit) => {
      if (disposed || typeof VideoDecoder === 'undefined') return;
      lastUnitAt = Date.now();

      if (unit.key) needsKey = false;
      else if (needsKey) return;

      if (!decoder) {
        decoder = new VideoDecoder({
          output: (frame) => {
            try {
              // Orientation is read at paint time rather than paired with the
              // chunk that produced this frame: decoding is asynchronous, and
              // the settings behind it change at human speed, so the worst
              // this costs is one frame of lag after a rotate.
              paint(
                frame,
                frame.displayWidth,
                frame.displayHeight,
                orientation.rotation,
                orientation.mirror
              );
            } finally {
              // VideoFrames hold GPU memory outside the JS heap; at 60fps,
              // leaving them to the collector exhausts the renderer in
              // seconds.
              frame.close();
            }
          },
          error: resetDecoder
        });
      }

      const bytes = new Uint8Array(unit.data);
      if (!configured) {
        // Taken from the stream's own SPS rather than assumed: encoders differ
        // on profile and level, and a codec string that disagrees with the
        // bytes is a decoder that configures and then produces nothing.
        decoder.configure({
          codec: codecFromAnnexB(bytes) ?? 'avc1.42E01E',
          optimizeForLatency: true
        });
        configured = true;
      }

      // A machine that cannot keep up must fall behind by dropping, not by
      // queueing: a backlog here is latency that only ever grows. Dropping a
      // delta corrupts the picture until the next keyframe, which is why the
      // decoder is told to wait for one.
      if (!unit.key && decoder.decodeQueueSize > 4) {
        needsKey = true;
        return;
      }

      orientation = { rotation: unit.rotation, mirror: unit.mirror };
      try {
        decoder.decode(
          new EncodedVideoChunk({
            type: unit.key ? 'key' : 'delta',
            timestamp: unit.timestamp,
            data: bytes
          })
        );
      } catch {
        resetDecoder();
      }
    });

    // ---- JPEG fallback ----

    // One decode in flight at a time. Frames arrive faster than a slow machine
    // can decode them, and queueing every one would build a backlog that only
    // grows — the newest picture is the only one worth painting.
    let decoding = false;

    const stopFrames = window.switchboard.camera.onFrame((jpeg) => {
      // The video track wins while it is producing. Both are on the wire at
      // once, and painting from both would show two rates fighting over one
      // canvas.
      if (disposed || decoding || Date.now() - lastUnitAt < VIDEO_TRACK_GRACE_MS) return;
      decoding = true;

      createImageBitmap(new Blob([jpeg], { type: 'image/jpeg' }))
        .then((bitmap) => {
          // The JPEG track arrives already rotated and mirrored by the phone.
          paint(bitmap, bitmap.width, bitmap.height, 0, false);
          // Bitmaps hold decoded pixels outside the JS heap; at 30fps, leaving
          // them to the collector exhausts the renderer within minutes.
          bitmap.close();
        })
        .catch(() => undefined)
        .finally(() => {
          decoding = false;
        });
    });

    return () => {
      disposed = true;
      resetDecoder();
      stopVideo();
      stopFrames();
    };
  }, []);

  const apply = async (patch: Partial<CameraSettings>) => {
    if (!camera) return;
    setCamera({ ...camera, settings: { ...camera.settings, ...patch } });
    await window.switchboard.camera.control({ ...camera.settings, ...patch });
  };

  const toggle = async () => {
    setBusy(true);
    try {
      setCamera(
        camera?.streaming || camera?.deviceId
          ? await window.switchboard.camera.stop()
          : await window.switchboard.camera.start(selected)
      );
    } finally {
      setBusy(false);
    }
  };

  const settings = camera?.settings;
  const live = camera?.streaming ?? false;
  const waiting = Boolean(camera?.deviceId) && !live;

  return (
    <div className="flex-1 overflow-y-auto bg-canvas p-6">
      <header className="mb-5">
        <h2 className="text-base font-semibold text-ink">Camera</h2>
        <p className="text-tiny text-ink-faint">
          Stream a paired phone's camera to this machine.
        </p>
      </header>

      <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_320px]">
        <section className="space-y-3">
          <div className="relative flex aspect-video items-center justify-center overflow-hidden rounded-lg border border-line bg-black">
            {/* Kept mounted so the frame handler always has a canvas to draw
                into; remounting it on every start would drop the first frames. */}
            <canvas
              ref={canvasRef}
              aria-label="Phone camera"
              className={`h-full w-full object-contain ${live ? '' : 'hidden'}`}
            />
            {!live && (
              <p className="px-6 text-center text-tiny text-ink-faint">
                {waiting
                  ? 'Waiting for the phone to start its camera.'
                  : camera?.error || 'No camera streaming.'}
              </p>
            )}
          </div>

          <div className="flex flex-wrap items-center gap-3">
            <select
              value={selected}
              onChange={(event) => setDeviceId(event.target.value)}
              disabled={live}
              className="rounded border border-line bg-surface px-2 py-1 text-tiny text-ink"
            >
              {online.length === 0 && <option value="">No devices connected</option>}
              {online.map((device) => (
                <option key={device.id} value={device.id}>
                  {device.name}
                </option>
              ))}
            </select>

            <button
              type="button"
              onClick={toggle}
              disabled={busy || (!selected && !live && !waiting)}
              className="rounded bg-accent px-3 py-1 text-tiny font-medium text-white disabled:opacity-40"
            >
              {live || waiting ? 'Stop' : 'Start'}
            </button>

            {live && camera && (
              <span className="text-tiny text-ink-faint">
                {camera.width}×{camera.height} · {camera.fps.toFixed(0)} fps ·{' '}
                {(camera.bytesPerSec / 1_000_000).toFixed(1)} MB/s
                {camera.codec === 'h264' ? ' · H.264' : camera.codec === 'jpeg' ? ' · MJPEG' : ''}
              </span>
            )}
          </div>

          <div className="rounded-lg border border-line bg-surface p-3 text-tiny space-y-2">
            <div className="flex items-center justify-between">
              <span className="font-semibold text-ink">System Virtual Camera</span>
              <span
                className={`inline-flex items-center gap-1 font-medium ${
                  camera?.vcamInstalled ? 'text-emerald-400' : 'text-amber-400'
                }`}
              >
                <span
                  className={`h-1.5 w-1.5 rounded-full ${
                    camera?.vcamInstalled ? 'bg-emerald-400' : 'bg-amber-400'
                  }`}
                />
                {camera?.vcamInstalled ? 'Device Active ("Switchboard Camera")' : 'Not installed'}
              </span>
            </div>
            <p className="text-ink-faint">
              {camera?.vcamInstalled
                ? 'Device entry registered in Windows. You can select "Switchboard Camera" directly in Chrome, Zoom, Google Meet, Microsoft Teams, Discord, or any webcam app without OBS.'
                : 'Install the virtual camera device entry to use this phone camera directly in Chrome, Zoom, Google Meet, Teams, and other apps without needing OBS.'}
            </p>
            <div className="flex items-center gap-2 pt-1">
              {!camera?.vcamInstalled ? (
                <button
                  type="button"
                  onClick={handleInstallVcam}
                  disabled={vcamBusy}
                  className="rounded bg-accent px-2.5 py-1 text-tiny font-medium text-white hover:opacity-90 disabled:opacity-40"
                >
                  {vcamBusy ? 'Installing...' : 'Install Virtual Camera'}
                </button>
              ) : (
                <button
                  type="button"
                  onClick={handleUninstallVcam}
                  disabled={vcamBusy}
                  className="rounded border border-line px-2.5 py-1 text-tiny text-ink-faint hover:text-ink hover:bg-surface-elevated disabled:opacity-40"
                >
                  {vcamBusy ? 'Uninstalling...' : 'Uninstall Virtual Camera'}
                </button>
              )}
              {vcamMessage && (
                <span className="text-tiny text-ink-faint">{vcamMessage}</span>
              )}
            </div>
          </div>

          <p className="text-tiny text-ink-faint">
            Alternative MJPEG stream: <code className="text-ink">{STREAM_URL}</code> for OBS or VLC.
          </p>
        </section>

        {settings && (
          <aside className="space-y-4 text-tiny">
            <Group title="Capture">
              <Chips
                options={QUALITIES}
                value={settings.quality}
                onSelect={(quality) => apply({ quality })}
              />
              <Range
                label="Frame rate"
                value={settings.fps}
                min={5}
                max={60}
                readout={`${settings.fps} fps`}
                onChange={(fps) => apply({ fps })}
              />
              {camera?.hasFrontCamera && (
                <Toggle
                  label="Front camera"
                  checked={settings.facing === 'front'}
                  onChange={(front) =>
                    // A front camera that is not mirrored reads as wrong to
                    // whoever is in front of it, so it follows the lens.
                    apply({ facing: front ? 'front' : 'back', mirror: front })
                  }
                />
              )}
              {camera?.hasTorch && (
                <Toggle
                  label="Torch"
                  checked={settings.torch}
                  onChange={(torch) => apply({ torch })}
                />
              )}
            </Group>

            <Group title="Framing">
              <Range
                label="Zoom"
                value={Math.round(settings.zoom * 100)}
                min={0}
                max={100}
                readout={`${Math.round(settings.zoom * 100)}%`}
                onChange={(zoom) => apply({ zoom: zoom / 100 })}
              />
              <Chips
                options={[
                  [0, '0°'],
                  [90, '90°'],
                  [180, '180°'],
                  [270, '270°']
                ]}
                value={settings.rotation}
                onSelect={(rotation) => apply({ rotation })}
              />
              <Toggle
                label="Mirror"
                checked={settings.mirror}
                onChange={(mirror) => apply({ mirror })}
              />
              {/* Hidden rather than disabled on a lens with no face detection:
                  there is nothing for auto framing to follow, and a switch that
                  flips without changing the picture is worse than no switch. */}
              {camera?.hasAutoFraming && (
                <Toggle
                  label="Auto framing"
                  checked={settings.autoFraming}
                  onChange={(autoFraming) => apply({ autoFraming })}
                />
              )}
            </Group>

            <Group title="Image">
              {camera?.hasManualFocus && (
                <>
                  <Toggle
                    label="Auto focus"
                    checked={settings.autoFocus}
                    onChange={(autoFocus) => apply({ autoFocus })}
                  />
                  {!settings.autoFocus && (
                    <Range
                      label="Focus"
                      value={Math.round(settings.focusDistance * 100)}
                      min={0}
                      max={100}
                      readout={settings.focusDistance > 0.95 ? 'Infinity' : 'Near'}
                      onChange={(focus) => apply({ focusDistance: focus / 100 })}
                    />
                  )}
                </>
              )}
              {camera?.hasManualExposure && camera.maxExposure > camera.minExposure && (
                <>
                  <Toggle
                    label="Auto exposure"
                    checked={settings.autoExposure}
                    onChange={(autoExposure) => apply({ autoExposure })}
                  />
                  {!settings.autoExposure && (
                    <Range
                      label="Exposure"
                      value={settings.exposure}
                      min={camera.minExposure}
                      max={camera.maxExposure}
                      readout={`${settings.exposure} EV`}
                      onChange={(exposure) => apply({ exposure })}
                    />
                  )}
                </>
              )}
            </Group>

            {camera?.hasWhiteBalance && (
              <Group title="White balance">
                <Chips
                  options={(camera.whiteBalanceModes?.length
                    ? camera.whiteBalanceModes
                    : WHITE_BALANCE
                  ).map((mode) => [mode, mode] as [string, string])}
                  value={settings.whiteBalance}
                  onSelect={(whiteBalance) => apply({ whiteBalance })}
                />
              </Group>
            )}
          </aside>
        )}
      </div>
    </div>
  );
};

const Group = ({ title, children }: { title: string; children: React.ReactNode }) => (
  <section className="space-y-2 rounded-lg border border-line bg-surface p-3">
    <h3 className="text-tiny font-semibold uppercase tracking-wide text-ink-faint">{title}</h3>
    {children}
  </section>
);

function Chips<T extends string | number>({
  options,
  value,
  onSelect
}: {
  options: Array<[T, string]>;
  value: T;
  onSelect: (value: T) => void;
}) {
  return (
    <div className="flex flex-wrap gap-1.5">
      {options.map(([option, label]) => (
        <button
          key={String(option)}
          type="button"
          onClick={() => onSelect(option)}
          className={`rounded px-2 py-0.5 text-tiny capitalize ${
            option === value
              ? 'bg-accent text-white'
              : 'border border-line text-ink-faint hover:text-ink'
          }`}
        >
          {label}
        </button>
      ))}
    </div>
  );
}

/**
 * A compact labelled range.
 *
 * Deliberately not the shared LevelSlider: that one is built around an icon
 * and a percentage for the audio and display rows, and bending it to carry
 * "12 EV" or "Infinity" would complicate the control every other view uses.
 */
const Range = ({
  label,
  value,
  min,
  max,
  readout,
  onChange
}: {
  label: string;
  value: number;
  min: number;
  max: number;
  readout: string;
  onChange: (value: number) => void;
}) => (
  <label className="block space-y-1">
    <span className="flex items-center justify-between text-tiny text-ink">
      {label}
      <span className="text-ink-faint">{readout}</span>
    </span>
    <input
      type="range"
      min={min}
      max={max}
      value={value}
      onChange={(event) => onChange(Number(event.target.value))}
      className="w-full accent-accent"
    />
  </label>
);

const Toggle = ({
  label,
  checked,
  onChange
}: {
  label: string;
  checked: boolean;
  onChange: (checked: boolean) => void;
}) => (
  <label className="flex items-center justify-between text-tiny text-ink">
    {label}
    <input
      type="checkbox"
      checked={checked}
      onChange={(event) => onChange(event.target.checked)}
      className="accent-accent"
    />
  </label>
);
