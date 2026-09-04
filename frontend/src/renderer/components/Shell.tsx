import { Minus, Square, X } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import type { ReactNode } from 'react';

export type ViewId = 'displays' | 'audio' | 'files' | 'devices' | 'settings';

/** Frameless-window chrome. The bar itself is the OS drag handle. */
export function TitleBar({ subtitle }: { subtitle: string }) {
  const controls: Array<{ label: string; icon: LucideIcon; run: () => void; danger?: boolean }> = [
    { label: 'Minimize', icon: Minus, run: () => window.switchboard.window.minimize() },
    { label: 'Maximize', icon: Square, run: () => window.switchboard.window.toggleMaximize() },
    { label: 'Close', icon: X, run: () => window.switchboard.window.close(), danger: true }
  ];

  return (
    <header className="app-drag flex h-9 shrink-0 items-center justify-between border-b border-edge bg-rail pl-3">
      <div className="flex items-center gap-2 text-micro">
        <span className="font-semibold tracking-wide text-ink">Switchboard</span>
        <span className="text-ink-faint">{subtitle}</span>
      </div>
      <div className="app-no-drag flex h-full">
        {controls.map(({ label, icon: Icon, run, danger }) => (
          <button
            key={label}
            type="button"
            onClick={run}
            aria-label={label}
            className={`flex h-full w-11 items-center justify-center text-ink-dim transition-colors ${
              danger ? 'hover:bg-danger hover:text-canvas' : 'hover:bg-raised hover:text-ink'
            }`}
          >
            <Icon aria-hidden="true" className="h-3.5 w-3.5" />
          </button>
        ))}
      </div>
    </header>
  );
}

interface ActivityBarProps {
  items: Array<{ id: ViewId; label: string; icon: LucideIcon; badge?: number }>;
  active: ViewId;
  onSelect: (id: ViewId) => void;
}

/** The icon rail. Each button carries a label: an icon alone is not a name. */
export function ActivityBar({ items, active, onSelect }: ActivityBarProps) {
  return (
    <nav aria-label="Sections" className="flex w-12 shrink-0 flex-col border-r border-edge bg-rail py-1">
      {items.map(({ id, label, icon: Icon, badge }) => {
        const selected = id === active;
        return (
          <button
            key={id}
            type="button"
            onClick={() => onSelect(id)}
            aria-label={label}
            aria-current={selected ? 'page' : undefined}
            title={label}
            className={`relative flex h-12 w-full items-center justify-center transition-colors ${
              selected ? 'text-ink' : 'text-ink-faint hover:text-ink-dim'
            }`}
          >
            {/* The active marker is a shape, not just a colour change. */}
            <span
              aria-hidden="true"
              className={`absolute left-0 top-2 bottom-2 w-0.5 rounded-r bg-accent transition-opacity ${
                selected ? 'opacity-100' : 'opacity-0'
              }`}
            />
            <Icon aria-hidden="true" className="h-5 w-5" />
            {badge ? (
              <span className="absolute right-2 top-3 flex h-4 min-w-4 items-center justify-center rounded-full bg-accent px-1 font-mono text-[10px] font-semibold tabular-nums text-rail">
                {badge}
              </span>
            ) : null}
          </button>
        );
      })}
    </nav>
  );
}

/** The middle column: a titled list of whatever the active section owns. */
export function Sidebar({ title, action, children }: { title: string; action?: ReactNode; children: ReactNode }) {
  return (
    <aside className="flex w-60 shrink-0 flex-col border-r border-edge bg-sidebar">
      <div className="flex h-8 shrink-0 items-center justify-between px-3">
        <h2 className="text-micro font-semibold uppercase tracking-wider text-ink-faint">{title}</h2>
        {action}
      </div>
      <div className="flex-1 overflow-y-auto px-2 pb-2">{children}</div>
    </aside>
  );
}

interface SidebarItemProps {
  label: string;
  detail?: string;
  icon: LucideIcon;
  selected?: boolean;
  onSelect: () => void;
}

export function SidebarItem({ label, detail, icon: Icon, selected, onSelect }: SidebarItemProps) {
  return (
    <button
      type="button"
      onClick={onSelect}
      aria-current={selected ? 'true' : undefined}
      className={`flex w-full items-center gap-2 rounded px-2 py-1.5 text-left text-tiny transition-colors ${
        selected ? 'bg-raised text-ink' : 'text-ink-dim hover:bg-card hover:text-ink'
      }`}
    >
      <Icon aria-hidden="true" className="h-3.5 w-3.5 shrink-0" />
      <span className="flex-1 truncate">{label}</span>
      {detail ? <span className="shrink-0 font-mono text-micro tabular-nums text-ink-faint">{detail}</span> : null}
    </button>
  );
}

/** The content pane, with a heading row matching the VS Code editor header. */
export function Pane({ title, description, actions, children }: {
  title: string;
  description?: string;
  actions?: ReactNode;
  children: ReactNode;
}) {
  return (
    <main className="flex min-w-0 flex-1 flex-col bg-canvas">
      <div className="flex h-12 shrink-0 items-center justify-between border-b border-edge px-5">
        <div className="min-w-0">
          <h1 className="truncate text-sm font-semibold text-ink">{title}</h1>
          {description ? <p className="truncate text-micro text-ink-faint">{description}</p> : null}
        </div>
        {actions ? <div className="flex shrink-0 items-center gap-2">{actions}</div> : null}
      </div>
      <div className="flex-1 overflow-y-auto p-5">{children}</div>
    </main>
  );
}

/** Status strip along the bottom, as in the reference editor chrome. */
export function StatusBar({ host, devices, error }: { host: string; devices: number; error: string | null }) {
  return (
    <footer
      className={`flex h-6 shrink-0 items-center justify-between px-3 text-micro ${
        error ? 'bg-danger text-canvas' : 'bg-accent-dim text-ink'
      }`}
    >
      <span className="truncate">{error ? `Daemon: ${error}` : host}</span>
      {!error && (
        <span className="shrink-0 tabular-nums">
          {devices} {devices === 1 ? 'device' : 'devices'} paired
        </span>
      )}
    </footer>
  );
}

export function Card({ title, meta, children }: { title: string; meta?: ReactNode; children: ReactNode }) {
  return (
    <section className="rounded-lg border border-edge bg-card p-4">
      <div className="mb-3 flex items-center justify-between gap-3">
        <h3 className="truncate text-tiny font-semibold text-ink">{title}</h3>
        {meta}
      </div>
      <div className="space-y-3">{children}</div>
    </section>
  );
}

export function EmptyState({ icon: Icon, title, hint }: { icon: LucideIcon; title: string; hint: string }) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 rounded-lg border border-dashed border-edge px-6 py-12 text-center">
      <Icon aria-hidden="true" className="h-6 w-6 text-ink-faint" />
      <p className="text-tiny font-medium text-ink-dim">{title}</p>
      <p className="max-w-xs text-micro text-ink-faint">{hint}</p>
    </div>
  );
}
