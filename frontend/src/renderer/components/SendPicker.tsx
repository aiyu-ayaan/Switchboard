import { Smartphone, X } from 'lucide-react';
import { useEffect, useState } from 'react';
import type { PairedDevice } from '../../shared/types';

/**
 * The window Explorer's "Send to Switchboard" verb opens.
 *
 * One job: name the files, list the devices, send on click. It never learns
 * the paths — the main process holds those and takes only a device id back —
 * and it closes itself the moment the transfer is queued, because the Files
 * view in the main window is where progress belongs.
 */
export function SendPicker() {
  const [files, setFiles] = useState<string[]>([]);
  const [devices, setDevices] = useState<PairedDevice[]>([]);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const picker = window.switchboard.sendPicker;

  useEffect(() => {
    picker.files().then(setFiles).catch(() => setFiles([]));
    picker.devices().then(setDevices).catch(() => setDevices([]));
    // A second right-click while this is open adds to the same batch.
    return picker.onFiles(setFiles);
  }, [picker]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') picker.cancel();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [picker]);

  const send = async (device: PairedDevice) => {
    setBusy(device.id);
    setError(null);
    try {
      await picker.send(device.id);
    } catch (err) {
      setBusy(null);
      setError(err instanceof Error ? err.message : 'Could not queue the files');
    }
  };

  const online = devices.filter((d) => d.online);
  const summary = files.length === 1 ? files[0] : `${files.length} files`;

  return (
    <div className="flex h-full flex-col bg-canvas">
      <header
        className="flex items-start justify-between gap-2 border-b border-edge px-4 py-3"
        style={{ WebkitAppRegion: 'drag' } as React.CSSProperties}
      >
        <div className="min-w-0">
          <p className="truncate text-tiny font-medium text-ink">Send {summary}</p>
          <p className="truncate text-micro text-ink-faint">
            {files.length > 1 ? files.slice(0, 3).join(', ') : 'Choose a device'}
            {files.length > 3 ? `, +${files.length - 3}` : ''}
          </p>
        </div>
        <button
          type="button"
          onClick={() => picker.cancel()}
          aria-label="Cancel"
          className="rounded p-1 text-ink-faint hover:bg-card hover:text-ink"
          style={{ WebkitAppRegion: 'no-drag' } as React.CSSProperties}
        >
          <X aria-hidden="true" className="h-4 w-4" />
        </button>
      </header>

      <div className="flex-1 overflow-y-auto p-2">
        {devices.length === 0 ? (
          <p className="px-2 py-6 text-center text-micro text-ink-faint">
            Nothing is paired yet. Pair a phone in Switchboard first.
          </p>
        ) : online.length === 0 ? (
          <p className="px-2 py-6 text-center text-micro text-ink-faint">
            No paired device is online right now.
          </p>
        ) : (
          <ul className="grid gap-1">
            {devices.map((device) => (
              <li key={device.id}>
                <button
                  type="button"
                  disabled={!device.online || busy !== null}
                  onClick={() => send(device)}
                  className="flex w-full items-center gap-3 rounded-lg px-3 py-2.5 text-left transition-colors enabled:hover:bg-card disabled:cursor-not-allowed disabled:opacity-40"
                >
                  <Smartphone aria-hidden="true" className="h-4 w-4 shrink-0 text-ink-faint" />
                  <span className="min-w-0 flex-1 truncate text-tiny text-ink">{device.name}</span>
                  <span
                    className={`text-micro ${device.online ? 'text-level' : 'text-ink-faint'}`}
                  >
                    {busy === device.id ? 'Sending…' : device.online ? 'Online' : 'Offline'}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>

      {error ? (
        <p className="border-t border-edge px-4 py-2 text-micro text-danger">{error}</p>
      ) : null}
    </div>
  );
}
