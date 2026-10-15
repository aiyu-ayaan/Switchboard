import { AppWindow, ExternalLink, RefreshCw, Search } from 'lucide-react';
import { useCallback, useEffect, useMemo, useState } from 'react';
import type { LocalState, MirrorApp, MirrorState } from '../../shared/types';
import { PhoneMirror, PhoneNavBar } from './PhoneMirror';

/** Polls the mirror's state; the phone reports it, the daemon relays it. */
export function useMirrorState(intervalMs = 1000) {
  const [mirror, setMirror] = useState<MirrorState | null>(null);
  const refresh = useCallback(async () => {
    try {
      setMirror(await window.switchboard.phone.getState());
    } catch {
      // the daemon restarting; the next tick tries again
    }
  }, []);
  useEffect(() => {
    void refresh();
    const timer = setInterval(refresh, intervalMs);
    return () => clearInterval(timer);
  }, [refresh, intervalMs]);
  return { mirror, setMirror, refresh };
}

/**
 * The frame rate, as the user should read it. Android sends nothing while the
 * screen is still -- only a repeat every quarter second -- so a low count is
 * an idle screen, not a slow stream, and saying "4 fps" would claim otherwise.
 */
const rate = (fps = 0) => (fps >= 8 ? `${fps.toFixed(0)} fps` : 'idle, updates as the screen changes');

const iconSrc = (app: MirrorApp) => (app.icon ? `data:image/png;base64,${app.icon}` : undefined);

/**
 * A paired phone's screen on this desktop.
 *
 * The phone appears live in the middle, driven by mouse and keyboard; its apps
 * are listed beside it, and each opens in a window of its own -- the way a
 * phone-link app keeps WhatsApp and Messages side by side on the desktop. An
 * Android app cannot run other apps on hidden screens of its own, so those
 * windows share the phone's one screen: focusing a window brings its app to
 * the front on the phone.
 */
export const PhoneView = ({ state }: { state: LocalState }) => {
  const { mirror, setMirror } = useMirrorState();
  const [apps, setApps] = useState<MirrorApp[]>([]);
  const [appsLoading, setAppsLoading] = useState(false);
  const [query, setQuery] = useState('');
  const [busy, setBusy] = useState(false);
  const [deviceId, setDeviceId] = useState('');
  const [windows, setWindows] = useState<string[]>([]);

  useEffect(() => {
    void window.switchboard.phone.windows().then(setWindows);
    return window.switchboard.phone.onWindows(setWindows);
  }, []);
  // Out in a window of its own, the phone is not drawn here as well.
  const poppedOut = windows.length > 0;

  const online = state.devices.filter((d) => d.online);
  const selected = mirror?.deviceId || deviceId || online[0]?.id || '';
  const live = Boolean(mirror?.streaming);
  const waiting = Boolean(mirror?.awaitingConsent);
  const deviceName = state.devices.find((d) => d.id === selected)?.name ?? 'the phone';

  /**
   * Asks the phone for its launcher list. The answer comes back on the
   * session a moment later, so this polls briefly rather than waiting on a
   * reply the daemon does not hold.
   */
  const loadApps = useCallback(async (device: string) => {
    if (!device) return;
    setAppsLoading(true);
    try {
      await window.switchboard.phone.refreshApps(device);
      for (let i = 0; i < 20; i += 1) {
        await new Promise((resolve) => setTimeout(resolve, 300));
        const result = await window.switchboard.phone.apps();
        if (result.deviceId === device && result.apps.length > 0) {
          setApps(result.apps);
          return;
        }
      }
    } catch {
      // phone not connected; the list stays as it was
    } finally {
      setAppsLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadApps(selected);
  }, [selected, loadApps]);

  const toggle = async () => {
    setBusy(true);
    try {
      setMirror(live || waiting ? await window.switchboard.phone.stop() : await window.switchboard.phone.start(selected));
    } finally {
      setBusy(false);
    }
  };

  const openApp = async (app: MirrorApp) => {
    if (!live && !waiting && selected) setMirror(await window.switchboard.phone.start(selected));
    await window.switchboard.phone.launch(app.package, selected);
    await window.switchboard.phone.openWindow(app.package, app.label);
  };

  const shown = useMemo(() => {
    const q = query.trim().toLowerCase();
    return q ? apps.filter((a) => a.label.toLowerCase().includes(q) || a.package.includes(q)) : apps;
  }, [apps, query]);

  const foregroundLabel = apps.find((a) => a.package === mirror?.foreground)?.label;

  return (
    <div className="flex min-w-0 flex-1 bg-canvas">
      <div className="flex min-w-0 flex-1 flex-col p-6">
        <header className="mb-4 flex flex-wrap items-center gap-3">
          <div className="mr-auto">
            <h2 className="text-base font-semibold text-ink">Phone</h2>
            <p className="text-tiny text-ink-faint">
              See and use a paired phone on this desktop, and open its apps in their own windows.
            </p>
          </div>
          <select
            value={selected}
            onChange={(event) => setDeviceId(event.target.value)}
            disabled={live || waiting}
            className="rounded border border-edge bg-card px-2.5 py-1 text-tiny text-ink transition-colors hover:border-accent-dim focus:border-accent"
          >
            {online.length === 0 && <option value="">No phones connected</option>}
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
            className="rounded bg-accent px-3 py-1 text-tiny font-medium text-white transition-opacity hover:opacity-90 disabled:opacity-40"
          >
            {live || waiting ? 'Stop sharing' : 'Show phone screen'}
          </button>
          <button
            type="button"
            onClick={() => window.switchboard.phone.openWindow('', deviceName)}
            disabled={!live}
            title="Open the whole phone in its own window"
            className="flex items-center gap-1.5 rounded border border-edge px-2.5 py-1 text-tiny text-ink-dim transition-colors hover:border-accent-dim hover:text-ink disabled:opacity-40"
          >
            <ExternalLink aria-hidden="true" className="h-3.5 w-3.5" /> Pop out
          </button>
        </header>

        <div className="flex min-h-0 flex-1 flex-col items-center justify-center gap-3">
          <div className="relative flex min-h-0 w-full flex-1 items-center justify-center">
            {poppedOut ? (
              <div className="flex max-w-sm flex-col items-center gap-3 text-center">
                <p className="text-tiny text-ink-faint">
                  {windows.length === 1 && windows[0] === ''
                    ? `${deviceName} is open in its own window.`
                    : `${deviceName} is open in ${windows.length} window${windows.length === 1 ? '' : 's'}.`}
                </p>
                <div className="flex gap-2">
                  <button
                    type="button"
                    onClick={() => window.switchboard.phone.focusWindows()}
                    className="rounded border border-edge px-2.5 py-1 text-tiny text-ink-dim hover:border-accent-dim hover:text-ink"
                  >
                    Focus
                  </button>
                  <button
                    type="button"
                    onClick={() => window.switchboard.phone.closeWindows()}
                    className="rounded border border-edge px-2.5 py-1 text-tiny text-ink-dim hover:border-accent-dim hover:text-ink"
                  >
                    Show here
                  </button>
                </div>
              </div>
            ) : (
              <PhoneMirror live={live} control={Boolean(mirror?.control)} />
            )}
            {!live && !poppedOut && (
              <p className="absolute max-w-sm px-6 text-center text-tiny text-ink-faint">
                {waiting
                  ? `Allow sharing on ${deviceName}: Android is asking on its screen.`
                  : mirror?.error || `Press "Show phone screen" to see ${deviceName} here.`}
              </p>
            )}
          </div>
          {live && !poppedOut && <PhoneNavBar disabled={!mirror?.control} />}
          {live && (
            <p className="text-micro text-ink-faint">
              {mirror?.width}×{mirror?.height} · {rate(mirror?.fps)}
              {foregroundLabel ? ` · ${foregroundLabel}` : ''}
              {mirror?.control
                ? ' · Click to tap, drag to swipe, right-click for Back'
                : ' · View only: turn on "Switchboard desktop control" in the phone\'s Accessibility settings to use it from here'}
            </p>
          )}
        </div>
      </div>

      <aside className="flex w-72 shrink-0 flex-col border-l border-edge bg-sidebar">
        <div className="flex items-center gap-2 border-b border-edge px-3 py-2.5">
          <span className="text-micro font-semibold uppercase tracking-wide text-ink-dim">Apps</span>
          <span className="text-micro text-ink-faint">{apps.length || ''}</span>
          <button
            type="button"
            onClick={() => loadApps(selected)}
            disabled={!selected || appsLoading}
            title="Reload the app list"
            aria-label="Reload the app list"
            className="ml-auto rounded p-1 text-ink-dim hover:bg-raised hover:text-ink disabled:opacity-40"
          >
            <RefreshCw aria-hidden="true" className={`h-3.5 w-3.5 ${appsLoading ? 'animate-spin' : ''}`} />
          </button>
        </div>
        <label className="mx-3 mt-3 flex items-center gap-2 rounded border border-edge bg-card px-2 py-1">
          <Search aria-hidden="true" className="h-3.5 w-3.5 text-ink-faint" />
          <input
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="Search apps"
            className="w-full bg-transparent text-tiny text-ink outline-none placeholder:text-ink-faint"
          />
        </label>
        <ul className="mt-2 min-h-0 flex-1 overflow-y-auto px-1.5 pb-3">
          {shown.map((app) => (
            <li key={app.package}>
              <button
                type="button"
                onClick={() => openApp(app)}
                title={`Open ${app.label} in its own window`}
                className={`group flex w-full items-center gap-2.5 rounded px-2 py-1.5 text-left transition-colors hover:bg-raised ${
                  mirror?.foreground === app.package ? 'bg-raised/60' : ''
                }`}
              >
                {iconSrc(app) ? (
                  <img src={iconSrc(app)} alt="" className="h-7 w-7 rounded-md" />
                ) : (
                  <span className="h-7 w-7 rounded-md bg-raised" />
                )}
                <span className="min-w-0 flex-1 truncate text-tiny text-ink">{app.label}</span>
                <AppWindow aria-hidden="true" className="h-3.5 w-3.5 text-ink-faint opacity-0 group-hover:opacity-100" />
              </button>
            </li>
          ))}
          {!appsLoading && shown.length === 0 && (
            <li className="px-3 py-4 text-center text-tiny text-ink-faint">
              {selected ? (apps.length ? 'No app matches.' : 'No apps yet.') : 'Connect a phone to list its apps.'}
            </li>
          )}
        </ul>
      </aside>
    </div>
  );
};
