import { useCallback, useEffect, useRef, useState } from 'react';
import type { CameraSettings, CameraState, LocalState } from '../../shared/types';

const QUALITIES: Array<[CameraSettings['quality'], string]> = [
  ['full', 'Full (1080p)'],
  ['balanced', '720p'],
  ['low', '480p']
];

const WHITE_BALANCE = ['auto', 'incandescent', 'fluorescent', 'daylight', 'cloudy', 'shade'];

const STREAM_URL = 'http://127.0.0.1:9427/local/camera/stream';

/**
 * Live view and controls for a phone acting as a webcam.
 *
 * Frames arrive over IPC as raw JPEG bytes rather than through an `<img>`
 * pointed at the daemon: the renderer runs with `default-src 'self'` and makes
 * no network requests of its own, and relaxing that for a video feed would be
 * a poor trade. Each frame becomes a blob URL, and the previous one is revoked
 * immediately — at 30fps, leaking them exhausts the renderer within minutes.
 */
export const CameraView = ({ state }: { state: LocalState }) => {
  const [camera, setCamera] = useState<CameraState | null>(null);
  const [frameUrl, setFrameUrl] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const previousUrl = useRef<string | null>(null);

  const online = state.devices.filter((d) => d.online);
  const [deviceId, setDeviceId] = useState('');
  const selected = deviceId || online[0]?.id || '';

  const refresh = useCallback(async () => {
    setCamera(await window.switchboard.camera.getState());
  }, []);

  useEffect(() => {
    void refresh();
    const timer = setInterval(refresh, 1000);
    return () => clearInterval(timer);
  }, [refresh]);

  useEffect(() => {
    const stop = window.switchboard.camera.onFrame((jpeg) => {
      const url = URL.createObjectURL(new Blob([jpeg], { type: 'image/jpeg' }));
      // Revoke on replacement, not on unmount: one URL per frame at 30fps is
      // a megabyte a second of retained blobs otherwise.
      if (previousUrl.current) URL.revokeObjectURL(previousUrl.current);
      previousUrl.current = url;
      setFrameUrl(url);
    });
    return () => {
      stop();
      if (previousUrl.current) URL.revokeObjectURL(previousUrl.current);
      previousUrl.current = null;
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
          <div className="flex aspect-video items-center justify-center overflow-hidden rounded-lg border border-line bg-black">
            {frameUrl && live ? (
              <img src={frameUrl} alt="Phone camera" className="h-full w-full object-contain" />
            ) : (
              <p className="px-6 text-center text-tiny text-ink-faint">
                {waiting
                  ? 'Waiting for the phone. Open the Camera screen in the app — streaming runs only while it is on screen.'
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
              </span>
            )}
          </div>

          {/*
            The path that makes this phone usable in software Switchboard knows
            nothing about. A real system camera device would need a signed
            Media Foundation or DirectShow filter, so OBS bridges the gap.
          */}
          <p className="text-tiny text-ink-faint">
            Use in other apps: add <code className="text-ink">{STREAM_URL}</code> as a media
            source in OBS or VLC, then enable OBS's virtual camera to make it available to
            conferencing apps.
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
              <Toggle
                label="Auto framing"
                checked={settings.autoFraming}
                onChange={(autoFraming) => apply({ autoFraming })}
              />
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

            <Group title="White balance">
              <Chips
                options={WHITE_BALANCE.map((mode) => [mode, mode] as [string, string])}
                value={settings.whiteBalance}
                onSelect={(whiteBalance) => apply({ whiteBalance })}
              />
            </Group>
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
