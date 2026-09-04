import { FolderOpen, Gauge, PanelBottom, SlidersHorizontal } from 'lucide-react';
import { useState } from 'react';
import type { HostSettings, LocalState } from '../../shared/types';
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
