import {
  ExternalLink,
  FolderOpen,
  Gauge,
  Github,
  Globe,
  Info,
  Linkedin,
  PanelBottom,
  Power,
  ShieldCheck,
  SlidersHorizontal,
  User
} from 'lucide-react';
import { useState } from 'react';
import type { HostSettings, LocalState } from '../../shared/types';
import { Card, Pane, Sidebar, SidebarItem } from './Shell';
import { UpdatesCard } from './UpdatesCard';

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
        <div className="grid max-w-3xl gap-4">
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

          <Card title="Start on boot">
            <div className="flex items-center gap-3">
              <Power aria-hidden="true" className="h-4 w-4 shrink-0 text-ink-dim" />
              <p className="min-w-0 flex-1 text-micro text-ink-dim">
                {settings.autoStart
                  ? 'Switchboard launches automatically when you sign in to your computer.'
                  : 'Switchboard will only start when you launch it manually.'}
              </p>
              <button
                type="button"
                onClick={() => apply({ autoStart: !settings.autoStart })}
                aria-pressed={settings.autoStart}
                className={`shrink-0 rounded border px-2.5 py-1 text-micro transition-colors ${
                  settings.autoStart
                    ? 'border-accent bg-accent/10 text-accent'
                    : 'border-edge text-ink-dim hover:bg-raised hover:text-ink'
                }`}
              >
                {settings.autoStart ? 'On' : 'Off'}
              </button>
            </div>
          </Card>

          {error ? <p className="text-micro text-danger">{error}</p> : null}

          <UpdatesCard />

          <div className="pt-2">
            <h2 className="mb-2 text-micro font-bold uppercase tracking-wider text-ink-faint">
              About & Developer
            </h2>
          </div>

          <Card title="About Developer">
            <div className="flex items-start gap-4">
              <div className="relative h-14 w-14 shrink-0 overflow-hidden rounded-full border border-edge bg-raised">
                <img
                  src="https://avatars.githubusercontent.com/u/76834976?v=4"
                  alt="Aiyu Ayaan"
                  className="h-full w-full object-cover"
                  onError={(e) => {
                    (e.currentTarget as HTMLElement).style.display = 'none';
                  }}
                />
                <div className="absolute inset-0 flex items-center justify-center -z-10 text-ink-dim">
                  <User className="h-6 w-6" />
                </div>
              </div>
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  <h4 className="text-tiny font-bold text-ink">Aiyu Ayaan</h4>
                  <span className="rounded bg-accent/10 px-1.5 py-0.5 font-mono text-[10px] text-accent">
                    @aiyu-ayaan
                  </span>
                </div>
                <p className="text-micro text-ink-dim mt-0.5">Developer & Creator</p>
                <p className="mt-2 text-micro leading-relaxed text-ink-dim">
                  Software developer and creator of Switchboard. Focused on low-latency systems, zero-trust
                  local networking, and seamless cross-device workflows without third-party cloud dependence.
                  Code is objective.
                </p>
                <div className="mt-3 flex flex-wrap gap-2">
                  <button
                    type="button"
                    onClick={() => window.switchboard.openExternal('https://github.com/aiyu-ayaan')}
                    className="inline-flex items-center gap-1.5 rounded border border-edge bg-raised/50 px-2.5 py-1 text-micro text-ink-dim transition-colors hover:border-accent hover:bg-raised hover:text-ink"
                  >
                    <Github className="h-3.5 w-3.5" />
                    <span>GitHub</span>
                    <ExternalLink className="h-2.5 w-2.5 opacity-60" />
                  </button>
                  <button
                    type="button"
                    onClick={() => window.switchboard.openExternal('https://me.aiyu.co.in/')}
                    className="inline-flex items-center gap-1.5 rounded border border-edge bg-raised/50 px-2.5 py-1 text-micro text-ink-dim transition-colors hover:border-accent hover:bg-raised hover:text-ink"
                  >
                    <Globe className="h-3.5 w-3.5" />
                    <span>Portfolio</span>
                    <ExternalLink className="h-2.5 w-2.5 opacity-60" />
                  </button>
                  <button
                    type="button"
                    onClick={() => window.switchboard.openExternal('https://www.linkedin.com/in/aiyu/')}
                    className="inline-flex items-center gap-1.5 rounded border border-edge bg-raised/50 px-2.5 py-1 text-micro text-ink-dim transition-colors hover:border-accent hover:bg-raised hover:text-ink"
                  >
                    <Linkedin className="h-3.5 w-3.5" />
                    <span>LinkedIn</span>
                    <ExternalLink className="h-2.5 w-2.5 opacity-60" />
                  </button>
                </div>
              </div>
            </div>
          </Card>

          <Card title="About Switchboard">
            <div className="space-y-3">
              <div className="rounded border border-edge bg-raised/40 p-3">
                <div className="flex items-center gap-2 text-accent">
                  <ShieldCheck className="h-4 w-4" />
                  <span className="text-micro font-semibold">Zero-Trust E2EE Protocol</span>
                </div>
                <p className="mt-1 text-micro text-ink-dim">
                  Ephemeral X25519 ECDH key exchange with AES-256-GCM local encrypted WebSocket framing.
                  Direct peer-to-peer communication over local Wi-Fi with no cloud relay.
                </p>
              </div>

              <div className="flex items-center justify-between text-micro text-ink-dim">
                <div className="flex items-center gap-1.5">
                  <Info className="h-3.5 w-3.5 text-ink-faint" />
                  <span>Switchboard Desktop Host</span>
                </div>
                <span className="rounded bg-raised px-2 py-0.5 font-mono text-[11px] font-semibold text-accent">
                  v0.1.0
                </span>
              </div>
            </div>
          </Card>
        </div>
      </Pane>
    </>
  );
}
