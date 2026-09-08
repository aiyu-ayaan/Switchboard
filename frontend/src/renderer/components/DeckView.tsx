// Deck surface.
//
// The deck is a launcher first and an editor second. In launch mode the keys
// fill the pane and a single click fires the action, the way the physical
// hardware behaves. Editing is a mode you opt into, which is when the
// inspector appears and clicks select instead of launching.
//
// Configuration is saved explicitly. An earlier revision auto-saved on a
// debounce and cancelled the pending write on unmount, so the last edit before
// leaving the view was silently dropped; leaving now flushes instead.
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Bell,
  Bookmark,
  Calendar,
  Camera,
  Check,
  ChevronLeft,
  ChevronRight,
  Clock,
  Code,
  Command,
  Compass,
  Cpu,
  FileText,
  Flame,
  Folder,
  Globe,
  Headphones,
  Home,
  Layers,
  Link,
  Linkedin,
  Loader2,
  Lock,
  Mic,
  Monitor,
  Moon,
  Music,
  Pencil,
  Play,
  Plus,
  Power,
  RefreshCw,
  RotateCcw,
  Save,
  Search,
  Settings,
  SkipBack,
  SkipForward,
  Sparkles,
  Sun,
  Terminal,
  Trash2,
  Tv,
  Volume1,
  Volume2,
  VolumeX,
  Youtube,
  Zap
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import type {
  DeckAction,
  DeckConfig,
  DeckInfobar,
  DeckKey,
  DeckPage,
  InstalledApp,
  LocalState
} from '../../shared/types';

// ---------------------------------------------------------------------------
// Catalogue
// ---------------------------------------------------------------------------

export const ICONS_MAP: Record<string, LucideIcon> = {
  home: Home,
  notes: FileText,
  folder: Folder,
  calendar: Calendar,
  google: Globe,
  youtube: Youtube,
  spotify: Music,
  linkedin: Linkedin,
  terminal: Terminal,
  play_pause: Play,
  skip_next: SkipForward,
  skip_prev: SkipBack,
  volume_up: Volume2,
  volume_down: Volume1,
  volume_mute: VolumeX,
  lock: Lock,
  camera: Camera,
  code: Code,
  monitor: Monitor,
  settings: Settings,
  mic: Mic,
  headphones: Headphones,
  sparkles: Sparkles,
  cpu: Cpu,
  layers: Layers,
  flame: Flame,
  sun: Sun,
  moon: Moon,
  tv: Tv,
  compass: Compass,
  link: Link,
  bookmark: Bookmark,
  bell: Bell,
  power: Power
};

const ICON_LABELS: Record<string, string> = {
  home: 'Home',
  notes: 'Notes / Docs',
  folder: 'Files & Folders',
  calendar: 'Calendar',
  google: 'Web & Search',
  youtube: 'YouTube',
  spotify: 'Music',
  linkedin: 'LinkedIn',
  terminal: 'Terminal',
  play_pause: 'Play / Pause',
  skip_next: 'Next Track',
  skip_prev: 'Previous Track',
  volume_up: 'Volume Up',
  volume_down: 'Volume Down',
  volume_mute: 'Mute',
  lock: 'Lock System',
  camera: 'Camera / Capture',
  code: 'Developer',
  monitor: 'Display',
  settings: 'Preferences',
  mic: 'Microphone',
  headphones: 'Audio Output',
  sparkles: 'AI / Assistant',
  cpu: 'Hardware',
  layers: 'Windows',
  flame: 'Trending',
  sun: 'Brightness Up',
  moon: 'Brightness Down',
  tv: 'Media Stream',
  compass: 'Explore',
  link: 'Quick Link',
  bookmark: 'Bookmark',
  bell: 'Notification',
  power: 'Power'
};

const ACTION_TYPES: Array<{ id: DeckAction['type']; label: string; icon: LucideIcon; hint: string }> = [
  { id: 'app', label: 'App', icon: Terminal, hint: 'Launch a desktop application' },
  { id: 'url', label: 'Link', icon: Globe, hint: 'Open a website in the default browser' },
  { id: 'hotkey', label: 'Hotkey', icon: Command, hint: 'Send a keyboard shortcut to the host' },
  { id: 'media', label: 'Media', icon: Music, hint: 'Transport and volume control' },
  { id: 'system', label: 'System', icon: Monitor, hint: 'Lock, capture, brightness' },
  { id: 'page', label: 'Page', icon: Layers, hint: 'Jump to another deck page' }
];

const TILE_COLOR_PRESETS = [
  '#21222d',
  '#16161e',
  '#1a1b26',
  '#292b38',
  '#24283b',
  '#1f2d48',
  '#1b2d28',
  '#321e2e',
  '#2d2418',
  '#2a1c2f'
];

const ICON_COLOR_PRESETS = [
  '#7aa2f7',
  '#7dcfff',
  '#9ece6a',
  '#e0af68',
  '#ff9e64',
  '#f7768e',
  '#bb9af7',
  '#c8d1f0',
  '#ffffff',
  '#38bdf8'
];

const HOTKEY_PRESETS = [
  { label: 'Show Desktop', chord: 'win+d' },
  { label: 'Task Manager', chord: 'ctrl+shift+esc' },
  { label: 'Screenshot', chord: 'win+shift+s' },
  { label: 'Switch App', chord: 'alt+tab' },
  { label: 'File Explorer', chord: 'win+e' },
  { label: 'Copy', chord: 'ctrl+c' },
  { label: 'Paste', chord: 'ctrl+v' },
  { label: 'Undo', chord: 'ctrl+z' }
];

const MEDIA_PRESETS: Array<{ label: string; value: string; icon: LucideIcon }> = [
  { label: 'Play / Pause', value: 'toggle', icon: Play },
  { label: 'Next Track', value: 'next', icon: SkipForward },
  { label: 'Previous Track', value: 'prev', icon: SkipBack },
  { label: 'Volume +5%', value: 'vol_up', icon: Volume2 },
  { label: 'Volume -5%', value: 'vol_down', icon: Volume1 },
  { label: 'Toggle Mute', value: 'mute', icon: VolumeX }
];

const SYSTEM_PRESETS: Array<{ label: string; value: string; icon: LucideIcon }> = [
  { label: 'Lock Workstation', value: 'lock', icon: Lock },
  { label: 'Take Screenshot', value: 'screenshot', icon: Camera },
  { label: 'Brightness +10%', value: 'bright_up', icon: Sun },
  { label: 'Brightness -10%', value: 'bright_down', icon: Moon }
];

const URL_PRESETS = [
  { label: 'Google', url: 'https://google.com' },
  { label: 'GitHub', url: 'https://github.com' },
  { label: 'YouTube', url: 'https://youtube.com' },
  { label: 'Spotify', url: 'https://open.spotify.com' },
  { label: 'X', url: 'https://x.com' },
  { label: 'ChatGPT', url: 'https://chatgpt.com' }
];

const BADGE_PRESETS = ['ACTIVE', 'LIVE', 'REC', 'ON', 'OFF', 'MUTE', 'NEW', 'HOT'];

const KEYS_PER_PAGE = 8;

const blankKey = (index: number): DeckKey => ({
  index,
  title: '',
  icon: 'code',
  bgColor: '#21222d',
  iconColor: '#7aa2f7',
  action: { type: 'url', value: '' }
});

const iconForApp = (app: InstalledApp): LucideIcon => {
  if (app.icon && ICONS_MAP[app.icon]) return ICONS_MAP[app.icon];
  const name = app.name.toLowerCase();
  if (/chrome|edge|firefox|browser|safari|brave|opera/.test(name)) return Globe;
  if (/term|shell|power|cmd|bash|wsl/.test(name)) return Terminal;
  if (/note|word|doc|txt|writer|obsidian|notion/.test(name)) return FileText;
  if (/music|spotify|sound|audio|player|vlc/.test(name)) return Music;
  if (/camera|photo|screen|capture|snip/.test(name)) return Camera;
  return Code;
};

/** True when a key has never been configured — nothing to launch. */
const isEmptyKey = (key: DeckKey): boolean => !key.action.value && !key.title;

// ---------------------------------------------------------------------------
// Key tile
// ---------------------------------------------------------------------------

interface DeckKeyTileProps {
  deckKey: DeckKey;
  slot: number;
  selected: boolean;
  editing: boolean;
  firing: boolean;
  /** Icon just read from the shell, outranking whatever the key has stored. */
  liveIcon?: string;
  onActivate: () => void;
}

const DeckKeyTile: React.FC<DeckKeyTileProps> = ({
  deckKey,
  slot,
  selected,
  editing,
  firing,
  liveIcon,
  onActivate
}) => {
  const Glyph = ICONS_MAP[deckKey.icon] ?? Code;
  const empty = isEmptyKey(deckKey);
  const iconColor = deckKey.iconColor || '#7aa2f7';
  const art = liveIcon || deckKey.iconData;

  return (
    <button
      type="button"
      onClick={onActivate}
      title={editing ? `Configure key ${slot + 1}` : deckKey.title || `Key ${slot + 1}`}
      aria-pressed={editing ? selected : undefined}
      style={{ backgroundColor: empty ? undefined : deckKey.bgColor || '#21222d' }}
      className={`group relative flex aspect-[4/3] flex-col items-center justify-center gap-2 rounded-2xl border transition-all duration-150
        ${empty ? 'border-dashed border-edge bg-canvas/60' : 'border-edge'}
        ${selected && editing ? 'border-accent ring-2 ring-accent/40' : ''}
        ${firing ? 'scale-[0.94] border-level ring-2 ring-level/50' : 'active:scale-[0.96]'}
        ${!editing && !empty ? 'hover:-translate-y-0.5 hover:border-accent/60 hover:shadow-lg hover:shadow-black/40' : ''}
        ${editing ? 'hover:border-accent/60' : ''}`}
    >
      {/* Slot number, only while arranging the deck. */}
      {editing && (
        <span className="absolute left-2 top-1.5 font-mono text-[10px] text-ink-faint">
          {slot + 1}
        </span>
      )}

      {deckKey.badge && (
        <span className="absolute right-2 top-2 rounded bg-accent/25 px-1.5 py-0.5 font-mono text-[9px] font-semibold uppercase tracking-wider text-accent">
          {deckKey.badge}
        </span>
      )}

      {empty ? (
        <>
          <Plus className="h-6 w-6 text-ink-faint transition group-hover:text-accent" />
          <span className="text-micro text-ink-faint">Empty</span>
        </>
      ) : (
        <>
          {/* The host's real application icon beats any glyph we could pick. */}
          {art ? (
            <img
              src={art}
              alt=""
              className="h-9 w-9 object-contain drop-shadow"
              draggable={false}
            />
          ) : (
            <Glyph className="h-8 w-8" style={{ color: iconColor }} strokeWidth={1.75} />
          )}
          <span className="line-clamp-2 px-1.5 text-center text-tiny font-medium leading-tight text-ink">
            {deckKey.title || `Key ${slot + 1}`}
          </span>
        </>
      )}

      {/* Launch affordance: only meaningful outside edit mode. */}
      {!editing && !empty && (
        <span className="pointer-events-none absolute inset-x-0 bottom-1.5 flex justify-center opacity-0 transition-opacity group-hover:opacity-100">
          <span className="inline-flex items-center gap-1 rounded-full bg-black/50 px-2 py-0.5 font-mono text-[9px] uppercase tracking-wider text-level">
            <Zap className="h-2.5 w-2.5" /> Launch
          </span>
        </span>
      )}
    </button>
  );
};

// ---------------------------------------------------------------------------
// Deck view
// ---------------------------------------------------------------------------

export interface DeckViewProps {
  state: LocalState;
}

export const DeckView: React.FC<DeckViewProps> = ({ state }) => {
  const [config, setConfig] = useState<DeckConfig | null>(null);
  const [pageIndex, setPageIndex] = useState(0);
  const [keyIndex, setKeyIndex] = useState(0);
  const [editing, setEditing] = useState(false);

  const [apps, setApps] = useState<InstalledApp[]>([]);
  const [nativeIcons, setNativeIcons] = useState<Record<string, string>>({});
  const [siteIcons, setSiteIcons] = useState<Record<string, string>>({});
  const [appsQuery, setAppsQuery] = useState('');
  const [loadingApps, setLoadingApps] = useState(false);

  const [dirty, setDirty] = useState(false);
  const [saving, setSaving] = useState(false);
  const [notice, setNotice] = useState<{ type: 'success' | 'error' | 'info'; text: string } | null>(
    null
  );
  const [firingSlot, setFiringSlot] = useState<number | null>(null);

  const [renamingPage, setRenamingPage] = useState(false);
  const [pageNameInput, setPageNameInput] = useState('');
  const [iconQuery, setIconQuery] = useState('');
  const [now, setNow] = useState(() => new Date());

  // The unsaved config has to be reachable from the unmount handler, which only
  // ever runs with the first render's closure.
  const pendingRef = useRef<{ config: DeckConfig; dirty: boolean }>({
    config: { activePage: 0, infobar: { mode: 'clock', customText: '' }, pages: [] },
    dirty: false
  });

  const flash = useCallback((type: 'success' | 'error' | 'info', text: string) => {
    setNotice({ type, text });
    window.setTimeout(() => setNotice(null), 2600);
  }, []);

  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 1000);
    return () => window.clearInterval(timer);
  }, []);

  // ---- load -----------------------------------------------------------------

  const loadConfig = useCallback(async () => {
    try {
      const initial = await window.switchboard.deck.get();
      setConfig(initial);
      pendingRef.current = { config: initial, dirty: false };
      setDirty(false);
      setPageIndex(Math.min(initial.activePage ?? 0, Math.max(0, initial.pages.length - 1)));
    } catch {
      flash('error', 'Could not read the deck configuration');
    }
  }, [flash]);

  const loadApps = useCallback(async () => {
    setLoadingApps(true);
    try {
      const list = await window.switchboard.deck.apps();
      setApps(list);
      const entries = await Promise.all(
        list.slice(0, 200).map(async (app) => {
          try {
            return [app.path, await window.switchboard.deck.appIcon(app.path)] as const;
          } catch {
            return [app.path, ''] as const;
          }
        })
      );
      setNativeIcons(Object.fromEntries(entries.filter(([, icon]) => Boolean(icon))));
    } catch {
      // The picker still works from a typed path.
    } finally {
      setLoadingApps(false);
    }
  }, []);

  useEffect(() => {
    void loadConfig();
    void loadApps();
  }, [loadConfig, loadApps]);

  // A link key should wear the site's own logo rather than a generic globe.
  // Resolution happens in the main process, which is the only side of the app
  // that makes network requests; a URL that yields nothing is cached as '' so
  // it is asked about once.
  useEffect(() => {
    if (!config) return;
    const pending = Array.from(
      new Set(
        config.pages
          .flatMap((page) => page.keys ?? [])
          .filter((key) => key.action.type === 'url' && key.action.value)
          .map((key) => key.action.value)
      )
    ).filter((url) => !(url in siteIcons));
    if (pending.length === 0) return;

    let cancelled = false;
    void (async () => {
      const entries = await Promise.all(
        pending.map(
          async (url) =>
            [url, await window.switchboard.deck.siteIcon(url).catch(() => '')] as const
        )
      );
      if (!cancelled) setSiteIcons((prev) => ({ ...prev, ...Object.fromEntries(entries) }));
    })();
    return () => {
      cancelled = true;
    };
  }, [config, siteIcons]);

  // ---- save -----------------------------------------------------------------

  /** Stages an edit locally. Nothing reaches the daemon until Save. */
  const stage = useCallback((updated: DeckConfig) => {
    setConfig(updated);
    pendingRef.current = { config: updated, dirty: true };
    setDirty(true);
  }, []);

  const save = useCallback(async () => {
    const target = pendingRef.current.config;
    setSaving(true);
    try {
      await window.switchboard.deck.set(target);
      pendingRef.current = { config: target, dirty: false };
      setDirty(false);
      flash('success', 'Deck saved');
    } catch {
      flash('error', 'Save failed — the Switchboard daemon did not respond');
    } finally {
      setSaving(false);
    }
  }, [flash]);

  // Leaving the view must not discard work. Switching to another view unmounts
  // this component, which is exactly when the old debounce used to be cancelled.
  useEffect(() => {
    return () => {
      const { config: pending, dirty: unsaved } = pendingRef.current;
      if (unsaved) void window.switchboard.deck.set(pending).catch(() => undefined);
    };
  }, []);

  // Ctrl+S is the reflex for a surface with an explicit save.
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
        event.preventDefault();
        if (pendingRef.current.dirty) void save();
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [save]);

  // ---- derived --------------------------------------------------------------

  const activePage: DeckPage | undefined = config?.pages[pageIndex] ?? config?.pages[0];

  const currentKeys: DeckKey[] = useMemo(
    () =>
      Array.from(
        { length: KEYS_PER_PAGE },
        (_, idx) => activePage?.keys?.find((k) => k.index === idx) ?? blankKey(idx)
      ),
    [activePage]
  );

  const selectedKey = currentKeys[keyIndex] ?? currentKeys[0];

  // What a key actually shows: art this machine can resolve right now -- the
  // shell's icon for an app, the site's own for a link -- ahead of the copy
  // saved into the config for the phone.
  const liveArt = useCallback(
    (key: DeckKey): string | undefined => {
      if (key.action.type === 'app') return nativeIcons[key.action.value] || undefined;
      if (key.action.type === 'url') return siteIcons[key.action.value] || undefined;
      return undefined;
    },
    [nativeIcons, siteIcons]
  );

  const selectedArt = liveArt(selectedKey) ?? selectedKey.iconData;

  // The phone renders whatever `iconData` the config carries -- it cannot reach
  // a website from behind the desktop, and an app key already has its icon
  // written in when it is picked. So a resolved site logo is written back here,
  // which is what makes a link key look like the site on the phone as well.
  //
  // Only fills an empty slot, so a custom icon a user chose is never replaced,
  // and only while nothing is unsaved, so derived art never commits a
  // half-finished edit.
  useEffect(() => {
    if (!config || dirty) return;
    let filled = false;
    const enriched: DeckConfig = {
      ...config,
      pages: config.pages.map((page) => ({
        ...page,
        keys: (page.keys ?? []).map((key) => {
          if (key.action.type !== 'url' || key.iconData) return key;
          const art = siteIcons[key.action.value];
          if (!art) return key;
          filled = true;
          return { ...key, iconData: art };
        })
      }))
    };
    if (!filled) return;
    setConfig(enriched);
    pendingRef.current = { config: enriched, dirty: false };
    void window.switchboard.deck.set(enriched).catch(() => undefined);
  }, [config, dirty, siteIcons]);

  const filteredApps = useMemo(() => {
    const q = appsQuery.trim().toLowerCase();
    if (!q) return apps.slice(0, 60);
    return apps.filter(
      (app) => app.name.toLowerCase().includes(q) || app.path.toLowerCase().includes(q)
    );
  }, [apps, appsQuery]);

  const filteredIcons = useMemo(() => {
    const entries = Object.entries(ICONS_MAP);
    const q = iconQuery.trim().toLowerCase();
    if (!q) return entries;
    return entries.filter(
      ([key]) => key.includes(q) || (ICON_LABELS[key] || '').toLowerCase().includes(q)
    );
  }, [iconQuery]);

  // ---- mutations ------------------------------------------------------------

  const patchKey = useCallback(
    (patch: Partial<DeckKey>) => {
      if (!config) return;
      const keys = currentKeys.map((k) => (k.index === keyIndex ? { ...k, ...patch } : k));
      stage({
        ...config,
        pages: config.pages.map((p, idx) => (idx === pageIndex ? { ...p, keys } : p))
      });
    },
    [config, currentKeys, keyIndex, pageIndex, stage]
  );

  const patchInfobar = useCallback(
    (patch: Partial<DeckInfobar>) => {
      if (!config) return;
      stage({ ...config, infobar: { ...config.infobar, ...patch } });
    },
    [config, stage]
  );

  const selectPage = useCallback(
    (idx: number) => {
      if (!config) return;
      setPageIndex(idx);
      setKeyIndex(0);
      setRenamingPage(false);
      stage({ ...config, activePage: idx });
    },
    [config, stage]
  );

  const addPage = useCallback(() => {
    if (!config) return;
    const nextIndex = config.pages.length;
    const page: DeckPage = {
      id: `page-${Date.now()}`,
      name: `Page ${nextIndex + 1}`,
      keys: Array.from({ length: KEYS_PER_PAGE }, (_, idx) => blankKey(idx))
    };
    setPageIndex(nextIndex);
    setKeyIndex(0);
    stage({ ...config, activePage: nextIndex, pages: [...config.pages, page] });
  }, [config, stage]);

  const deletePage = useCallback(() => {
    if (!config || config.pages.length <= 1) return;
    const nextIndex = Math.max(0, pageIndex - 1);
    setPageIndex(nextIndex);
    setKeyIndex(0);
    stage({
      ...config,
      activePage: nextIndex,
      pages: config.pages.filter((_, idx) => idx !== pageIndex)
    });
  }, [config, pageIndex, stage]);

  const commitPageName = useCallback(() => {
    setRenamingPage(false);
    if (!config || !pageNameInput.trim()) return;
    stage({
      ...config,
      pages: config.pages.map((p, idx) =>
        idx === pageIndex ? { ...p, name: pageNameInput.trim() } : p
      )
    });
  }, [config, pageIndex, pageNameInput, stage]);

  const clearKey = useCallback(() => {
    patchKey({ ...blankKey(keyIndex), iconData: undefined });
    flash('info', `Key ${keyIndex + 1} cleared`);
  }, [keyIndex, patchKey, flash]);

  /** Runs a key against the host. */
  const fire = useCallback(
    async (key: DeckKey) => {
      if (isEmptyKey(key)) {
        setEditing(true);
        setKeyIndex(key.index);
        return;
      }

      // Page jumps are a client concern; the host has nothing to execute.
      if (key.action.type === 'page') {
        const target = config?.pages.findIndex((p) => p.id === key.action.value) ?? -1;
        if (target >= 0) selectPage(target);
        return;
      }

      setFiringSlot(key.index);
      window.setTimeout(() => setFiringSlot(null), 220);
      try {
        await window.switchboard.deck.action({
          pageId: activePage?.id,
          keyIndex: key.index,
          action: key.action
        });
        flash('success', `${key.title || `Key ${key.index + 1}`} launched`);
      } catch (err) {
        flash('error', err instanceof Error ? err.message : 'The host refused the action');
      }
    },
    [activePage, config, selectPage, flash]
  );

  // Adopts an installed application, real desktop icon included. Picking from
  // the list re-titles the key: keeping a previous app's name on a key that now
  // launches something else is the one outcome nobody wants, and the Label
  // field above stays free to override it afterwards.
  const pickApp = useCallback(
    (app: InstalledApp) => {
      patchKey({
        title: app.name,
        icon: app.icon || 'code',
        iconData: nativeIcons[app.path] || undefined,
        action: { type: 'app', value: app.path || app.name }
      });
    },
    [patchKey, nativeIcons]
  );

  // ---- render ---------------------------------------------------------------

  if (!config || !activePage) {
    return (
      <div className="flex flex-1 items-center justify-center bg-canvas">
        <div className="flex flex-col items-center gap-3">
          <Loader2 className="h-6 w-6 animate-spin text-accent" />
          <p className="font-mono text-tiny text-ink-faint">Opening deck…</p>
        </div>
      </div>
    );
  }

  const infobarText = (() => {
    switch (config.infobar.mode) {
      case 'text':
        return config.infobar.customText || 'Custom text';
      case 'page':
        return `${activePage.name} · ${pageIndex + 1} of ${config.pages.length}`;
      case 'media': {
        const media = state.host.media;
        if (media.active && media.title) {
          return media.artist ? `${media.title} - ${media.artist}` : media.title;
        }
        return state.host.volume.muted ? 'Muted' : `Volume ${state.host.volume.level}%`;
      }
      default:
        return now.toLocaleTimeString();
    }
  })();

  return (
    <main className="flex flex-1 overflow-hidden bg-canvas">
      {/* ---------------- deck ---------------- */}
      <section className="flex min-w-0 flex-1 flex-col items-center overflow-y-auto px-6 py-5">
        {/* A deck is a physical object: the keys keep a thumb-sized footprint
            instead of stretching to whatever width the window happens to be. */}
        <div className="flex w-full max-w-[680px] flex-col">
        <header className="flex flex-wrap items-center justify-between gap-3 pb-5">
          <div className="flex min-w-0 items-center gap-3">
            <span className="grid h-9 w-9 shrink-0 place-items-center rounded-xl bg-accent/15 text-accent">
              <Layers className="h-4.5 w-4.5" />
            </span>
            <div className="min-w-0">
              <div className="flex items-center gap-2">
                <h1 className="truncate text-base font-semibold text-ink">Deck</h1>
                <span className="rounded bg-accent/15 px-1.5 py-0.5 font-mono text-[9px] font-bold tracking-wider text-accent uppercase leading-none">
                  ALPHA
                </span>
              </div>
              <p className="truncate text-micro text-ink-dim">
                {editing
                  ? 'Pick a key, then set what it does'
                  : 'Click a key to launch it on this machine'}
              </p>
            </div>
          </div>

          <div className="flex items-center gap-2">
            {dirty && (
              <span className="inline-flex items-center gap-1.5 rounded-full bg-warn/15 px-2.5 py-1 text-micro font-medium text-warn">
                <span className="h-1.5 w-1.5 rounded-full bg-warn" /> Unsaved
              </span>
            )}

            <button
              type="button"
              onClick={() => void save()}
              disabled={!dirty || saving}
              title="Save deck (Ctrl+S)"
              className={`inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-tiny font-medium transition
                ${
                  dirty
                    ? 'bg-accent text-[#0b1220] hover:brightness-110'
                    : 'cursor-default bg-card text-ink-faint'
                }`}
            >
              {saving ? (
                <Loader2 className="h-3.5 w-3.5 animate-spin" />
              ) : dirty ? (
                <Save className="h-3.5 w-3.5" />
              ) : (
                <Check className="h-3.5 w-3.5 text-level" />
              )}
              {saving ? 'Saving' : dirty ? 'Save' : 'Saved'}
            </button>

            <button
              type="button"
              onClick={() => setEditing((v) => !v)}
              className={`inline-flex items-center gap-1.5 rounded-lg border px-3 py-1.5 text-tiny font-medium transition ${
                editing
                  ? 'border-accent bg-accent/15 text-accent'
                  : 'border-edge bg-card text-ink hover:border-accent/60'
              }`}
            >
              <Pencil className="h-3.5 w-3.5" />
              {editing ? 'Done' : 'Edit'}
            </button>
          </div>
        </header>

        {/* Page rail */}
        <div className="flex flex-wrap items-center gap-1.5 pb-4">
          {config.pages.map((page, idx) =>
            renamingPage && idx === pageIndex ? (
              <input
                key={page.id}
                autoFocus
                value={pageNameInput}
                onChange={(e) => setPageNameInput(e.target.value)}
                onBlur={commitPageName}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') commitPageName();
                  if (e.key === 'Escape') setRenamingPage(false);
                }}
                className="w-32 rounded-full border border-accent bg-card px-3 py-1.5 text-tiny text-ink focus:outline-none"
              />
            ) : (
              <button
                key={page.id}
                type="button"
                onClick={() => selectPage(idx)}
                onDoubleClick={() => {
                  if (!editing) return;
                  setPageNameInput(page.name);
                  setRenamingPage(true);
                }}
                className={`rounded-full px-3.5 py-1.5 text-tiny font-medium transition ${
                  idx === pageIndex
                    ? 'bg-accent/20 text-accent'
                    : 'text-ink-dim hover:bg-card hover:text-ink'
                }`}
              >
                {page.name}
              </button>
            )
          )}

          {editing && (
            <>
              <button
                type="button"
                onClick={addPage}
                title="Add page"
                className="grid h-7 w-7 place-items-center rounded-full border border-edge text-ink-dim transition hover:border-accent hover:text-accent"
              >
                <Plus className="h-3.5 w-3.5" />
              </button>
              {config.pages.length > 1 && (
                <button
                  type="button"
                  onClick={deletePage}
                  title="Delete this page"
                  className="grid h-7 w-7 place-items-center rounded-full border border-edge text-ink-dim transition hover:border-danger hover:text-danger"
                >
                  <Trash2 className="h-3.5 w-3.5" />
                </button>
              )}
            </>
          )}
        </div>

        {/* The deck itself */}
        <div className="rounded-3xl border border-edge bg-sidebar p-4 shadow-2xl shadow-black/40">
          <div className="grid grid-cols-4 gap-3">
            {currentKeys.map((key, slot) => (
              <DeckKeyTile
                key={slot}
                deckKey={key}
                slot={slot}
                selected={slot === keyIndex}
                editing={editing}
                firing={firingSlot === slot}
                liveIcon={liveArt(key)}
                onActivate={() => {
                  if (editing) setKeyIndex(slot);
                  else void fire(key);
                }}
              />
            ))}
          </div>

          {/* Infobar strip, mirroring the hardware display */}
          <div className="mt-3 flex items-center gap-2 rounded-2xl border border-edge bg-canvas px-3 py-2">
            <button
              type="button"
              onClick={() => selectPage((pageIndex - 1 + config.pages.length) % config.pages.length)}
              className="grid h-7 w-7 place-items-center rounded-lg text-ink-dim transition hover:bg-card hover:text-ink"
            >
              <ChevronLeft className="h-4 w-4" />
            </button>
            <div className="flex flex-1 items-center justify-center gap-2 font-mono text-tiny text-ink">
              <Clock className="h-3.5 w-3.5 text-accent" />
              {infobarText}
            </div>
            <button
              type="button"
              onClick={() => selectPage((pageIndex + 1) % config.pages.length)}
              className="grid h-7 w-7 place-items-center rounded-lg text-ink-dim transition hover:bg-card hover:text-ink"
            >
              <ChevronRight className="h-4 w-4" />
            </button>
          </div>
        </div>

        {notice && (
          <p
            className={`mt-4 rounded-lg px-3 py-2 text-tiny ${
              notice.type === 'error'
                ? 'bg-danger/15 text-danger'
                : notice.type === 'success'
                  ? 'bg-level/15 text-level'
                  : 'bg-card text-ink-dim'
            }`}
          >
            {notice.text}
          </p>
        )}

        {editing && (
          <section className="mt-5 rounded-2xl border border-edge bg-card p-4">
            <h2 className="text-tiny font-semibold text-ink">Infobar</h2>
            <div className="mt-3 grid grid-cols-4 gap-2">
              {(['clock', 'media', 'page', 'text'] as const).map((mode) => (
                <button
                  key={mode}
                  type="button"
                  onClick={() => patchInfobar({ mode })}
                  className={`rounded-lg border px-2 py-1.5 text-tiny capitalize transition ${
                    config.infobar.mode === mode
                      ? 'border-accent bg-accent/15 text-accent'
                      : 'border-edge text-ink-dim hover:text-ink'
                  }`}
                >
                  {mode}
                </button>
              ))}
            </div>
            {config.infobar.mode === 'text' && (
              <input
                value={config.infobar.customText}
                onChange={(e) => patchInfobar({ customText: e.target.value })}
                placeholder="Shown on the deck display"
                className="mt-3 w-full rounded-lg border border-edge bg-canvas px-3 py-2 text-tiny text-ink focus:border-accent focus:outline-none"
              />
            )}
          </section>
        )}
        </div>
      </section>

      {/* ---------------- inspector ---------------- */}
      {editing && (
        <aside className="flex w-[380px] shrink-0 flex-col overflow-y-auto border-l border-edge bg-sidebar">
          <div className="flex items-center justify-between gap-2 border-b border-edge px-4 py-3">
            <div className="flex min-w-0 items-center gap-2.5">
              <span
                className="grid h-9 w-9 shrink-0 place-items-center rounded-xl border border-edge"
                style={{ backgroundColor: selectedKey.bgColor || '#21222d' }}
              >
                {selectedArt ? (
                  <img src={selectedArt} alt="" className="h-5 w-5 object-contain" />
                ) : (
                  React.createElement(ICONS_MAP[selectedKey.icon] ?? Code, {
                    className: 'h-4.5 w-4.5',
                    style: { color: selectedKey.iconColor || '#7aa2f7' }
                  })
                )}
              </span>
              <div className="min-w-0">
                <p className="font-mono text-[10px] uppercase tracking-wider text-ink-faint">
                  Key {keyIndex + 1}
                </p>
                <p className="truncate text-tiny font-medium text-ink">
                  {selectedKey.title || 'Unassigned'}
                </p>
              </div>
            </div>
            <div className="flex items-center gap-1">
              <button
                type="button"
                onClick={() => void fire(selectedKey)}
                disabled={isEmptyKey(selectedKey)}
                title="Run this key now"
                className="grid h-7 w-7 place-items-center rounded-lg text-ink-dim transition hover:bg-card hover:text-level disabled:opacity-40"
              >
                <Play className="h-3.5 w-3.5" />
              </button>
              <button
                type="button"
                onClick={clearKey}
                title="Clear this key"
                className="grid h-7 w-7 place-items-center rounded-lg text-ink-dim transition hover:bg-card hover:text-danger"
              >
                <RotateCcw className="h-3.5 w-3.5" />
              </button>
            </div>
          </div>

          <div className="flex flex-col gap-5 p-4">
            {/* Label + badge */}
            <div className="grid grid-cols-[1fr_120px] gap-2">
              <label className="block">
                <span className="text-micro font-medium text-ink-dim">Label</span>
                <input
                  value={selectedKey.title}
                  onChange={(e) => patchKey({ title: e.target.value })}
                  placeholder={`Key ${keyIndex + 1}`}
                  className="mt-1 w-full rounded-lg border border-edge bg-card px-2.5 py-1.5 text-tiny text-ink focus:border-accent focus:outline-none"
                />
              </label>
              <label className="block">
                <span className="text-micro font-medium text-ink-dim">Badge</span>
                <input
                  value={selectedKey.badge ?? ''}
                  onChange={(e) => patchKey({ badge: e.target.value })}
                  placeholder="e.g. LIVE"
                  className="mt-1 w-full rounded-lg border border-edge bg-card px-2.5 py-1.5 text-tiny text-ink focus:border-accent focus:outline-none"
                />
              </label>
            </div>
            <div className="-mt-3 flex flex-wrap gap-1">
              {BADGE_PRESETS.map((badge) => (
                <button
                  key={badge}
                  type="button"
                  onClick={() => patchKey({ badge })}
                  className="rounded border border-edge px-1.5 py-0.5 font-mono text-[10px] text-ink-dim transition hover:border-accent hover:text-accent"
                >
                  {badge}
                </button>
              ))}
            </div>

            {/* Action type */}
            <div>
              <span className="text-micro font-medium text-ink-dim">Action</span>
              <div className="mt-1.5 grid grid-cols-3 gap-1.5">
                {ACTION_TYPES.map(({ id, label, icon: Icon, hint }) => (
                  <button
                    key={id}
                    type="button"
                    title={hint}
                    onClick={() => patchKey({ action: { type: id, value: '' }, iconData: undefined })}
                    className={`flex flex-col items-center gap-1 rounded-lg border px-2 py-2 text-micro transition ${
                      selectedKey.action.type === id
                        ? 'border-accent bg-accent/15 text-accent'
                        : 'border-edge bg-card text-ink-dim hover:text-ink'
                    }`}
                  >
                    <Icon className="h-3.5 w-3.5" />
                    {label}
                  </button>
                ))}
              </div>
            </div>

            {/* Per-type editor */}
            {selectedKey.action.type === 'app' && (
              <div className="flex flex-col gap-2">
                <div className="flex items-center justify-between">
                  <span className="text-micro font-medium text-ink-dim">Installed applications</span>
                  <button
                    type="button"
                    onClick={() => void loadApps()}
                    className="inline-flex items-center gap-1 text-micro text-accent hover:underline"
                  >
                    <RefreshCw className={`h-3 w-3 ${loadingApps ? 'animate-spin' : ''}`} />
                    Refresh
                  </button>
                </div>

                <div className="relative">
                  <Search className="absolute left-2.5 top-2.5 h-3.5 w-3.5 text-ink-faint" />
                  <input
                    value={appsQuery}
                    onChange={(e) => setAppsQuery(e.target.value)}
                    placeholder="Search programs…"
                    className="w-full rounded-lg border border-edge bg-card py-1.5 pl-8 pr-3 text-tiny text-ink focus:border-accent focus:outline-none"
                  />
                </div>

                <div className="max-h-64 divide-y divide-edge/40 overflow-y-auto rounded-lg border border-edge bg-card">
                  {filteredApps.length === 0 ? (
                    <p className="p-4 text-center text-micro text-ink-faint">
                      {loadingApps ? 'Reading the Start Menu…' : 'No matching applications'}
                    </p>
                  ) : (
                    filteredApps.map((app) => {
                      const picked = selectedKey.action.value === (app.path || app.name);
                      const Fallback = iconForApp(app);
                      const native = nativeIcons[app.path];
                      return (
                        <button
                          key={app.path || app.name}
                          type="button"
                          onClick={() => pickApp(app)}
                          className={`flex w-full items-center gap-2.5 px-3 py-2 text-left transition ${
                            picked ? 'bg-accent/20 font-medium text-accent' : 'text-ink hover:bg-raised'
                          }`}
                        >
                          <span className="grid h-7 w-7 shrink-0 place-items-center overflow-hidden rounded bg-canvas">
                            {native ? (
                              <img src={native} alt="" className="h-5 w-5 object-contain" />
                            ) : (
                              <Fallback className="h-3.5 w-3.5 text-accent" />
                            )}
                          </span>
                          <span className="min-w-0 flex-1">
                            <span className="block truncate text-tiny">{app.name}</span>
                            <span className="block truncate font-mono text-[10px] text-ink-faint">
                              {app.path}
                            </span>
                          </span>
                          {picked && <Check className="h-3.5 w-3.5 shrink-0 text-accent" />}
                        </button>
                      );
                    })
                  )}
                </div>

                <label className="block">
                  <span className="text-micro font-medium text-ink-dim">Or a target path</span>
                  <input
                    value={selectedKey.action.value}
                    onChange={(e) =>
                      patchKey({ action: { type: 'app', value: e.target.value } })
                    }
                    placeholder="C:\Windows\notepad.exe"
                    className="mt-1 w-full rounded-lg border border-edge bg-card px-2.5 py-1.5 font-mono text-tiny text-ink focus:border-accent focus:outline-none"
                  />
                </label>
              </div>
            )}

            {selectedKey.action.type === 'url' && (
              <div className="flex flex-col gap-2">
                <input
                  value={selectedKey.action.value}
                  onChange={(e) => patchKey({ action: { type: 'url', value: e.target.value } })}
                  placeholder="https://example.com"
                  className="w-full rounded-lg border border-edge bg-card px-2.5 py-1.5 font-mono text-tiny text-ink focus:border-accent focus:outline-none"
                />
                <div className="flex flex-wrap gap-1.5">
                  {URL_PRESETS.map((preset) => (
                    <button
                      key={preset.url}
                      type="button"
                      onClick={() => patchKey({ action: { type: 'url', value: preset.url } })}
                      className="rounded-lg border border-edge px-2 py-1 text-micro text-ink-dim transition hover:border-accent hover:text-accent"
                    >
                      {preset.label}
                    </button>
                  ))}
                </div>
              </div>
            )}

            {selectedKey.action.type === 'hotkey' && (
              <div className="flex flex-col gap-2">
                <input
                  value={selectedKey.action.value}
                  onChange={(e) => patchKey({ action: { type: 'hotkey', value: e.target.value } })}
                  placeholder="ctrl+shift+n"
                  className="w-full rounded-lg border border-edge bg-card px-2.5 py-1.5 font-mono text-tiny text-ink focus:border-accent focus:outline-none"
                />
                <div className="flex flex-wrap gap-1.5">
                  {HOTKEY_PRESETS.map((preset) => (
                    <button
                      key={preset.chord}
                      type="button"
                      onClick={() =>
                        patchKey({
                          title: selectedKey.title || preset.label,
                          action: { type: 'hotkey', value: preset.chord }
                        })
                      }
                      className="rounded-lg border border-edge px-2 py-1 text-micro text-ink-dim transition hover:border-accent hover:text-accent"
                    >
                      {preset.label}
                    </button>
                  ))}
                </div>
              </div>
            )}

            {selectedKey.action.type === 'media' && (
              <div className="grid grid-cols-2 gap-1.5">
                {MEDIA_PRESETS.map(({ label, value, icon: Icon }) => (
                  <button
                    key={value}
                    type="button"
                    onClick={() =>
                      patchKey({
                        title: selectedKey.title || label,
                        action: { type: 'media', value }
                      })
                    }
                    className={`flex items-center gap-2 rounded-lg border px-2.5 py-2 text-micro transition ${
                      selectedKey.action.value === value
                        ? 'border-accent bg-accent/15 text-accent'
                        : 'border-edge bg-card text-ink-dim hover:text-ink'
                    }`}
                  >
                    <Icon className="h-3.5 w-3.5" />
                    {label}
                  </button>
                ))}
              </div>
            )}

            {selectedKey.action.type === 'system' && (
              <div className="grid grid-cols-2 gap-1.5">
                {SYSTEM_PRESETS.map(({ label, value, icon: Icon }) => (
                  <button
                    key={value}
                    type="button"
                    onClick={() =>
                      patchKey({
                        title: selectedKey.title || label,
                        action: { type: 'system', value }
                      })
                    }
                    className={`flex items-center gap-2 rounded-lg border px-2.5 py-2 text-micro transition ${
                      selectedKey.action.value === value
                        ? 'border-accent bg-accent/15 text-accent'
                        : 'border-edge bg-card text-ink-dim hover:text-ink'
                    }`}
                  >
                    <Icon className="h-3.5 w-3.5" />
                    {label}
                  </button>
                ))}
              </div>
            )}

            {selectedKey.action.type === 'page' && (
              <div className="flex flex-col gap-1.5">
                {config.pages.map((page) => (
                  <button
                    key={page.id}
                    type="button"
                    onClick={() =>
                      patchKey({
                        title: selectedKey.title || page.name,
                        action: { type: 'page', value: page.id }
                      })
                    }
                    className={`rounded-lg border px-2.5 py-2 text-left text-tiny transition ${
                      selectedKey.action.value === page.id
                        ? 'border-accent bg-accent/15 text-accent'
                        : 'border-edge bg-card text-ink-dim hover:text-ink'
                    }`}
                  >
                    {page.name}
                  </button>
                ))}
              </div>
            )}

            {/* Appearance */}
            <div className="flex flex-col gap-3 border-t border-edge pt-4">
              <span className="text-micro font-semibold uppercase tracking-wider text-ink-faint">
                Appearance
              </span>

              {selectedArt && (
                <div className="flex items-center gap-2.5 rounded-lg border border-edge bg-card px-2.5 py-2">
                  <img src={selectedArt} alt="" className="h-6 w-6 object-contain" />
                  <span className="flex-1 text-micro text-ink-dim">Using the app&apos;s own icon</span>
                  <button
                    type="button"
                    onClick={() => patchKey({ iconData: undefined })}
                    className="text-micro text-accent hover:underline"
                  >
                    Use a glyph
                  </button>
                </div>
              )}

              <div>
                <span className="text-micro font-medium text-ink-dim">Tile colour</span>
                <div className="mt-1.5 flex flex-wrap gap-1.5">
                  {TILE_COLOR_PRESETS.map((color) => (
                    <button
                      key={color}
                      type="button"
                      onClick={() => patchKey({ bgColor: color })}
                      style={{ backgroundColor: color }}
                      className={`h-6 w-6 rounded-md border transition ${
                        selectedKey.bgColor === color ? 'border-accent ring-2 ring-accent/40' : 'border-edge'
                      }`}
                    />
                  ))}
                </div>
              </div>

              <div>
                <span className="text-micro font-medium text-ink-dim">Icon colour</span>
                <div className="mt-1.5 flex flex-wrap gap-1.5">
                  {ICON_COLOR_PRESETS.map((color) => (
                    <button
                      key={color}
                      type="button"
                      onClick={() => patchKey({ iconColor: color })}
                      style={{ backgroundColor: color }}
                      className={`h-6 w-6 rounded-md border transition ${
                        selectedKey.iconColor === color
                          ? 'border-accent ring-2 ring-accent/40'
                          : 'border-edge'
                      }`}
                    />
                  ))}
                </div>
              </div>

              <div>
                <div className="flex items-center justify-between">
                  <span className="text-micro font-medium text-ink-dim">Glyph</span>
                  <input
                    value={iconQuery}
                    onChange={(e) => setIconQuery(e.target.value)}
                    placeholder="Search…"
                    className="w-24 rounded border border-edge bg-card px-2 py-0.5 text-micro text-ink focus:border-accent focus:outline-none"
                  />
                </div>
                <div className="mt-1.5 grid max-h-40 grid-cols-6 gap-1.5 overflow-y-auto rounded-lg border border-edge bg-card p-2">
                  {filteredIcons.map(([id, Glyph]) => (
                    <button
                      key={id}
                      type="button"
                      title={ICON_LABELS[id] ?? id}
                      onClick={() => patchKey({ icon: id, iconData: undefined })}
                      className={`grid aspect-square place-items-center rounded-md border transition ${
                        selectedKey.icon === id && !selectedKey.iconData
                          ? 'border-accent bg-accent/15 text-accent'
                          : 'border-transparent text-ink-dim hover:border-edge hover:text-ink'
                      }`}
                    >
                      <Glyph className="h-4 w-4" />
                    </button>
                  ))}
                </div>
              </div>
            </div>
          </div>
        </aside>
      )}
    </main>
  );
};
