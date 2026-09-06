import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  AlertCircle,
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
  Lock,
  Mic,
  Monitor,
  Moon,
  Music,
  Pause,
  Pencil,
  Play,
  Plus,
  Power,
  Radio,
  RefreshCw,
  RotateCcw,
  Search,
  Settings,
  SkipBack,
  SkipForward,
  Sliders,
  Sparkles,
  Sun,
  Terminal,
  Trash2,
  Tv,
  Type,
  Volume1,
  Volume2,
  VolumeX,
  Youtube
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

// ============================================================================
// Icon Catalog & Configuration Presets
// ============================================================================

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
  spotify: 'Music / Spotify',
  linkedin: 'LinkedIn',
  terminal: 'Terminal / Shell',
  play_pause: 'Play / Pause',
  skip_next: 'Next Track',
  skip_prev: 'Previous Track',
  volume_up: 'Volume Up',
  volume_down: 'Volume Down',
  volume_mute: 'Mute',
  lock: 'Lock System',
  camera: 'Camera / Screen',
  code: 'Developer / Code',
  monitor: 'Display / Screen',
  settings: 'Preferences',
  mic: 'Microphone',
  headphones: 'Audio Output',
  sparkles: 'AI / Assistant',
  cpu: 'Hardware / CPU',
  layers: 'Window / Layers',
  flame: 'Fire / Trend',
  sun: 'Brightness / Light',
  moon: 'Night / Dark',
  tv: 'Media Stream',
  compass: 'Browser / Explore',
  link: 'Quick Link',
  bookmark: 'Saved Bookmark',
  bell: 'Notification',
  power: 'Power Action'
};

const ACTION_TYPES: Array<{ id: DeckAction['type']; label: string; icon: LucideIcon; hint: string }> = [
  { id: 'app', label: 'App', icon: Terminal, hint: 'Launch desktop application' },
  { id: 'url', label: 'URL', icon: Globe, hint: 'Open website in default browser' },
  { id: 'hotkey', label: 'Hotkey', icon: Command, hint: 'Simulate keyboard shortcut chord' },
  { id: 'media', label: 'Media', icon: Music, hint: 'Control host media playback & volume' },
  { id: 'system', label: 'System', icon: Monitor, hint: 'Workstation lock, screenshot, brightness' },
  { id: 'page', label: 'Page', icon: Layers, hint: 'Switch deck pages on the device' }
];

// Preset color swatches matching Tokyo Night palette
const TILE_COLOR_PRESETS = [
  '#111827', // Default dark
  '#16161e', // Tokyo Night sidebar
  '#1a1b26', // Tokyo Night canvas
  '#21222d', // Tokyo Night card
  '#24283b', // Tokyo Night elevated
  '#1e293b', // Slate dark
  '#1e1e2e', // Deep mocha
  '#1f2d48', // Midnight blue
  '#1b2d28', // Forest dark
  '#321e2e'  // Twilight plum
];

const ICON_COLOR_PRESETS = [
  '#7aa2f7', // Tokyo Night accent (Blue)
  '#7dcfff', // Cyan
  '#9ece6a', // Level green
  '#e0af68', // Warm yellow
  '#ff9e64', // Orange
  '#f7768e', // Danger pink/red
  '#bb9af7', // Purple
  '#c8d1f0', // Ink light
  '#ffffff', // Pure white
  '#38bdf8'  // Sky blue
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

const SYSTEM_PRESETS: Array<{ label: string; value: string; icon: LucideIcon; hint: string }> = [
  { label: 'Lock Workstation', value: 'lock', icon: Lock, hint: 'Immediately locks workstation screen' },
  { label: 'Take Screenshot', value: 'screenshot', icon: Camera, hint: 'Triggers native snip tool (Win+Shift+S)' },
  { label: 'Brightness +10%', value: 'bright_up', icon: Sun, hint: 'Increases primary display brightness' },
  { label: 'Brightness -10%', value: 'bright_down', icon: Moon, hint: 'Decreases primary display brightness' }
];

const URL_PRESETS = [
  { label: 'Google', url: 'https://google.com' },
  { label: 'GitHub', url: 'https://github.com' },
  { label: 'YouTube', url: 'https://youtube.com' },
  { label: 'Spotify Web', url: 'https://open.spotify.com' },
  { label: 'Twitter / X', url: 'https://x.com' },
  { label: 'ChatGPT', url: 'https://chatgpt.com' }
];

const BADGE_PRESETS = ['ACTIVE', 'LIVE', 'REC', 'ON', 'OFF', 'MUTE', 'NEW', 'HOT'];

const iconForApp = (app: InstalledApp): LucideIcon => {
  if (app.icon && ICONS_MAP[app.icon]) return ICONS_MAP[app.icon];
  const name = app.name.toLowerCase();
  if (/chrome|edge|firefox|browser|safari|brave|opera/.test(name)) return Globe;
  if (/term|shell|power|cmd|bash|wsl|alacritty|kitty/.test(name)) return Terminal;
  if (/note|word|doc|txt|writer|obsidian|notion/.test(name)) return FileText;
  if (/code|studio|dev|git|idea|sublime/.test(name)) return Code;
  if (/music|spotify|sound|audio|player|vlc/.test(name)) return Music;
  if (/mail|outlook|thunderbird/.test(name)) return Bookmark;
  if (/camera|photo|screen|capture/.test(name)) return Camera;
  return Code;
};

// ============================================================================
// Props
// ============================================================================

export interface DeckViewProps {
  state: LocalState;
}

// ============================================================================
// Main Component
// ============================================================================

export const DeckView: React.FC<DeckViewProps> = ({ state }) => {
  // Config & Selection state
  const [config, setConfig] = useState<DeckConfig | null>(null);
  const [pageIndex, setPageIndex] = useState<number>(0);
  const [keyIndex, setKeyIndex] = useState<number>(0);

  // App discovery state
  const [apps, setApps] = useState<InstalledApp[]>([]);
  const [nativeIcons, setNativeIcons] = useState<Record<string, string>>({});
  const [appsQuery, setAppsQuery] = useState<string>('');
  const [loadingApps, setLoadingApps] = useState<boolean>(false);

  // Persistence & notification state
  const [syncStatus, setSyncStatus] = useState<'synced' | 'saving' | 'error'>('synced');
  const [notice, setNotice] = useState<{ type: 'success' | 'error' | 'info'; text: string } | null>(null);
  const [testingKey, setTestingKey] = useState<boolean>(false);

  // Page renaming inline state
  const [renamingPage, setRenamingPage] = useState<boolean>(false);
  const [pageNameInput, setPageNameInput] = useState<string>('');

  // Icon search state
  const [iconQuery, setIconQuery] = useState<string>('');

  // Live infobar clock timer
  const [currentTime, setCurrentTime] = useState<string>(() => new Date().toLocaleTimeString());

  // Save debounce reference
  const saveTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Tick clock for infobar preview
  useEffect(() => {
    const timer = setInterval(() => {
      setCurrentTime(new Date().toLocaleTimeString());
    }, 1000);
    return () => clearInterval(timer);
  }, []);

  // Initial load of config
  const loadConfig = useCallback(async () => {
    try {
      const initial = await window.switchboard.deck.get();
      setConfig(initial);
      setPageIndex(Math.min(initial.activePage ?? 0, Math.max(0, initial.pages.length - 1)));
    } catch {
      setNotice({ type: 'error', text: 'Failed to load deck configuration' });
    }
  }, []);

  // Load installed apps & fetch native icons
  const loadApps = useCallback(async () => {
    setLoadingApps(true);
    try {
      const list = await window.switchboard.deck.apps();
      setApps(list);
      // Asynchronously fetch native OS icons for discovered applications
      const iconEntries = await Promise.all(
        list.slice(0, 100).map(async (app) => {
          try {
            const iconData = await window.switchboard.deck.appIcon(app.path);
            return [app.path, iconData] as const;
          } catch {
            return [app.path, ''] as const;
          }
        })
      );
      setNativeIcons(Object.fromEntries(iconEntries.filter(([, icon]) => Boolean(icon))));
    } catch {
      // Non-critical, fallback to standard icons
    } finally {
      setLoadingApps(false);
    }
  }, []);

  useEffect(() => {
    void loadConfig();
    void loadApps();
  }, [loadConfig, loadApps]);

  // Persist configuration changes with debounced background commit
  const commitConfig = useCallback((updated: DeckConfig, immediate = false) => {
    setConfig(updated);
    setSyncStatus('saving');

    if (saveTimeoutRef.current) {
      clearTimeout(saveTimeoutRef.current);
    }

    const persist = async () => {
      try {
        await window.switchboard.deck.set(updated);
        setSyncStatus('synced');
      } catch {
        setSyncStatus('error');
        setNotice({ type: 'error', text: 'Failed to save configuration to Switchboard daemon' });
      }
    };

    if (immediate) {
      void persist();
    } else {
      saveTimeoutRef.current = setTimeout(persist, 350);
    }
  }, []);

  // Clean up debounce on unmount
  useEffect(() => {
    return () => {
      if (saveTimeoutRef.current) clearTimeout(saveTimeoutRef.current);
    };
  }, []);

  // Active page & selected key computation
  const activePage: DeckPage | undefined = config?.pages[pageIndex] ?? config?.pages[0];

  // Guarantee 8 keys always exist in standard Neo layout
  const currentKeys: DeckKey[] = useMemo(() => {
    return Array.from({ length: 8 }, (_, idx) => {
      const existing = activePage?.keys?.find((k) => k.index === idx);
      if (existing) return existing;
      return {
        index: idx,
        title: `Key ${idx + 1}`,
        icon: 'code',
        bgColor: '#111827',
        iconColor: '#7aa2f7',
        action: { type: 'url', value: '' }
      };
    });
  }, [activePage]);

  const selectedKey: DeckKey = currentKeys[keyIndex] ?? currentKeys[0];

  // Update selected key properties
  const updateSelectedKey = useCallback(
    (patch: Partial<DeckKey>) => {
      if (!config || !activePage) return;
      const updatedKeys = currentKeys.map((k) => (k.index === keyIndex ? { ...k, ...patch } : k));
      const updatedPages = config.pages.map((p, idx) =>
        idx === pageIndex ? { ...p, keys: updatedKeys } : p
      );
      commitConfig({ ...config, pages: updatedPages });
    },
    [config, activePage, currentKeys, keyIndex, pageIndex, commitConfig]
  );

  // Switch page
  const handleSelectPage = useCallback(
    (idx: number) => {
      if (!config) return;
      setPageIndex(idx);
      setRenamingPage(false);
      commitConfig({ ...config, activePage: idx }, true);
    },
    [config, commitConfig]
  );

  // Add new page
  const handleAddPage = useCallback(() => {
    if (!config) return;
    const newIndex = config.pages.length;
    const newPage: DeckPage = {
      id: `page-${Date.now()}`,
      name: `Page ${newIndex + 1}`,
      keys: Array.from({ length: 8 }, (_, idx) => ({
        index: idx,
        title: `Key ${idx + 1}`,
        icon: 'code',
        bgColor: '#111827',
        iconColor: '#7aa2f7',
        action: { type: 'url', value: '' }
      }))
    };
    const updated: DeckConfig = {
      ...config,
      activePage: newIndex,
      pages: [...config.pages, newPage]
    };
    setPageIndex(newIndex);
    commitConfig(updated, true);
    setNotice({ type: 'info', text: `Added new ${newPage.name}` });
    setTimeout(() => setNotice(null), 2500);
  }, [config, commitConfig]);

  // Delete current page (allowed only if > 1 page)
  const handleDeletePage = useCallback(() => {
    if (!config || config.pages.length <= 1) return;
    const nextIndex = Math.max(0, pageIndex - 1);
    const updatedPages = config.pages.filter((_, idx) => idx !== pageIndex);
    const updated: DeckConfig = {
      ...config,
      activePage: nextIndex,
      pages: updatedPages
    };
    setPageIndex(nextIndex);
    commitConfig(updated, true);
    setNotice({ type: 'info', text: 'Page deleted' });
    setTimeout(() => setNotice(null), 2500);
  }, [config, pageIndex, commitConfig]);

  // Rename current page
  const handleSavePageName = useCallback(() => {
    if (!config || !activePage || !pageNameInput.trim()) {
      setRenamingPage(false);
      return;
    }
    const updatedPages = config.pages.map((p, idx) =>
      idx === pageIndex ? { ...p, name: pageNameInput.trim() } : p
    );
    commitConfig({ ...config, pages: updatedPages }, true);
    setRenamingPage(false);
  }, [config, activePage, pageNameInput, pageIndex, commitConfig]);

  // Execute / Test key action
  const handleRunKey = useCallback(
    async (keyToRun: DeckKey) => {
      setTestingKey(true);
      try {
        await window.switchboard.deck.action({
          pageId: activePage?.id,
          keyIndex: keyToRun.index,
          action: keyToRun.action
        });
        setNotice({
          type: 'success',
          text: `Action "${keyToRun.title || `Key ${keyToRun.index + 1}`}" executed successfully`
        });
      } catch (err) {
        const errorMsg = err instanceof Error ? err.message : 'Unknown execution error';
        setNotice({ type: 'error', text: `Execution failed: ${errorMsg}` });
      } finally {
        setTestingKey(false);
        setTimeout(() => setNotice(null), 3000);
      }
    },
    [activePage]
  );

  // Update Infobar settings
  const handleUpdateInfobar = useCallback(
    (patch: Partial<DeckInfobar>) => {
      if (!config) return;
      const updated: DeckConfig = {
        ...config,
        infobar: { ...config.infobar, ...patch }
      };
      commitConfig(updated, true);
    },
    [config, commitConfig]
  );

  // Reset selected key to defaults
  const handleResetKey = useCallback(() => {
    updateSelectedKey({
      title: `Key ${keyIndex + 1}`,
      icon: 'code',
      bgColor: '#111827',
      iconColor: '#7aa2f7',
      badge: '',
      action: { type: 'url', value: '' }
    });
    setNotice({ type: 'info', text: `Key ${keyIndex + 1} reset to defaults` });
    setTimeout(() => setNotice(null), 2000);
  }, [keyIndex, updateSelectedKey]);

  // Filtered applications list
  const filteredApps = useMemo(() => {
    if (!appsQuery.trim()) return apps.slice(0, 40);
    const q = appsQuery.toLowerCase();
    return apps.filter(
      (app) => app.name.toLowerCase().includes(q) || app.path.toLowerCase().includes(q)
    );
  }, [apps, appsQuery]);

  // Filtered icons list
  const filteredIcons = useMemo(() => {
    const entries = Object.entries(ICONS_MAP);
    if (!iconQuery.trim()) return entries;
    const q = iconQuery.toLowerCase();
    return entries.filter(
      ([key]) => key.toLowerCase().includes(q) || (ICON_LABELS[key] || '').toLowerCase().includes(q)
    );
  }, [iconQuery]);

  // Loading skeleton screen
  if (!config || !activePage) {
    return (
      <div className="flex flex-1 items-center justify-center bg-canvas">
        <div className="flex flex-col items-center gap-3">
          <RefreshCw className="h-6 w-6 animate-spin text-accent" />
          <p className="font-mono text-tiny text-ink-faint">Connecting to Switchboard Deck…</p>
        </div>
      </div>
    );
  }

  return (
    <main className="flex flex-1 flex-col overflow-y-auto bg-canvas px-4 py-5 sm:px-6 lg:px-8">
      {/* 1. Page Header & Navigation */}
      <header className="mx-auto flex w-full max-w-7xl flex-wrap items-center justify-between gap-4 border-b border-edge pb-5">
        <div>
          <div className="flex items-center gap-2.5">
            <span className="inline-flex items-center gap-1.5 rounded-md bg-accent/15 px-2 py-0.5 text-micro font-semibold uppercase tracking-[0.16em] text-accent">
              <Radio className="h-3 w-3" /> Command Surface
            </span>
            {/* Sync State Badge */}
            <div
              className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-micro font-medium transition-colors ${
                syncStatus === 'synced'
                  ? 'bg-level/15 text-level'
                  : syncStatus === 'saving'
                    ? 'bg-warn/15 text-warn'
                    : 'bg-danger/15 text-danger'
              }`}
            >
              <span
                className={`h-1.5 w-1.5 rounded-full ${
                  syncStatus === 'synced'
                    ? 'bg-level'
                    : syncStatus === 'saving'
                      ? 'animate-ping bg-warn'
                      : 'bg-danger'
                }`}
              />
              {syncStatus === 'synced' ? 'Synced' : syncStatus === 'saving' ? 'Saving…' : 'Sync Error'}
            </div>
          </div>
          <h1 className="mt-1 text-xl font-bold text-ink">Stream Deck Neo Controller</h1>
          <p className="text-tiny text-ink-dim">
            Configure physical 8-key layouts, interactive infobars, and desktop automation.
          </p>
        </div>

        {/* Page Selector Tabs & Action Controls */}
        <div className="flex flex-wrap items-center gap-1.5 rounded-xl border border-edge bg-card p-1.5">
          {config.pages.map((p, idx) => {
            const isSelected = idx === pageIndex;
            return (
              <div key={p.id} className="relative flex items-center">
                {isSelected && renamingPage ? (
                  <div className="flex items-center gap-1 px-1">
                    <input
                      type="text"
                      autoFocus
                      value={pageNameInput}
                      onChange={(e) => setPageNameInput(e.target.value)}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter') handleSavePageName();
                        if (e.key === 'Escape') setRenamingPage(false);
                      }}
                      className="w-24 rounded border border-accent bg-canvas px-2 py-1 text-tiny text-ink focus:outline-none"
                    />
                    <button
                      type="button"
                      onClick={handleSavePageName}
                      className="rounded p-1 text-level hover:bg-raised"
                      title="Save name"
                    >
                      <Check className="h-3.5 w-3.5" />
                    </button>
                  </div>
                ) : (
                  <button
                    type="button"
                    onClick={() => handleSelectPage(idx)}
                    onDoubleClick={() => {
                      setPageNameInput(p.name);
                      setRenamingPage(true);
                    }}
                    title="Click to switch · Double-click to rename"
                    className={`flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-tiny font-medium transition ${
                      isSelected
                        ? 'bg-raised text-ink shadow-sm ring-1 ring-edge'
                        : 'text-ink-dim hover:bg-raised/60 hover:text-ink'
                    }`}
                  >
                    <span>{p.name}</span>
                    <span className="font-mono text-[10px] text-ink-faint">({p.keys?.length ?? 8})</span>
                  </button>
                )}
              </div>
            );
          })}

          {/* Inline Rename active page button */}
          {!renamingPage && (
            <button
              type="button"
              onClick={() => {
                setPageNameInput(activePage.name);
                setRenamingPage(true);
              }}
              title="Rename active page"
              className="grid h-8 w-8 place-items-center rounded-lg text-ink-dim hover:bg-raised hover:text-ink transition"
            >
              <Pencil className="h-3.5 w-3.5" />
            </button>
          )}

          {/* Add Page Button */}
          <button
            type="button"
            onClick={handleAddPage}
            title="Create new deck page"
            className="grid h-8 w-8 place-items-center rounded-lg text-ink-dim hover:bg-raised hover:text-accent transition"
          >
            <Plus className="h-4 w-4" />
          </button>

          {/* Delete Page Button */}
          {config.pages.length > 1 && (
            <button
              type="button"
              onClick={handleDeletePage}
              title="Delete this page"
              className="grid h-8 w-8 place-items-center rounded-lg text-ink-dim hover:bg-danger/15 hover:text-danger transition"
            >
              <Trash2 className="h-3.5 w-3.5" />
            </button>
          )}
        </div>
      </header>

      {/* Main Grid: Command Surface & Key Inspector */}
      <div className="mx-auto grid w-full max-w-7xl gap-6 py-6 lg:grid-cols-[1fr_420px] xl:grid-cols-[1fr_450px]">
        {/* ==================================================================== */}
        {/* LEFT COLUMN: 2x4 Key Command Surface + Infobar Hardware Shell        */}
        {/* ==================================================================== */}
        <div className="flex flex-col gap-6">
          {/* Hardware Frame simulation */}
          <section className="relative overflow-hidden rounded-3xl border border-edge bg-gradient-to-b from-[#14151f] via-card to-[#12131a] p-6 shadow-2xl">
            {/* Stream Deck Neo Top Banner / Status */}
            <div className="mb-5 flex flex-wrap items-center justify-between gap-3 border-b border-edge/60 pb-4">
              <div className="flex items-center gap-3">
                <div className="flex h-7 w-7 items-center justify-center rounded-lg bg-raised font-mono text-tiny font-bold text-accent">
                  ⊞
                </div>
                <div>
                  <h2 className="text-sm font-semibold text-ink">{activePage.name}</h2>
                  <p className="text-micro text-ink-dim">
                    Click to inspect · Double-click to trigger immediately
                  </p>
                </div>
              </div>

              {/* Neo Device Info Tag */}
              <div className="flex items-center gap-2 rounded-lg bg-canvas/70 px-2.5 py-1 text-micro text-ink-faint border border-edge/40 font-mono">
                <span>Stream Deck Neo</span>
                <span>•</span>
                <span>8 Keys (2×4)</span>
              </div>
            </div>

            {/* 2x4 Key Command Surface Grid */}
            <div className="grid grid-cols-4 gap-3.5 sm:gap-4">
              {currentKeys.map((key) => {
                const isSelected = key.index === keyIndex;
                const IconComponent = ICONS_MAP[key.icon] ?? Code;
                const tileBg = key.bgColor || '#16161e';
                const tileIconColor = key.iconColor || '#7aa2f7';

                return (
                  <button
                    key={key.index}
                    type="button"
                    onClick={() => setKeyIndex(key.index)}
                    onDoubleClick={() => void handleRunKey(key)}
                    style={{ backgroundColor: tileBg }}
                    className={`group relative flex aspect-square flex-col items-center justify-center rounded-2xl p-2.5 text-center transition-all duration-150 focus-visible:outline-none ${
                      isSelected
                        ? 'border-2 border-accent ring-4 ring-accent/30 shadow-[0_0_20px_rgba(122,162,247,0.4)] scale-[1.03] z-10'
                        : 'border border-edge/80 hover:border-accent-dim hover:scale-[1.01] hover:shadow-lg'
                    }`}
                  >
                    {/* Key Index Pill (Top Left) */}
                    <span className="absolute left-2.5 top-2.5 font-mono text-[10px] font-semibold text-ink-faint/70 group-hover:text-ink-faint">
                      {key.index + 1}
                    </span>

                    {/* Badge Pill (Top Right) */}
                    {key.badge && (
                      <span className="absolute right-2 top-2 max-w-[65%] truncate rounded bg-accent/20 px-1.5 py-0.5 font-mono text-[9px] font-bold uppercase tracking-wider text-accent border border-accent/40 shadow-sm">
                        {key.badge}
                      </span>
                    )}

                    {/* Key Icon */}
                    <div className="my-auto flex items-center justify-center transition-transform group-hover:scale-110">
                      <IconComponent
                        className="h-8 w-8 sm:h-9 sm:w-9"
                        style={{ color: tileIconColor }}
                      />
                    </div>

                    {/* Key Title */}
                    <span className="w-full truncate px-1 text-tiny font-semibold tracking-tight text-ink">
                      {key.title || `Key ${key.index + 1}`}
                    </span>

                    {/* Action Type Subtle Hint */}
                    <span className="truncate font-mono text-[10px] text-ink-faint">
                      {key.action.type}
                    </span>
                  </button>
                );
              })}
            </div>

            {/* Neo Signature Infobar Hardware Display */}
            <div className="mt-5 overflow-hidden rounded-xl border border-edge/80 bg-[#0c0d12] p-3 shadow-inner">
              <div className="flex items-center justify-between gap-4">
                {/* Hardware Touch Sensor Left (<) */}
                <button
                  type="button"
                  onClick={() => handleSelectPage(Math.max(0, pageIndex - 1))}
                  disabled={pageIndex <= 0}
                  title="Previous Page (Hardware Touch Point)"
                  className="grid h-9 w-9 shrink-0 place-items-center rounded-lg border border-edge/40 bg-raised/40 text-ink-dim hover:border-accent hover:text-accent disabled:opacity-30 disabled:hover:border-edge/40 disabled:hover:text-ink-dim transition"
                >
                  <ChevronLeft className="h-4 w-4" />
                </button>

                {/* OLED Infobar Screen Simulation */}
                <div className="flex flex-1 items-center justify-center text-center font-mono">
                  {config.infobar.mode === 'clock' && (
                    <div className="flex items-center gap-2">
                      <Clock className="h-4 w-4 text-accent" />
                      <span className="text-sm font-bold tracking-wider text-accent">
                        {currentTime}
                      </span>
                      <span className="text-micro text-ink-faint">
                        {new Date().toLocaleDateString(undefined, {
                          weekday: 'short',
                          month: 'short',
                          day: 'numeric'
                        })}
                      </span>
                    </div>
                  )}

                  {config.infobar.mode === 'media' && (
                    <div className="flex items-center gap-2 max-w-sm truncate text-ink">
                      {state.host.media.active && state.host.media.title ? (
                        <>
                          {state.host.media.status === 'playing' ? (
                            <Volume2 className="h-4 w-4 text-level animate-pulse" />
                          ) : (
                            <Pause className="h-4 w-4 text-warn" />
                          )}
                          <span className="truncate text-tiny font-medium">
                            {state.host.media.title}
                            {state.host.media.artist ? ` — ${state.host.media.artist}` : ''}
                          </span>
                        </>
                      ) : (
                        <span className="flex items-center gap-1.5 text-micro text-ink-faint">
                          <Music className="h-3.5 w-3.5" /> No active media playback
                        </span>
                      )}
                    </div>
                  )}

                  {config.infobar.mode === 'page' && (
                    <div className="flex items-center gap-2">
                      <Layers className="h-4 w-4 text-accent" />
                      <span className="text-tiny font-bold text-ink">
                        Page {pageIndex + 1} of {config.pages.length}
                      </span>
                      <span className="text-micro text-ink-dim">· {activePage.name}</span>
                    </div>
                  )}

                  {config.infobar.mode === 'text' && (
                    <div className="flex items-center gap-2 text-ink">
                      <Type className="h-4 w-4 text-accent" />
                      <span className="text-tiny font-semibold tracking-wide">
                        {config.infobar.customText || 'Switchboard Neo Deck'}
                      </span>
                    </div>
                  )}
                </div>

                {/* Hardware Touch Sensor Right (>) */}
                <button
                  type="button"
                  onClick={() =>
                    handleSelectPage(Math.min(config.pages.length - 1, pageIndex + 1))
                  }
                  disabled={pageIndex >= config.pages.length - 1}
                  title="Next Page (Hardware Touch Point)"
                  className="grid h-9 w-9 shrink-0 place-items-center rounded-lg border border-edge/40 bg-raised/40 text-ink-dim hover:border-accent hover:text-accent disabled:opacity-30 disabled:hover:border-edge/40 disabled:hover:text-ink-dim transition"
                >
                  <ChevronRight className="h-4 w-4" />
                </button>
              </div>
            </div>

            {/* Key Trigger Feedback Banner & Test Button Bar */}
            <div className="mt-4 flex flex-wrap items-center justify-between gap-3 rounded-xl border border-edge bg-canvas/80 px-4 py-3">
              <div className="flex items-center gap-2 min-w-0">
                {notice ? (
                  <div
                    className={`flex items-center gap-2 text-tiny font-medium ${
                      notice.type === 'success'
                        ? 'text-level'
                        : notice.type === 'error'
                          ? 'text-danger'
                          : 'text-accent'
                    }`}
                  >
                    {notice.type === 'success' && <Check className="h-4 w-4" />}
                    {notice.type === 'error' && <AlertCircle className="h-4 w-4" />}
                    {notice.type === 'info' && <Radio className="h-4 w-4" />}
                    <span className="truncate">{notice.text}</span>
                  </div>
                ) : (
                  <span className="text-tiny text-ink-dim">
                    Selected:{' '}
                    <strong className="text-ink">
                      Key {selectedKey.index + 1} ({selectedKey.title || 'Untitled'})
                    </strong>{' '}
                    · {selectedKey.action.type}:{' '}
                    <span className="font-mono text-ink-faint">
                      {selectedKey.action.value || '(empty)'}
                    </span>
                  </span>
                )}
              </div>

              {/* Run Selected Key Button */}
              <button
                type="button"
                onClick={() => void handleRunKey(selectedKey)}
                disabled={testingKey}
                className="inline-flex items-center gap-2 rounded-lg bg-accent px-3.5 py-1.5 text-tiny font-semibold text-canvas hover:brightness-110 active:scale-95 disabled:opacity-50 transition"
              >
                {testingKey ? (
                  <RefreshCw className="h-3.5 w-3.5 animate-spin" />
                ) : (
                  <Play className="h-3.5 w-3.5 fill-current" />
                )}
                Run Selected Key
              </button>
            </div>
          </section>

          {/* 4. Infobar Settings Card */}
          <section className="rounded-2xl border border-edge bg-card p-5">
            <div className="flex items-center justify-between border-b border-edge pb-3.5">
              <div className="flex items-center gap-2">
                <Sliders className="h-4 w-4 text-accent" />
                <h3 className="text-sm font-semibold text-ink">Infobar Display Settings</h3>
              </div>
              <span className="font-mono text-micro text-ink-faint">Mode: {config.infobar.mode}</span>
            </div>

            <div className="mt-4 space-y-4">
              <div>
                <label className="block text-micro font-semibold uppercase tracking-wider text-ink-faint">
                  Display Mode
                </label>
                <div className="mt-2 grid grid-cols-2 gap-2 sm:grid-cols-4">
                  {(['clock', 'media', 'page', 'text'] as const).map((mode) => {
                    const isCurrent = config.infobar.mode === mode;
                    return (
                      <button
                        key={mode}
                        type="button"
                        onClick={() => handleUpdateInfobar({ mode })}
                        className={`flex items-center justify-center gap-2 rounded-lg border py-2 text-tiny font-medium capitalize transition ${
                          isCurrent
                            ? 'border-accent bg-accent/15 text-accent shadow-sm'
                            : 'border-edge bg-canvas text-ink-dim hover:bg-raised hover:text-ink'
                        }`}
                      >
                        {mode === 'clock' && <Clock className="h-3.5 w-3.5" />}
                        {mode === 'media' && <Music className="h-3.5 w-3.5" />}
                        {mode === 'page' && <Layers className="h-3.5 w-3.5" />}
                        {mode === 'text' && <Type className="h-3.5 w-3.5" />}
                        <span>{mode}</span>
                      </button>
                    );
                  })}
                </div>
              </div>

              {/* Custom Text input if mode === 'text' */}
              {config.infobar.mode === 'text' && (
                <div>
                  <label className="block text-micro font-semibold uppercase tracking-wider text-ink-faint">
                    Custom Display Text
                  </label>
                  <input
                    type="text"
                    value={config.infobar.customText}
                    onChange={(e) => handleUpdateInfobar({ customText: e.target.value })}
                    placeholder="Enter custom text for the Neo OLED strip…"
                    className="mt-1.5 w-full rounded-lg border border-edge bg-canvas px-3 py-2 text-tiny text-ink focus:border-accent focus:outline-none"
                  />
                </div>
              )}
            </div>
          </section>
        </div>

        {/* ==================================================================== */}
        {/* RIGHT COLUMN: Key Configuration Inspector                            */}
        {/* ==================================================================== */}
        <aside className="flex flex-col gap-6">
          <div className="rounded-2xl border border-edge bg-card p-5 shadow-lg">
            {/* Inspector Header */}
            <div className="flex items-center justify-between border-b border-edge pb-4">
              <div className="flex items-center gap-3">
                <div
                  className="grid h-10 w-10 place-items-center rounded-xl border border-edge"
                  style={{ backgroundColor: selectedKey.bgColor || '#111827' }}
                >
                  {(() => {
                    const CurrentIcon = ICONS_MAP[selectedKey.icon] ?? Code;
                    return (
                      <CurrentIcon
                        className="h-5 w-5"
                        style={{ color: selectedKey.iconColor || '#7aa2f7' }}
                      />
                    );
                  })()}
                </div>
                <div>
                  <p className="font-mono text-micro font-bold uppercase tracking-wider text-accent">
                    Key {selectedKey.index + 1} of 8
                  </p>
                  <h2 className="text-base font-semibold text-ink">
                    {selectedKey.title || `Key ${selectedKey.index + 1}`}
                  </h2>
                </div>
              </div>

              {/* Reset Key Button */}
              <button
                type="button"
                onClick={handleResetKey}
                title="Reset key to default settings"
                className="inline-flex items-center gap-1.5 rounded-lg border border-edge px-2.5 py-1.5 text-micro font-medium text-ink-dim hover:border-danger/40 hover:bg-danger/10 hover:text-danger transition"
              >
                <RotateCcw className="h-3.5 w-3.5" />
                Reset
              </button>
            </div>

            <div className="mt-5 space-y-5">
              {/* Title & Badge inputs */}
              <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
                <div className="sm:col-span-2">
                  <label className="block text-micro font-semibold uppercase tracking-wider text-ink-faint">
                    Key Label
                  </label>
                  <input
                    type="text"
                    value={selectedKey.title}
                    onChange={(e) => updateSelectedKey({ title: e.target.value })}
                    placeholder="e.g. Chrome, Mute, Terminal"
                    className="mt-1.5 w-full rounded-lg border border-edge bg-canvas px-3 py-2 text-tiny text-ink focus:border-accent focus:outline-none"
                  />
                </div>
                <div>
                  <label className="block text-micro font-semibold uppercase tracking-wider text-ink-faint">
                    Badge Pill
                  </label>
                  <input
                    type="text"
                    value={selectedKey.badge || ''}
                    onChange={(e) => updateSelectedKey({ badge: e.target.value.toUpperCase() })}
                    placeholder="e.g. LIVE"
                    maxLength={6}
                    className="mt-1.5 w-full rounded-lg border border-edge bg-canvas px-3 py-2 font-mono text-tiny uppercase text-ink focus:border-accent focus:outline-none"
                  />
                </div>
              </div>

              {/* Quick Badge Suggestions */}
              <div className="flex flex-wrap items-center gap-1">
                <span className="text-micro text-ink-faint mr-1">Suggestions:</span>
                {BADGE_PRESETS.map((b) => (
                  <button
                    key={b}
                    type="button"
                    onClick={() => updateSelectedKey({ badge: selectedKey.badge === b ? '' : b })}
                    className={`rounded px-1.5 py-0.5 font-mono text-[10px] font-semibold transition ${
                      selectedKey.badge === b
                        ? 'bg-accent text-canvas'
                        : 'bg-raised text-ink-dim hover:text-ink'
                    }`}
                  >
                    {b}
                  </button>
                ))}
                {selectedKey.badge && (
                  <button
                    type="button"
                    onClick={() => updateSelectedKey({ badge: '' })}
                    className="text-micro text-danger hover:underline ml-1"
                  >
                    clear
                  </button>
                )}
              </div>

              {/* Action Type Selector */}
              <div>
                <label className="block text-micro font-semibold uppercase tracking-wider text-ink-faint">
                  Action Type
                </label>
                <div className="mt-2 grid grid-cols-3 gap-1.5">
                  {ACTION_TYPES.map((type) => {
                    const isCurrent = selectedKey.action.type === type.id;
                    const TypeIcon = type.icon;
                    return (
                      <button
                        key={type.id}
                        type="button"
                        onClick={() => {
                          let defaultValue = '';
                          if (type.id === 'media') defaultValue = 'toggle';
                          if (type.id === 'system') defaultValue = 'lock';
                          if (type.id === 'page') defaultValue = 'next';
                          if (type.id === 'url') defaultValue = 'https://';
                          updateSelectedKey({ action: { type: type.id, value: defaultValue } });
                        }}
                        className={`flex flex-col items-center justify-center gap-1 rounded-xl border p-2.5 text-center transition ${
                          isCurrent
                            ? 'border-accent bg-accent/15 text-accent shadow-sm'
                            : 'border-edge bg-canvas text-ink-dim hover:bg-raised hover:text-ink'
                        }`}
                      >
                        <TypeIcon className="h-4 w-4" />
                        <span className="text-micro font-medium">{type.label}</span>
                      </button>
                    );
                  })}
                </div>
              </div>

              {/* Context-Sensitive Editor for Action Value */}
              <div className="rounded-xl border border-edge bg-canvas/60 p-3.5">
                {/* 1. APP ACTION */}
                {selectedKey.action.type === 'app' && (
                  <div className="space-y-3">
                    <div className="flex items-center justify-between">
                      <label className="text-micro font-semibold uppercase tracking-wider text-ink-faint">
                        Installed Applications
                      </label>
                      <button
                        type="button"
                        onClick={() => void loadApps()}
                        disabled={loadingApps}
                        className="inline-flex items-center gap-1 text-micro font-medium text-accent hover:underline"
                      >
                        <RefreshCw className={`h-3 w-3 ${loadingApps ? 'animate-spin' : ''}`} />
                        Refresh
                      </button>
                    </div>

                    {/* App search input */}
                    <div className="relative">
                      <Search className="absolute left-2.5 top-2.5 h-3.5 w-3.5 text-ink-faint" />
                      <input
                        type="text"
                        value={appsQuery}
                        onChange={(e) => setAppsQuery(e.target.value)}
                        placeholder="Search programs…"
                        className="w-full rounded-lg border border-edge bg-card py-1.5 pl-8 pr-3 text-tiny text-ink focus:border-accent focus:outline-none"
                      />
                    </div>

                    {/* App List */}
                    <div className="max-h-48 overflow-y-auto rounded-lg border border-edge bg-card divide-y divide-edge/40">
                      {filteredApps.length === 0 ? (
                        <div className="p-4 text-center text-micro text-ink-faint">
                          {loadingApps ? 'Discovering system applications…' : 'No matching applications found'}
                        </div>
                      ) : (
                        filteredApps.map((app) => {
                          const isPicked = selectedKey.action.value === (app.path || app.name);
                          const FallbackIcon = iconForApp(app);
                          const nativeIconUri = nativeIcons[app.path];

                          return (
                            <button
                              key={app.path || app.name}
                              type="button"
                              onClick={() => {
                                updateSelectedKey({
                                  title:
                                    selectedKey.title === `Key ${keyIndex + 1}`
                                      ? app.name
                                      : selectedKey.title,
                                  icon: app.icon || 'code',
                                  action: { type: 'app', value: app.path || app.name }
                                });
                              }}
                              className={`flex w-full items-center gap-2.5 px-3 py-2 text-left transition ${
                                isPicked
                                  ? 'bg-accent/20 text-accent font-medium'
                                  : 'text-ink hover:bg-raised'
                              }`}
                            >
                              <span className="grid h-6 w-6 shrink-0 place-items-center rounded bg-canvas overflow-hidden">
                                {nativeIconUri ? (
                                  <img src={nativeIconUri} alt="" className="h-4 w-4 object-contain" />
                                ) : (
                                  <FallbackIcon className="h-3.5 w-3.5 text-accent" />
                                )}
                              </span>
                              <div className="min-w-0 flex-1">
                                <p className="truncate text-tiny">{app.name}</p>
                                <p className="truncate font-mono text-[10px] text-ink-faint">
                                  {app.path}
                                </p>
                              </div>
                              {isPicked && <Check className="h-3.5 w-3.5 shrink-0 text-accent" />}
                            </button>
                          );
                        })
                      )}
                    </div>

                    {/* Manual Path input fallback */}
                    <div>
                      <label className="block text-micro font-medium text-ink-dim">
                        Or Target Path / Executable
                      </label>
                      <input
                        type="text"
                        value={selectedKey.action.value}
                        onChange={(e) =>
                          updateSelectedKey({ action: { type: 'app', value: e.target.value } })
                        }
                        placeholder="C:\Windows\notepad.exe"
                        className="mt-1 w-full rounded border border-edge bg-card px-2.5 py-1.5 font-mono text-tiny text-ink focus:border-accent focus:outline-none"
                      />
                    </div>
                  </div>
                )}

                {/* 2. URL ACTION */}
                {selectedKey.action.type === 'url' && (
                  <div className="space-y-3">
                    <label className="block text-micro font-semibold uppercase tracking-wider text-ink-faint">
                      Target URL
                    </label>
                    <input
                      type="url"
                      value={selectedKey.action.value}
                      onChange={(e) =>
                        updateSelectedKey({ action: { type: 'url', value: e.target.value } })
                      }
                      placeholder="https://example.com"
                      className="w-full rounded-lg border border-edge bg-card px-3 py-2 font-mono text-tiny text-ink focus:border-accent focus:outline-none"
                    />

                    {/* Quick URL Presets */}
                    <div>
                      <span className="block text-micro text-ink-faint mb-1.5">Quick Presets:</span>
                      <div className="flex flex-wrap gap-1.5">
                        {URL_PRESETS.map((p) => (
                          <button
                            key={p.url}
                            type="button"
                            onClick={() => {
                              updateSelectedKey({
                                title:
                                  selectedKey.title === `Key ${keyIndex + 1}`
                                    ? p.label
                                    : selectedKey.title,
                                icon: p.label.toLowerCase().includes('youtube')
                                  ? 'youtube'
                                  : p.label.toLowerCase().includes('spotify')
                                    ? 'spotify'
                                    : 'google',
                                action: { type: 'url', value: p.url }
                              });
                            }}
                            className="rounded-md border border-edge bg-card px-2 py-1 text-micro text-ink-dim hover:border-accent hover:text-accent transition"
                          >
                            {p.label}
                          </button>
                        ))}
                      </div>
                    </div>
                  </div>
                )}

                {/* 3. HOTKEY ACTION */}
                {selectedKey.action.type === 'hotkey' && (
                  <div className="space-y-3">
                    <label className="block text-micro font-semibold uppercase tracking-wider text-ink-faint">
                      Hotkey Combination Chord
                    </label>
                    <input
                      type="text"
                      value={selectedKey.action.value}
                      onChange={(e) =>
                        updateSelectedKey({ action: { type: 'hotkey', value: e.target.value } })
                      }
                      placeholder="e.g. win+d, ctrl+shift+esc"
                      className="w-full rounded-lg border border-edge bg-card px-3 py-2 font-mono text-tiny text-ink focus:border-accent focus:outline-none"
                    />

                    {/* Hotkey suggestions */}
                    <div>
                      <span className="block text-micro text-ink-faint mb-1.5">Common Shortcuts:</span>
                      <div className="grid grid-cols-2 gap-1.5">
                        {HOTKEY_PRESETS.map((hk) => {
                          const isPicked = selectedKey.action.value === hk.chord;
                          return (
                            <button
                              key={hk.chord}
                              type="button"
                              onClick={() => {
                                updateSelectedKey({
                                  title:
                                    selectedKey.title === `Key ${keyIndex + 1}`
                                      ? hk.label
                                      : selectedKey.title,
                                  action: { type: 'hotkey', value: hk.chord }
                                });
                              }}
                              className={`flex items-center justify-between rounded-md border px-2 py-1.5 text-left text-micro transition ${
                                isPicked
                                  ? 'border-accent bg-accent/20 text-accent font-medium'
                                  : 'border-edge bg-card text-ink-dim hover:text-ink'
                              }`}
                            >
                              <span>{hk.label}</span>
                              <code className="font-mono text-[10px] text-ink-faint">{hk.chord}</code>
                            </button>
                          );
                        })}
                      </div>
                    </div>
                  </div>
                )}

                {/* 4. MEDIA ACTION */}
                {selectedKey.action.type === 'media' && (
                  <div className="space-y-3">
                    <label className="block text-micro font-semibold uppercase tracking-wider text-ink-faint">
                      Media & Audio Control
                    </label>
                    <div className="grid grid-cols-2 gap-2">
                      {MEDIA_PRESETS.map((m) => {
                        const isPicked = selectedKey.action.value === m.value;
                        const MediaIcon = m.icon;
                        return (
                          <button
                            key={m.value}
                            type="button"
                            onClick={() => {
                              updateSelectedKey({
                                title:
                                  selectedKey.title === `Key ${keyIndex + 1}`
                                    ? m.label
                                    : selectedKey.title,
                                icon:
                                  m.value === 'vol_up'
                                    ? 'volume_up'
                                    : m.value === 'vol_down'
                                      ? 'volume_down'
                                      : m.value === 'mute'
                                        ? 'volume_mute'
                                        : m.value === 'next'
                                          ? 'skip_next'
                                          : m.value === 'prev'
                                            ? 'skip_prev'
                                            : 'play_pause',
                                action: { type: 'media', value: m.value }
                              });
                            }}
                            className={`flex items-center gap-2 rounded-lg border p-2.5 text-left text-tiny transition ${
                              isPicked
                                ? 'border-accent bg-accent/20 text-accent font-medium'
                                : 'border-edge bg-card text-ink-dim hover:text-ink'
                            }`}
                          >
                            <MediaIcon className="h-4 w-4 shrink-0" />
                            <span>{m.label}</span>
                          </button>
                        );
                      })}
                    </div>
                  </div>
                )}

                {/* 5. SYSTEM ACTION */}
                {selectedKey.action.type === 'system' && (
                  <div className="space-y-3">
                    <label className="block text-micro font-semibold uppercase tracking-wider text-ink-faint">
                      Host System Commands
                    </label>
                    <div className="space-y-1.5">
                      {SYSTEM_PRESETS.map((s) => {
                        const isPicked = selectedKey.action.value === s.value;
                        const SysIcon = s.icon;
                        return (
                          <button
                            key={s.value}
                            type="button"
                            onClick={() => {
                              updateSelectedKey({
                                title:
                                  selectedKey.title === `Key ${keyIndex + 1}`
                                    ? s.label
                                    : selectedKey.title,
                                icon:
                                  s.value === 'lock'
                                    ? 'lock'
                                    : s.value === 'screenshot'
                                      ? 'camera'
                                      : 'monitor',
                                action: { type: 'system', value: s.value }
                              });
                            }}
                            className={`flex w-full items-center gap-2.5 rounded-lg border p-2.5 text-left transition ${
                              isPicked
                                ? 'border-accent bg-accent/20 text-accent font-medium'
                                : 'border-edge bg-card text-ink hover:bg-raised'
                            }`}
                          >
                            <SysIcon className="h-4 w-4 shrink-0" />
                            <div className="flex-1 min-w-0">
                              <p className="text-tiny font-medium">{s.label}</p>
                              <p className="text-micro text-ink-faint">{s.hint}</p>
                            </div>
                            {isPicked && <Check className="h-4 w-4 text-accent shrink-0" />}
                          </button>
                        );
                      })}
                    </div>
                  </div>
                )}

                {/* 6. PAGE ACTION */}
                {selectedKey.action.type === 'page' && (
                  <div className="space-y-3">
                    <label className="block text-micro font-semibold uppercase tracking-wider text-ink-faint">
                      Switch Deck Page
                    </label>
                    <div className="grid grid-cols-2 gap-2">
                      <button
                        type="button"
                        onClick={() =>
                          updateSelectedKey({
                            title:
                              selectedKey.title === `Key ${keyIndex + 1}`
                                ? 'Next Page'
                                : selectedKey.title,
                            icon: 'skip_next',
                            action: { type: 'page', value: 'next' }
                          })
                        }
                        className={`rounded-lg border p-2 text-center text-tiny font-medium transition ${
                          selectedKey.action.value === 'next'
                            ? 'border-accent bg-accent/20 text-accent'
                            : 'border-edge bg-card text-ink-dim hover:text-ink'
                        }`}
                      >
                        Next Page (→)
                      </button>
                      <button
                        type="button"
                        onClick={() =>
                          updateSelectedKey({
                            title:
                              selectedKey.title === `Key ${keyIndex + 1}`
                                ? 'Prev Page'
                                : selectedKey.title,
                            icon: 'skip_prev',
                            action: { type: 'page', value: 'prev' }
                          })
                        }
                        className={`rounded-lg border p-2 text-center text-tiny font-medium transition ${
                          selectedKey.action.value === 'prev'
                            ? 'border-accent bg-accent/20 text-accent'
                            : 'border-edge bg-card text-ink-dim hover:text-ink'
                        }`}
                      >
                        Previous Page (←)
                      </button>
                    </div>

                    {/* Specific Page Selector */}
                    <div>
                      <span className="block text-micro text-ink-faint mb-1.5">Jump to Page:</span>
                      <div className="flex flex-wrap gap-1.5">
                        {config.pages.map((p, pIdx) => {
                          const isTarget = selectedKey.action.value === String(pIdx);
                          return (
                            <button
                              key={p.id}
                              type="button"
                              onClick={() =>
                                updateSelectedKey({
                                  title:
                                    selectedKey.title === `Key ${keyIndex + 1}`
                                      ? p.name
                                      : selectedKey.title,
                                  action: { type: 'page', value: String(pIdx) }
                                })
                              }
                              className={`rounded-md border px-2.5 py-1 text-micro transition ${
                                isTarget
                                  ? 'border-accent bg-accent/20 text-accent font-semibold'
                                  : 'border-edge bg-card text-ink-dim hover:text-ink'
                              }`}
                            >
                              {p.name}
                            </button>
                          );
                        })}
                      </div>
                    </div>
                  </div>
                )}
              </div>

              {/* Visual Appearance Section */}
              <div className="border-t border-edge pt-4 space-y-4">
                <h3 className="text-micro font-semibold uppercase tracking-wider text-ink-faint">
                  Visual Appearance & Styling
                </h3>

                {/* Background Tile Color */}
                <div>
                  <div className="flex items-center justify-between text-micro font-medium text-ink-dim mb-1.5">
                    <span>Background Color</span>
                    <span className="font-mono text-ink-faint">
                      {selectedKey.bgColor || '#111827'}
                    </span>
                  </div>
                  <div className="flex items-center gap-1.5">
                    {TILE_COLOR_PRESETS.map((color) => (
                      <button
                        key={color}
                        type="button"
                        onClick={() => updateSelectedKey({ bgColor: color })}
                        style={{ backgroundColor: color }}
                        title={color}
                        className={`h-6 w-6 rounded-md border transition ${
                          selectedKey.bgColor === color
                            ? 'border-accent ring-2 ring-accent/60 scale-110'
                            : 'border-edge/80 hover:scale-105'
                        }`}
                      />
                    ))}
                    <label className="relative ml-auto grid h-7 w-7 place-items-center rounded-md border border-edge bg-canvas cursor-pointer overflow-hidden">
                      <input
                        type="color"
                        value={selectedKey.bgColor || '#111827'}
                        onChange={(e) => updateSelectedKey({ bgColor: e.target.value })}
                        className="opacity-0 absolute inset-0 cursor-pointer h-full w-full"
                      />
                      <span className="text-micro font-bold text-ink-dim">+</span>
                    </label>
                  </div>
                </div>

                {/* Icon Accent Color */}
                <div>
                  <div className="flex items-center justify-between text-micro font-medium text-ink-dim mb-1.5">
                    <span>Icon Color</span>
                    <span className="font-mono text-ink-faint">
                      {selectedKey.iconColor || '#7aa2f7'}
                    </span>
                  </div>
                  <div className="flex items-center gap-1.5">
                    {ICON_COLOR_PRESETS.map((color) => (
                      <button
                        key={color}
                        type="button"
                        onClick={() => updateSelectedKey({ iconColor: color })}
                        style={{ backgroundColor: color }}
                        title={color}
                        className={`h-6 w-6 rounded-md border transition ${
                          selectedKey.iconColor === color
                            ? 'border-accent ring-2 ring-accent/60 scale-110'
                            : 'border-edge/80 hover:scale-105'
                        }`}
                      />
                    ))}
                    <label className="relative ml-auto grid h-7 w-7 place-items-center rounded-md border border-edge bg-canvas cursor-pointer overflow-hidden">
                      <input
                        type="color"
                        value={selectedKey.iconColor || '#7aa2f7'}
                        onChange={(e) => updateSelectedKey({ iconColor: e.target.value })}
                        className="opacity-0 absolute inset-0 cursor-pointer h-full w-full"
                      />
                      <span className="text-micro font-bold text-ink-dim">+</span>
                    </label>
                  </div>
                </div>

                {/* Icon Selector Grid */}
                <div>
                  <div className="flex items-center justify-between text-micro font-medium text-ink-dim mb-1.5">
                    <span>Icon ({filteredIcons.length})</span>
                    <div className="relative w-28">
                      <input
                        type="text"
                        value={iconQuery}
                        onChange={(e) => setIconQuery(e.target.value)}
                        placeholder="Search…"
                        className="w-full rounded border border-edge bg-canvas px-2 py-0.5 text-micro text-ink focus:border-accent focus:outline-none"
                      />
                    </div>
                  </div>

                  <div className="grid grid-cols-6 sm:grid-cols-7 gap-1.5 max-h-40 overflow-y-auto rounded-lg border border-edge bg-canvas p-1.5">
                    {filteredIcons.map(([iconKey, IconCmp]) => {
                      const isPicked = selectedKey.icon === iconKey;
                      return (
                        <button
                          key={iconKey}
                          type="button"
                          onClick={() => updateSelectedKey({ icon: iconKey })}
                          title={ICON_LABELS[iconKey] || iconKey}
                          className={`grid h-8 place-items-center rounded-lg transition ${
                            isPicked
                              ? 'bg-accent/25 text-accent ring-1 ring-accent'
                              : 'text-ink-dim hover:bg-raised hover:text-ink'
                          }`}
                        >
                          <IconCmp className="h-4 w-4" />
                        </button>
                      );
                    })}
                  </div>
                </div>
              </div>
            </div>
          </div>
        </aside>
      </div>
    </main>
  );
};

export default DeckView;
