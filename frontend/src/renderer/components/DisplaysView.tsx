import { Contrast, Laptop, Monitor, MonitorOff, Power, RefreshCw, Sun } from 'lucide-react';
import { useCallback, useState } from 'react';
import type { Display, LocalState } from '../../shared/types';
import { useThrottledCommit } from '../useHostState';
import { Card, EmptyState, Pane, Sidebar, SidebarItem } from './Shell';
import { LevelSlider } from './LevelSlider';

interface DisplaysViewProps {
  state: LocalState;
  patch: (fn: (draft: LocalState) => LocalState) => void;
  setPaused: (paused: boolean) => void;
  refresh: () => void;
}

export function DisplaysView({ state, patch, setPaused, refresh }: DisplaysViewProps) {
  const displays = state.host.displays;
  const [selected, setSelected] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // Selecting a monitor focuses it; the default view lists them all, which is
  // how the reference panel behaves.
  const shown = selected ? displays.filter((d) => d.id === selected) : displays;

  const applyLocal = useCallback(
    (id: string, field: 'brightness' | 'contrast', value: number) => {
      patch((draft) => ({
        ...draft,
        host: {
          ...draft.host,
          displays: draft.host.displays.map((d) => (d.id === id ? { ...d, [field]: value } : d))
        }
      }));
    },
    [patch]
  );

  const handleTogglePower = useCallback(
    async (id: string, on: boolean) => {
      patch((draft) => ({
        ...draft,
        host: {
          ...draft.host,
          displays: draft.host.displays.map((d) => (d.id === id ? { ...d, power: on } : d))
        }
      }));
      try {
        const updated = await window.switchboard.setDisplayPower(id, on);
        if (updated) {
          patch((draft) => ({
            ...draft,
            host: {
              ...draft.host,
              displays: draft.host.displays.map((d) => (d.id === id ? { ...d, ...updated } : d))
            }
          }));
        }
      } catch (err) {
        console.error('Failed to set display power:', err);
        refresh();
      }
    },
    [patch, refresh]
  );

  const rescan = async () => {
    setBusy(true);
    try {
      await window.switchboard.refreshDisplays();
      refresh();
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <Sidebar title="Displays">
        <SidebarItem
          label="All displays"
          icon={Monitor}
          detail={String(displays.length)}
          selected={selected === null}
          onSelect={() => setSelected(null)}
        />
        {displays.map((d) => (
          <SidebarItem
            key={d.id}
            label={d.name}
            icon={d.internal ? Laptop : Monitor}
            detail={d.power === false ? 'Off' : `${percent(d.brightness, d.minBrightness, d.maxBrightness)}%`}
            selected={selected === d.id}
            onSelect={() => setSelected(d.id)}
          />
        ))}
      </Sidebar>

      <Pane
        title={selected ? (displays.find((d) => d.id === selected)?.name ?? 'Display') : 'Displays'}
        description="Brightness and contrast over DDC/CI"
        actions={
          <button
            type="button"
            onClick={rescan}
            disabled={busy}
            className="flex items-center gap-1.5 rounded border border-edge px-2.5 py-1 text-micro text-ink-dim transition-colors hover:bg-raised hover:text-ink disabled:opacity-50"
          >
            <RefreshCw aria-hidden="true" className={`h-3 w-3 ${busy ? 'animate-spin' : ''}`} />
            {busy ? 'Scanning' : 'Rescan'}
          </button>
        }
      >
        {displays.length === 0 ? (
          <EmptyState
            icon={MonitorOff}
            title="No controllable displays found"
            hint="Switchboard needs DDC/CI enabled in the monitor's on-screen menu. Some docks and KVM switches do not pass the control channel through."
          />
        ) : (
          <div className="grid gap-3 lg:grid-cols-2">
            {shown.map((display) => (
              <DisplayCard
                key={display.id}
                display={display}
                onLocalChange={applyLocal}
                onTogglePower={handleTogglePower}
                setPaused={setPaused}
              />
            ))}
          </div>
        )}
      </Pane>
    </>
  );
}

function DisplayCard({
  display,
  onLocalChange,
  onTogglePower,
  setPaused
}: {
  display: Display;
  onLocalChange: (id: string, field: 'brightness' | 'contrast', value: number) => void;
  onTogglePower: (id: string, on: boolean) => void;
  setPaused: (paused: boolean) => void;
}) {
  const isOff = display.power === false;
  const sendBrightness = useThrottledCommit((value: number) =>
    window.switchboard.setBrightness(display.id, value)
  );
  const sendContrast = useThrottledCommit((value: number) =>
    window.switchboard.setContrast(display.id, value)
  );

  return (
    <Card
      title={display.name}
      meta={
        <div className="flex items-center gap-2">
          {isOff ? (
            <span className="shrink-0 rounded bg-warn/15 px-1.5 py-0.5 font-mono text-micro text-warn">
              Standby
            </span>
          ) : (
            <span className="shrink-0 rounded bg-raised px-1.5 py-0.5 font-mono text-micro text-ink-faint">
              {display.internal ? 'Internal' : 'DDC/CI'}
            </span>
          )}
          <button
            type="button"
            onClick={() => onTogglePower(display.id, isOff)}
            aria-label={isOff ? `Turn on ${display.name}` : `Turn off ${display.name}`}
            title={isOff ? 'Turn on display' : 'Turn off display (standby)'}
            className={`flex items-center justify-center rounded p-1 transition-colors ${
              isOff
                ? 'text-ink-faint hover:bg-raised hover:text-ink'
                : 'text-accent hover:bg-raised hover:text-accent-hover'
            }`}
          >
            <Power aria-hidden="true" className="h-3.5 w-3.5" />
          </button>
        </div>
      }
    >
      {isOff && (
        <div className="flex items-center justify-between rounded-md border border-edge bg-canvas/80 px-3 py-2 text-micro">
          <div className="flex items-center gap-2">
            <MonitorOff aria-hidden="true" className="h-3.5 w-3.5 text-warn" />
            <span className="font-medium text-ink-dim">Display Standby / Off</span>
          </div>
          <button
            type="button"
            onClick={() => onTogglePower(display.id, true)}
            className="rounded bg-accent px-2 py-1 text-tiny font-medium text-canvas hover:bg-accent/90 transition-colors"
          >
            Turn On
          </button>
        </div>
      )}

      <div className={`space-y-3 transition-opacity ${isOff ? 'opacity-40 pointer-events-none' : ''}`}>
        <LevelSlider
          icon={Sun}
          label={`${display.name} brightness`}
          value={display.brightness}
          min={display.minBrightness}
          max={display.maxBrightness}
          disabled={isOff}
          onGestureChange={setPaused}
          onChange={(value) => {
            onLocalChange(display.id, 'brightness', value);
            sendBrightness(value);
          }}
          onCommit={(value) => window.switchboard.setBrightness(display.id, value).catch(() => {})}
        />

        {display.hasContrast ? (
          <LevelSlider
            icon={Contrast}
            label={`${display.name} contrast`}
            value={display.contrast}
            min={display.minContrast}
            max={display.maxContrast}
            disabled={isOff}
            onGestureChange={setPaused}
            onChange={(value) => {
              onLocalChange(display.id, 'contrast', value);
              sendContrast(value);
            }}
            onCommit={(value) => window.switchboard.setContrast(display.id, value).catch(() => {})}
          />
        ) : null}
      </div>

      {/* The panel's own capability range, not an assumed 0-100. */}
      <dl className={`flex gap-4 border-t border-edge pt-2 font-mono text-micro text-ink-faint ${isOff ? 'opacity-40' : ''}`}>
        <div className="flex gap-1.5">
          <dt>Brightness</dt>
          <dd className="tabular-nums text-ink-dim">
            {display.brightness} / {display.maxBrightness}
          </dd>
        </div>
        {display.hasContrast ? (
          <div className="flex gap-1.5">
            <dt>Contrast</dt>
            <dd className="tabular-nums text-ink-dim">
              {display.contrast} / {display.maxContrast}
            </dd>
          </div>
        ) : null}
      </dl>
    </Card>
  );
}

function percent(value: number, min: number, max: number): number {
  return max > min ? Math.round(((value - min) / (max - min)) * 100) : 0;
}
