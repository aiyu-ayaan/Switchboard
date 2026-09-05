import { Lock, Minus, Square, X } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import type { ReactNode } from 'react';

export type ViewId = 'displays' | 'audio' | 'camera' | 'files' | 'devices' | 'settings';

/** Frameless-window chrome. The bar itself is the OS drag handle. */
export function TitleBar({ subtitle }: { subtitle: string }) {
  const controls: Array<{ label: string; icon: LucideIcon; run: () => void; danger?: boolean }> = [
    { label: 'Lock workstation', icon: Lock, run: () => window.switchboard.lockSystem() },
    { label: 'Minimize', icon: Minus, run: () => window.switchboard.window.minimize() },
    { label: 'Maximize', icon: Square, run: () => window.switchboard.window.toggleMaximize() },
    { label: 'Close', icon: X, run: () => window.switchboard.window.close(), danger: true }
  ];

  return (
    <header className="app-drag flex h-9 shrink-0 items-center justify-between border-b border-edge bg-rail pl-3">
      <div className="flex items-center gap-2 text-micro">
        <svg className="h-4 w-4 shrink-0" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
          <path opacity="0.22" d="M5.67412 3.77772C5 4.78661 5 6.19108 5 9V15C5 17.8089 5 19.2134 5.67412 20.2223C5.96596 20.659 6.34096 21.034 6.77772 21.3259C7.78661 22 9.19108 22 12 22C14.8089 22 16.2134 22 17.2223 21.3259C17.659 21.034 18.034 20.659 18.3259 20.2223C19 19.2134 19 17.8089 19 15V9C19 6.19108 19 4.78661 18.3259 3.77772C18.034 3.34096 17.659 2.96596 17.2223 2.67412C16.2134 2 14.8089 2 12 2C9.19108 2 7.78661 2 6.77772 2.67412C6.34096 2.96596 5.96596 3.34096 5.67412 3.77772Z" fill="#7AA2F7" />
          <path fillRule="evenodd" clipRule="evenodd" d="M8.25 15.5C8.25 13.4289 9.92893 11.75 12 11.75C14.0711 11.75 15.75 13.4289 15.75 15.5C15.75 17.5711 14.0711 19.25 12 19.25C9.92893 19.25 8.25 17.5711 8.25 15.5ZM9.75 15.5C9.75 14.2574 10.7574 13.25 12 13.25C13.2426 13.25 14.25 14.2574 14.25 15.5C14.25 16.7426 13.2426 17.75 12 17.75C10.7574 17.75 9.75 16.7426 9.75 15.5Z" fill="#7AA2F7" />
          <path d="M16 9C16 9.55228 15.5523 10 15 10C14.4477 10 14 9.55228 14 9C14 8.44772 14.4477 8 15 8C15.5523 8 16 8.44772 16 9Z" fill="#C8D1F0" />
          <path d="M12 10C12.5523 10 13 9.55228 13 9C13 8.44772 12.5523 8 12 8C11.4477 8 11 8.44772 11 9C11 9.55228 11.4477 10 12 10Z" fill="#9ECE6A" />
          <path d="M10 9C10 9.55228 9.55228 10 9 10C8.44772 10 8 9.55228 8 9C8 8.44772 8 8 9 8C9.55228 8 10 8.44772 10 9Z" fill="#C8D1F0" />
          <path d="M9 4.75C8.58579 4.75 8.25 5.08579 8.25 5.5C8.25 5.91421 8.58579 6.25 9 6.25H15C15.4142 6.25 15.75 5.91421 15.75 5.5C15.75 5.08579 15.4142 4.75 15 4.75H9Z" fill="#9ECE6A" />
        </svg>
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
