import { Fingerprint, FolderOpen, Gauge, PanelBottom, SlidersHorizontal } from 'lucide-react';
import { useEffect, useState } from 'react';
import type { HostSettings, LocalState, UnlockStatus } from '../../shared/types';
import { Card, Pane, Sidebar, SidebarItem } from './Shell';

interface SettingsViewProps {
  state: LocalState;
  patch: (fn: (draft: LocalState) => LocalState) => void;
}

const RATE_UNITS: Array<{ value: HostSettings['rateUnit']; label: string; hint: string }> = [
  { value: 'MBps', label: 'MB/s', hint: 'Megabytes, as file managers report' },
  { value: 'Mbps', label: 'Mb/s', hint: 'Megabits, as network tools report' }
];

export function SettingsView({ state, patch }: SettingsViewProps) {
  const { settings } = state;
  const [error, setError] = useState<string | null>(null);

  const apply = async (change: Partial<HostSettings>) => {
    // Echoed locally first so a toggle does not wait a poll interval to move.
    patch((draft) => ({ ...draft, settings: { ...draft.settings, ...change } }));
    try {
      // The daemon validates a whole settings object, so the unchanged fields
      // ride along rather than the renderer guessing what may be omitted.
      await window.switchboard.updateSettings({ ...settings, ...change });
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not save the setting');
    }
  };

  // Setup runs in an elevated console the daemon launches, so nothing here
  // learns when it finished. The pane re-reads on focus instead, which is the
  // moment the user comes back from the UAC prompt and the password entry.
  const [unlock, setUnlock] = useState<UnlockStatus | null>(null);
  useEffect(() => {
    const read = () => {
      window.switchboard.unlock
        .status()
        .then(setUnlock)
        .catch(() => setUnlock(null));
    };
    read();
    window.addEventListener('focus', read);
    return () => window.removeEventListener('focus', read);
  }, []);

  const pickDir = async () => {
    const dir = await window.switchboard.chooseDownloadDir().catch(() => null);
    if (dir) apply({ downloadDir: dir });
  };

  return (
    <>
      <Sidebar title="Settings">
        <SidebarItem label="Preferences" icon={SlidersHorizontal} selected onSelect={() => {}} />
      </Sidebar>

      <Pane title="Settings" description="Where files land and how this host behaves">
        <div className="grid max-w-3xl gap-3">
          <Card title="Download folder">
            <div className="flex items-center gap-3">
              <FolderOpen aria-hidden="true" className="h-4 w-4 shrink-0 text-ink-dim" />
              <p className="min-w-0 flex-1 truncate font-mono text-micro text-ink-dim">
                {settings.downloadDir}
              </p>
              <button
                type="button"
                onClick={pickDir}
                className="shrink-0 rounded border border-edge px-2.5 py-1 text-micro text-ink-dim transition-colors hover:bg-raised hover:text-ink"
              >
                Change
              </button>
            </div>
            <p className="text-micro text-ink-faint">
              Files sent from a paired phone are written here.
            </p>
          </Card>

          <Card title="Transfer rate unit">
            <div className="flex items-center gap-3">
              <Gauge aria-hidden="true" className="h-4 w-4 shrink-0 text-ink-dim" />
              <div className="flex gap-1.5">
                {RATE_UNITS.map(({ value, label, hint }) => (
                  <button
                    key={value}
                    type="button"
                    onClick={() => apply({ rateUnit: value })}
                    aria-pressed={settings.rateUnit === value}
                    title={hint}
                    className={`rounded border px-2.5 py-1 font-mono text-micro transition-colors ${
                      settings.rateUnit === value
                        ? 'border-accent bg-accent/10 text-accent'
                        : 'border-edge text-ink-dim hover:bg-raised hover:text-ink'
                    }`}
                  >
                    {label}
                  </button>
                ))}
              </div>
            </div>
            <p className="text-micro text-ink-faint">
              {RATE_UNITS.find((u) => u.value === settings.rateUnit)?.hint}
            </p>
          </Card>

          {unlock?.supported ? (
            <Card title="Fingerprint unlock">
              <div className="flex items-center gap-3">
                <Fingerprint aria-hidden="true" className="h-4 w-4 shrink-0 text-ink-dim" />
                <p className="min-w-0 flex-1 text-micro text-ink-dim">
                  {unlock.enrolled
                    ? 'A paired phone can open this lock screen with its fingerprint.'
                    : 'Let a paired phone open this lock screen with its fingerprint.'}
                </p>
                <button
                  type="button"
                  onClick={() => {
                    const action = unlock.enrolled
                      ? window.switchboard.unlock.disable()
                      : window.switchboard.unlock.setup();
                    action.catch((err: unknown) =>
                      setError(err instanceof Error ? err.message : 'Could not start setup')
                    );
                  }}
                  className={`shrink-0 rounded border px-2.5 py-1 text-micro transition-colors ${
                    unlock.enrolled
                      ? 'border-accent bg-accent/10 text-accent'
                      : 'border-edge text-ink-dim hover:bg-raised hover:text-ink'
                  }`}
                >
                  {unlock.enrolled ? 'Turn off' : 'Set up'}
                </button>
              </div>
              <p className="text-micro text-ink-faint">
                {unlock.enrolled
                  ? 'Your Windows password is stored on this PC, encrypted and readable only by SYSTEM and administrators. Turning this off deletes it.'
                  : 'Opens an administrator window to store your Windows password and register the helper. Read the trade-offs first: this keeps your password on this PC.'}
              </p>
            </Card>
          ) : null}

          <Card title="Keep running in the background">
            <div className="flex items-center gap-3">
              <PanelBottom aria-hidden="true" className="h-4 w-4 shrink-0 text-ink-dim" />
              <p className="min-w-0 flex-1 text-micro text-ink-dim">
                {settings.runInBackground
                  ? 'Closing the window hides Switchboard to the tray; devices stay connected.'
                  : 'Closing the window quits Switchboard and disconnects paired devices.'}
              </p>
              <button
                type="button"
                onClick={() => apply({ runInBackground: !settings.runInBackground })}
                aria-pressed={settings.runInBackground}
                className={`shrink-0 rounded border px-2.5 py-1 text-micro transition-colors ${
                  settings.runInBackground
                    ? 'border-accent bg-accent/10 text-accent'
                    : 'border-edge text-ink-dim hover:bg-raised hover:text-ink'
                }`}
              >
                {settings.runInBackground ? 'On' : 'Off'}
              </button>
            </div>
          </Card>

          {error ? <p className="text-micro text-danger">{error}</p> : null}
        </div>
      </Pane>
    </>
  );
}
