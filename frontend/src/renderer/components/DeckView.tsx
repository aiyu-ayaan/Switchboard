import React, { useState, useEffect, useCallback, useMemo } from 'react';
import {
  Calendar,
  Camera,
  ChevronLeft,
  ChevronRight,
  Code,
  FileText,
  Folder,
  Globe,
  Home,
  Keyboard,
  Layers,
  Linkedin,
  Lock,
  Monitor,
  Music,
  Play,
  Plus,
  Power,
  RotateCcw,
  Search,
  Settings,
  Shield,
  Sparkles,
  Terminal,
  Trash2,
  Volume2,
  VolumeX,
  Youtube
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import type { DeckConfig, DeckKey, DeckPage, InstalledApp, LocalState } from '../../shared/types';

interface DeckViewProps {
  state: LocalState;
}

// Icon dictionary mapping keys to Lucide icons with fallback
const ICON_MAP: Record<string, LucideIcon> = {
  home: Home,
  notes: FileText,
  folder: Folder,
  calendar: Calendar,
  google: Globe,
  youtube: Youtube,
  spotify: Music,
  linkedin: Linkedin,
  terminal: Terminal,
  media: Play,
  play_pause: Play,
  skip_next: ChevronRight,
  skip_prev: ChevronLeft,
  volume_up: Volume2,
  volume_down: Volume2,
  volume_mute: VolumeX,
  lock: Lock,
  camera: Camera,
  code: Code,
  monitor: Monitor,
  globe: Globe,
  search: Search,
  power: Power,
  shield: Shield,
  sparkles: Sparkles,
  layers: Layers,
  settings: Settings
};

const ICON_CHOICES = [
  { id: 'home', label: 'Home', icon: Home },
  { id: 'notes', label: 'Notes', icon: FileText },
  { id: 'folder', label: 'Folder', icon: Folder },
  { id: 'calendar', label: 'Calendar', icon: Calendar },
  { id: 'google', label: 'Google', icon: Globe },
  { id: 'youtube', label: 'YouTube', icon: Youtube },
  { id: 'spotify', label: 'Spotify', icon: Music },
  { id: 'linkedin', label: 'LinkedIn', icon: Linkedin },
  { id: 'terminal', label: 'Terminal', icon: Terminal },
  { id: 'play_pause', label: 'Media', icon: Play },
  { id: 'volume_up', label: 'Volume', icon: Volume2 },
  { id: 'volume_mute', label: 'Mute', icon: VolumeX },
  { id: 'lock', label: 'Lock', icon: Lock },
  { id: 'camera', label: 'Screenshot', icon: Camera },
  { id: 'code', label: 'Code', icon: Code },
  { id: 'monitor', label: 'Display', icon: Monitor },
  { id: 'settings', label: 'Settings', icon: Settings }
];

const PRESET_URLS = [
  { label: 'Google', url: 'https://google.com', icon: 'google' },
  { label: 'YouTube', url: 'https://youtube.com', icon: 'youtube' },
  { label: 'Spotify', url: 'https://open.spotify.com', icon: 'spotify' },
  { label: 'LinkedIn', url: 'https://linkedin.com', icon: 'linkedin' },
  { label: 'GitHub', url: 'https://github.com', icon: 'code' },
  { label: 'Calendar', url: 'https://calendar.google.com', icon: 'calendar' }
];

const PRESET_HOTKEYS = [
  { label: 'Show Desktop', chord: 'win+d' },
  { label: 'Task View', chord: 'win+tab' },
  { label: 'Screenshot', chord: 'win+shift+s' },
  { label: 'Copy', chord: 'ctrl+c' },
  { label: 'Paste', chord: 'ctrl+v' },
  { label: 'Undo', chord: 'ctrl+z' },
  { label: 'Task Manager', chord: 'ctrl+shift+esc' },
  { label: 'Close Window', chord: 'alt+f4' },
  { label: 'Refresh Page', chord: 'f5' }
];

function findBestMatchingIcon(name: string): string | undefined {
  const lower = name.toLowerCase();
  if (lower.includes('code') || lower.includes('visual studio') || lower.includes('sublime') || lower.includes('git')) return 'code';
  if (lower.includes('term') || lower.includes('cmd') || lower.includes('powershell') || lower.includes('bash')) return 'terminal';
  if (lower.includes('chrome') || lower.includes('edge') || lower.includes('firefox') || lower.includes('brave') || lower.includes('browser')) return 'globe';
  if (lower.includes('music') || lower.includes('spotify') || lower.includes('sound')) return 'spotify';
  if (lower.includes('note') || lower.includes('word') || lower.includes('document')) return 'notes';
  if (lower.includes('file') || lower.includes('explorer')) return 'folder';
  if (lower.includes('camera') || lower.includes('snip') || lower.includes('screen')) return 'camera';
  if (lower.includes('setting') || lower.includes('control') || lower.includes('task')) return 'settings';
  if (lower.includes('calc')) return 'calendar';
  return undefined;
}

export const DeckView: React.FC<DeckViewProps> = ({ state }) => {
  const [config, setConfig] = useState<DeckConfig | null>(null);
  const [activePageIndex, setActivePageIndex] = useState(0);
  const [selectedKeyIndex, setSelectedKeyIndex] = useState<number | null>(0);
  const [isEditingInfobar, setIsEditingInfobar] = useState(false);
  const [actionNotice, setActionNotice] = useState<string | null>(null);
  const [timeStr, setTimeStr] = useState<string>('');
  const [dateStr, setDateStr] = useState<string>('');
  const [installedApps, setInstalledApps] = useState<InstalledApp[]>([]);
  const [appSearch, setAppSearch] = useState('');

  // Clock ticker for Infobar
  useEffect(() => {
    const updateTime = () => {
      const now = new Date();
      const days = ['SUNDAY', 'MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY'];
      const dayName = days[now.getDay()];
      const d = now.getDate();
      const m = now.getMonth() + 1;
      const y = String(now.getFullYear()).slice(-2);
      setDateStr(`${dayName} ${d}/${m}/${y}`);

      let hours = now.getHours();
      const minutes = String(now.getMinutes()).padStart(2, '0');
      const ampm = hours >= 12 ? 'PM' : 'AM';
      hours = hours % 12 || 12;
      setTimeStr(`${hours}:${minutes} ${ampm}`);
    };

    updateTime();
    const interval = setInterval(updateTime, 1000);
    return () => clearInterval(interval);
  }, []);

  // Fetch initial configuration
  const fetchConfig = useCallback(async () => {
    try {
      const data = await window.switchboard.deck.get();
      setConfig(data);
      if (data.activePage >= 0 && data.activePage < data.pages.length) {
        setActivePageIndex(data.activePage);
      }
    } catch (err) {
      console.error('Failed to load DeckConfig:', err);
    }
  }, []);

  // Fetch installed applications on the host system
  useEffect(() => {
    window.switchboard.deck
      .apps()
      .then((apps) => {
        if (Array.isArray(apps)) {
          setInstalledApps(apps);
        }
      })
      .catch((err) => console.error('Failed to load installed apps:', err));
  }, []);

  useEffect(() => {
    fetchConfig();
  }, [fetchConfig]);

  // Periodic sync of DeckConfig from daemon to reflect changes made from mobile
  useEffect(() => {
    const pollInterval = setInterval(async () => {
      try {
        const latest = await window.switchboard.deck.get();
        setConfig((prev) => {
          if (!prev) return latest;
          if (JSON.stringify(prev) !== JSON.stringify(latest)) {
            return latest;
          }
          return prev;
        });
      } catch (err) {
        // ignore poll errors
      }
    }, 2500);
    return () => clearInterval(pollInterval);
  }, []);

  // Save changes to backend and broadcast
  const saveConfig = useCallback(async (newConfig: DeckConfig) => {
    setConfig(newConfig);
    try {
      await window.switchboard.deck.set(newConfig);
    } catch (err) {
      console.error('Failed to save deck config:', err);
    }
  }, []);

  const currentPage = useMemo<DeckPage | undefined>(() => {
    if (!config || !config.pages || config.pages.length === 0) return undefined;
    return config.pages[activePageIndex] || config.pages[0];
  }, [config, activePageIndex]);

  const selectedKey = useMemo<DeckKey | undefined>(() => {
    if (!currentPage || selectedKeyIndex === null) return undefined;
    return currentPage.keys.find((k) => k.index === selectedKeyIndex);
  }, [currentPage, selectedKeyIndex]);

  const filteredApps = useMemo(() => {
    if (!appSearch.trim()) return installedApps;
    const q = appSearch.toLowerCase();
    return installedApps.filter(
      (a) => a.name.toLowerCase().includes(q) || a.path.toLowerCase().includes(q)
    );
  }, [installedApps, appSearch]);

  // Page switching via Touch Points
  const handlePrevPage = () => {
    if (!config || config.pages.length <= 1) return;
    const nextIdx = (activePageIndex - 1 + config.pages.length) % config.pages.length;
    setActivePageIndex(nextIdx);
    saveConfig({ ...config, activePage: nextIdx });
  };

  const handleNextPage = () => {
    if (!config || config.pages.length <= 1) return;
    const nextIdx = (activePageIndex + 1) % config.pages.length;
    setActivePageIndex(nextIdx);
    saveConfig({ ...config, activePage: nextIdx });
  };

  const handleAddPage = () => {
    if (!config) return;
    const newPageId = `page-${Date.now()}`;
    const newPage: DeckPage = {
      id: newPageId,
      name: `Page ${config.pages.length + 1}`,
      keys: Array.from({ length: 8 }, (_, i) => ({
        index: i,
        title: `Key ${i + 1}`,
        icon: 'code',
        bgColor: '#111827',
        iconColor: '#60a5fa',
        action: { type: 'url', value: 'https://google.com' }
      }))
    };
    const updated = {
      ...config,
      pages: [...config.pages, newPage],
      activePage: config.pages.length
    };
    setActivePageIndex(config.pages.length);
    saveConfig(updated);
  };

  const handleDeletePage = () => {
    if (!config || config.pages.length <= 1) return;
    const filtered = config.pages.filter((_, idx) => idx !== activePageIndex);
    const newIdx = Math.max(0, activePageIndex - 1);
    const updated = {
      ...config,
      pages: filtered,
      activePage: newIdx
    };
    setActivePageIndex(newIdx);
    saveConfig(updated);
  };

  // Triggering action
  const handleTriggerAction = async (key: DeckKey) => {
    try {
      setActionNotice(`Triggered: ${key.title} (${key.action.type})`);
      setTimeout(() => setActionNotice(null), 2500);
      await window.switchboard.deck.action({
        pageId: currentPage?.id,
        keyIndex: key.index,
        action: key.action
      });
    } catch (err) {
      setActionNotice(`Failed: ${err instanceof Error ? err.message : String(err)}`);
      setTimeout(() => setActionNotice(null), 3000);
    }
  };

  // Key updates from Inspector
  const handleUpdateKey = (patch: Partial<DeckKey>) => {
    if (!config || !currentPage || selectedKeyIndex === null) return;
    const updatedKeys = currentPage.keys.map((k) =>
      k.index === selectedKeyIndex ? { ...k, ...patch } : k
    );
    // Ensure all 8 keys exist
    const fullKeys: DeckKey[] = Array.from({ length: 8 }, (_, i) => {
      const existing = updatedKeys.find((k) => k.index === i);
      return existing || {
        index: i,
        title: `Key ${i + 1}`,
        icon: 'code',
        action: { type: 'url' as const, value: '' }
      };
    });

    const updatedPages = config.pages.map((p, idx) =>
      idx === activePageIndex ? { ...p, keys: fullKeys } : p
    );
    saveConfig({ ...config, pages: updatedPages });
  };

  const handleUpdateInfobar = (patch: Partial<DeckConfig['infobar']>) => {
    if (!config) return;
    const updated = {
      ...config,
      infobar: { ...config.infobar, ...patch }
    };
    saveConfig(updated);
  };

  if (!config) {
    return (
      <div className="flex flex-1 items-center justify-center bg-canvas">
        <p className="text-tiny text-ink-faint">Loading Stream Deck Neo...</p>
      </div>
    );
  }

  // Infobar text resolution
  const renderInfobarContent = () => {
    const mode = config.infobar?.mode || 'clock';
    if (mode === 'media' && state.host.media.active && state.host.media.title) {
      return (
        <div className="flex items-center gap-2 overflow-hidden px-3 text-center">
          <Music className="h-3 w-3 shrink-0 text-accent animate-pulse" />
          <span className="truncate text-xs font-medium text-ink">
            {state.host.media.title} {state.host.media.artist ? `— ${state.host.media.artist}` : ''}
          </span>
        </div>
      );
    }
    if (mode === 'page') {
      return (
        <div className="flex items-center justify-center gap-2 text-xs font-mono font-medium text-ink">
          <span>{currentPage?.name || 'Page 1'}</span>
          <span className="text-ink-faint">({activePageIndex + 1} / {config.pages.length})</span>
        </div>
      );
    }
    if (mode === 'text' && config.infobar.customText) {
      return (
        <div className="truncate px-3 text-xs font-mono font-medium text-ink">
          {config.infobar.customText}
        </div>
      );
    }

    // Default Clock Mode (Matches exact physical device display)
    return (
      <div className="flex w-full items-center justify-between px-3 text-mono font-mono text-[11px] font-bold tracking-tight text-white/90">
        <span className="uppercase text-white/70">{dateStr}</span>
        <span className="text-white drop-shadow-[0_0_8px_rgba(255,255,255,0.4)]">{timeStr}</span>
      </div>
    );
  };

  return (
    <div className="flex flex-1 flex-col overflow-y-auto bg-canvas p-6 lg:flex-row lg:items-start lg:gap-8">
      {/* LEFT: Stream Deck Neo Hardware Emulation Frame */}
      <div className="flex flex-col items-center">
        {/* Header Title & Page selector */}
        <div className="mb-4 flex w-full max-w-[540px] items-center justify-between">
          <div className="flex items-center gap-2">
            <h2 className="text-sm font-semibold tracking-wide text-ink">Elgato Stream Deck Neo</h2>
            <span className="rounded-full bg-accent/15 px-2 py-0.5 text-[10px] font-medium text-accent">
              Hardware Mirror
            </span>
          </div>

          <div className="flex items-center gap-1.5">
            {config.pages.map((p, idx) => (
              <button
                key={p.id}
                type="button"
                onClick={() => {
                  setActivePageIndex(idx);
                  saveConfig({ ...config, activePage: idx });
                }}
                className={`h-6 rounded px-2 text-tiny font-medium transition-colors ${
                  idx === activePageIndex
                    ? 'bg-raised text-ink border border-accent/40 shadow-sm'
                    : 'text-ink-faint hover:text-ink hover:bg-rail'
                }`}
              >
                {p.name || `P${idx + 1}`}
              </button>
            ))}
            <button
              type="button"
              onClick={handleAddPage}
              title="Add Page"
              className="flex h-6 w-6 items-center justify-center rounded text-ink-faint hover:bg-rail hover:text-ink"
            >
              <Plus className="h-3.5 w-3.5" />
            </button>
            {config.pages.length > 1 && (
              <button
                type="button"
                onClick={handleDeletePage}
                title="Delete Current Page"
                className="flex h-6 w-6 items-center justify-center rounded text-danger/70 hover:bg-danger/15 hover:text-danger"
              >
                <Trash2 className="h-3.5 w-3.5" />
              </button>
            )}
          </div>
        </div>

        {/* The Neo Casing Frame */}
        <div className="relative flex w-full max-w-[540px] flex-col items-center rounded-[36px] bg-[#eef1f5] p-7 shadow-[0_20px_50px_rgba(0,0,0,0.35),0_2px_8px_rgba(0,0,0,0.12),inset_0_2px_4px_rgba(255,255,255,0.8)] border border-[#d8dee9] dark:bg-[#e4e7ed] dark:border-[#c5cbd6]">
          {/* Subtle Top Center Neo Logo Circle */}
          <div className="mb-4 flex items-center justify-center">
            <div className="flex h-6 w-6 items-center justify-center rounded-full border border-black/15 bg-white/60 shadow-sm">
              <svg className="h-3.5 w-3.5 opacity-60" viewBox="0 0 24 24" fill="currentColor">
                <circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" strokeWidth="2" />
                <path d="M12 7v5l3 3" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
              </svg>
            </div>
          </div>

          {/* 8 LCD Keys Grid (2 rows x 4 columns) */}
          <div className="grid w-full grid-cols-4 gap-3.5">
            {Array.from({ length: 8 }).map((_, i) => {
              const key = currentPage?.keys.find((k) => k.index === i) || {
                index: i,
                title: '',
                icon: 'code',
                action: { type: 'url', value: '' }
              };
              const isSelected = selectedKeyIndex === i;
              const IconComp = ICON_MAP[key.icon] || Globe;

              return (
                <button
                  key={i}
                  type="button"
                  onClick={() => setSelectedKeyIndex(i)}
                  onDoubleClick={() => handleTriggerAction(key)}
                  className={`group relative flex aspect-square flex-col items-center justify-between overflow-hidden rounded-[20px] p-2.5 transition-all duration-150 active:scale-95 ${
                    isSelected
                      ? 'ring-2 ring-accent ring-offset-2 ring-offset-[#eef1f5] shadow-lg'
                      : 'shadow-[0_4px_12px_rgba(0,0,0,0.25),inset_0_1px_1px_rgba(255,255,255,0.2)] hover:brightness-110'
                  }`}
                  style={{
                    backgroundColor: key.bgColor || '#0d1117'
                  }}
                >
                  {/* Subtle glassy highlight top reflection */}
                  <div className="pointer-events-none absolute inset-x-0 top-0 h-1/2 rounded-t-[20px] bg-gradient-to-b from-white/15 to-transparent" />

                  {/* Badge (e.g. green indicator dot like in reference image) */}
                  {key.badge && (
                    <div className="absolute right-2 top-2 z-10 h-2.5 w-2.5 rounded-full bg-emerald-400 shadow-[0_0_6px_rgba(52,211,153,0.8)] border border-black/40" />
                  )}

                  {/* Icon */}
                  <div className="z-10 mt-1 flex flex-1 items-center justify-center">
                    <IconComp
                      className="h-7 w-7 transition-transform group-hover:scale-110"
                      style={{ color: key.iconColor || '#60a5fa' }}
                    />
                  </div>

                  {/* Key Label */}
                  <span className="z-10 w-full truncate text-center text-[10px] font-medium tracking-tight text-white/90 drop-shadow-sm">
                    {key.title || `Key ${i + 1}`}
                  </span>
                </button>
              );
            })}
          </div>

          {/* Bottom Row: Left Touch Point + Centered Infobar + Right Touch Point */}
          <div className="mt-5 flex w-full items-center justify-between gap-3 px-1">
            {/* Left Touch Point (Previous Page) */}
            <button
              type="button"
              onClick={handlePrevPage}
              title="Previous Page"
              className="group flex flex-col items-center justify-center p-2 transition-transform active:scale-90"
            >
              {/* Glowing LED Bar */}
              <div className="h-1 w-8 rounded-full bg-white/80 shadow-[0_0_8px_rgba(255,255,255,0.9)] transition-all group-hover:bg-white group-hover:shadow-[0_0_12px_rgba(255,255,255,1)]" />
              {/* Sensor dot */}
              <div className="mt-1.5 h-1.5 w-1.5 rounded-full bg-black/30" />
            </button>

            {/* Central Infobar Display */}
            <div
              onClick={() => setIsEditingInfobar(true)}
              className="flex h-9 flex-1 cursor-pointer items-center justify-center rounded-full bg-[#0a0a0f] border border-[#232733] shadow-[inset_0_2px_4px_rgba(0,0,0,0.8),0_1px_2px_rgba(255,255,255,0.4)] transition-all hover:border-accent/60"
            >
              {renderInfobarContent()}
            </div>

            {/* Right Touch Point (Next Page) */}
            <button
              type="button"
              onClick={handleNextPage}
              title="Next Page"
              className="group flex flex-col items-center justify-center p-2 transition-transform active:scale-90"
            >
              {/* Glowing LED Bar */}
              <div className="h-1 w-8 rounded-full bg-white/80 shadow-[0_0_8px_rgba(255,255,255,0.9)] transition-all group-hover:bg-white group-hover:shadow-[0_0_12px_rgba(255,255,255,1)]" />
              {/* Sensor dot */}
              <div className="mt-1.5 h-1.5 w-1.5 rounded-full bg-black/30" />
            </button>
          </div>
        </div>

        {/* Action Feedback Banner */}
        {actionNotice && (
          <div className="mt-3 flex items-center gap-2 rounded-lg bg-raised px-3 py-1.5 text-tiny text-ink border border-accent/30 shadow">
            <Sparkles className="h-3.5 w-3.5 text-accent animate-spin" />
            <span>{actionNotice}</span>
          </div>
        )}

        <p className="mt-2 text-center text-micro text-ink-faint">
          Double-click any key to execute on PC • Touch points cycle pages
        </p>
      </div>

      {/* RIGHT: Inspector & Customizer Panel */}
      <div className="mt-6 flex flex-1 flex-col rounded-2xl border border-edge bg-surface p-5 shadow-sm lg:mt-0">
        {selectedKey ? (
          <div className="space-y-4">
            <div className="flex items-center justify-between border-b border-edge pb-3">
              <div>
                <h3 className="text-sm font-semibold text-ink">
                  Customize Key #{selectedKey.index + 1}
                </h3>
                <p className="text-tiny text-ink-faint">
                  Changes save automatically and sync to mobile
                </p>
              </div>

              <button
                type="button"
                onClick={() => handleTriggerAction(selectedKey)}
                className="flex items-center gap-1.5 rounded-lg bg-accent px-3 py-1.5 text-tiny font-medium text-canvas shadow transition-colors hover:brightness-110 active:scale-95"
              >
                <Play className="h-3.5 w-3.5 fill-current" />
                <span>Test Action</span>
              </button>
            </div>

            {/* Title / Label */}
            <div>
              <label className="block text-tiny font-medium text-ink-dim">Key Label</label>
              <input
                type="text"
                value={selectedKey.title}
                onChange={(e) => handleUpdateKey({ title: e.target.value })}
                placeholder="Label"
                className="mt-1 w-full rounded-lg border border-edge bg-canvas px-3 py-1.5 text-tiny text-ink placeholder:text-ink-faint focus:border-accent focus:outline-none"
              />
            </div>

            {/* Icon Picker Grid */}
            <div>
              <label className="block text-tiny font-medium text-ink-dim">Select Icon</label>
              <div className="mt-1.5 grid grid-cols-6 gap-2 rounded-lg border border-edge bg-canvas p-2.5">
                {ICON_CHOICES.map(({ id, label, icon: Icon }) => (
                  <button
                    key={id}
                    type="button"
                    title={label}
                    onClick={() => handleUpdateKey({ icon: id })}
                    className={`flex flex-col items-center justify-center rounded-lg p-2 transition-colors ${
                      selectedKey.icon === id
                        ? 'bg-accent/20 text-accent border border-accent/40'
                        : 'text-ink-dim hover:bg-surface hover:text-ink'
                    }`}
                  >
                    <Icon className="h-4 w-4" />
                    <span className="mt-1 text-[9px] truncate w-full text-center">{label}</span>
                  </button>
                ))}
              </div>
            </div>

            {/* Colors & Badge */}
            <div className="grid grid-cols-3 gap-3">
              <div>
                <label className="block text-tiny font-medium text-ink-dim">Background</label>
                <div className="mt-1 flex items-center gap-2">
                  <input
                    type="color"
                    value={selectedKey.bgColor || '#0d1117'}
                    onChange={(e) => handleUpdateKey({ bgColor: e.target.value })}
                    className="h-8 w-8 cursor-pointer rounded border border-edge bg-transparent"
                  />
                  <span className="font-mono text-tiny text-ink-faint">
                    {selectedKey.bgColor || '#0d1117'}
                  </span>
                </div>
              </div>

              <div>
                <label className="block text-tiny font-medium text-ink-dim">Icon Color</label>
                <div className="mt-1 flex items-center gap-2">
                  <input
                    type="color"
                    value={selectedKey.iconColor || '#60a5fa'}
                    onChange={(e) => handleUpdateKey({ iconColor: e.target.value })}
                    className="h-8 w-8 cursor-pointer rounded border border-edge bg-transparent"
                  />
                  <span className="font-mono text-tiny text-ink-faint">
                    {selectedKey.iconColor || '#60a5fa'}
                  </span>
                </div>
              </div>

              <div>
                <label className="block text-tiny font-medium text-ink-dim">Badge Dot</label>
                <select
                  value={selectedKey.badge || ''}
                  onChange={(e) => handleUpdateKey({ badge: e.target.value || undefined })}
                  className="mt-1 w-full rounded-lg border border-edge bg-canvas px-2.5 py-1.5 text-tiny text-ink focus:border-accent focus:outline-none"
                >
                  <option value="">None</option>
                  <option value="active">Active (Green Dot)</option>
                </select>
              </div>
            </div>

            {/* Action Type Selector */}
            <div className="border-t border-edge pt-3">
              <label className="block text-tiny font-medium text-ink-dim">Action Type</label>
              <div className="mt-1.5 flex gap-1.5">
                {(['url', 'hotkey', 'media', 'system', 'app'] as const).map((type) => (
                  <button
                    key={type}
                    type="button"
                    onClick={() =>
                      handleUpdateKey({
                        action: {
                          type,
                          value:
                            type === 'url'
                              ? 'https://google.com'
                              : type === 'hotkey'
                              ? 'win+d'
                              : type === 'media'
                              ? 'toggle'
                              : type === 'system'
                              ? 'lock'
                              : 'notepad'
                        }
                      })
                    }
                    className={`flex-1 rounded-lg py-1.5 text-tiny font-medium capitalize transition-colors ${
                      selectedKey.action.type === type
                        ? 'bg-raised text-accent border border-accent/40 shadow-sm'
                        : 'text-ink-faint hover:text-ink hover:bg-canvas'
                    }`}
                  >
                    {type}
                  </button>
                ))}
              </div>
            </div>

            {/* Action Specific Config */}
            {selectedKey.action.type === 'url' && (
              <div className="space-y-2">
                <label className="block text-tiny font-medium text-ink-dim">Web URL</label>
                <div className="flex gap-2">
                  <input
                    type="url"
                    value={selectedKey.action.value}
                    onChange={(e) =>
                      handleUpdateKey({
                        action: { type: 'url', value: e.target.value }
                      })
                    }
                    placeholder="https://..."
                    className="flex-1 rounded-lg border border-edge bg-canvas px-3 py-1.5 text-tiny text-ink placeholder:text-ink-faint focus:border-accent focus:outline-none"
                  />
                </div>
                <div className="flex flex-wrap gap-1.5 pt-1">
                  {PRESET_URLS.map((p) => (
                    <button
                      key={p.label}
                      type="button"
                      onClick={() =>
                        handleUpdateKey({
                          title: p.label,
                          icon: p.icon,
                          action: { type: 'url', value: p.url }
                        })
                      }
                      className="rounded bg-canvas px-2 py-1 text-[10px] text-ink-dim hover:text-accent hover:border-accent/40 border border-edge"
                    >
                      {p.label}
                    </button>
                  ))}
                </div>
              </div>
            )}

            {selectedKey.action.type === 'hotkey' && (
              <div className="space-y-2">
                <label className="block text-tiny font-medium text-ink-dim">Shortcut Chord</label>
                <div className="flex items-center gap-2">
                  <Keyboard className="h-4 w-4 text-accent" />
                  <input
                    type="text"
                    value={selectedKey.action.value}
                    onChange={(e) =>
                      handleUpdateKey({
                        action: { type: 'hotkey', value: e.target.value }
                      })
                    }
                    placeholder="e.g. win+d or ctrl+shift+esc"
                    className="flex-1 rounded-lg border border-edge bg-canvas px-3 py-1.5 font-mono text-tiny text-ink placeholder:text-ink-faint focus:border-accent focus:outline-none"
                  />
                </div>
                <div className="flex flex-wrap gap-1.5 pt-1">
                  {PRESET_HOTKEYS.map((p) => (
                    <button
                      key={p.label}
                      type="button"
                      onClick={() =>
                        handleUpdateKey({
                          title: p.label,
                          action: { type: 'hotkey', value: p.chord }
                        })
                      }
                      className="rounded bg-canvas px-2 py-1 font-mono text-[10px] text-ink-dim hover:text-accent hover:border-accent/40 border border-edge"
                    >
                      {p.label} ({p.chord})
                    </button>
                  ))}
                </div>
              </div>
            )}

            {selectedKey.action.type === 'media' && (
              <div>
                <label className="block text-tiny font-medium text-ink-dim">Media Command</label>
                <select
                  value={selectedKey.action.value}
                  onChange={(e) =>
                    handleUpdateKey({
                      action: { type: 'media', value: e.target.value }
                    })
                  }
                  className="mt-1 w-full rounded-lg border border-edge bg-canvas px-3 py-1.5 text-tiny text-ink focus:border-accent focus:outline-none"
                >
                  <option value="toggle">Play / Pause Toggle</option>
                  <option value="next">Next Track</option>
                  <option value="prev">Previous Track</option>
                  <option value="stop">Stop</option>
                  <option value="vol_up">Volume +5%</option>
                  <option value="vol_down">Volume -5%</option>
                  <option value="mute">Mute / Unmute</option>
                </select>
              </div>
            )}

            {selectedKey.action.type === 'system' && (
              <div>
                <label className="block text-tiny font-medium text-ink-dim">System Action</label>
                <select
                  value={selectedKey.action.value}
                  onChange={(e) =>
                    handleUpdateKey({
                      action: { type: 'system', value: e.target.value }
                    })
                  }
                  className="mt-1 w-full rounded-lg border border-edge bg-canvas px-3 py-1.5 text-tiny text-ink focus:border-accent focus:outline-none"
                >
                  <option value="lock">Lock Workstation</option>
                  <option value="screenshot">Windows Snipping Tool (Win+Shift+S)</option>
                  <option value="bright_up">Brightness +10%</option>
                  <option value="bright_down">Brightness -10%</option>
                </select>
              </div>
            )}

            {selectedKey.action.type === 'app' && (
              <div className="space-y-2">
                <label className="block text-tiny font-medium text-ink-dim">Installed Applications</label>
                <div className="relative">
                  <Search className="absolute left-2.5 top-2.5 h-3.5 w-3.5 text-ink-faint" />
                  <input
                    type="text"
                    value={appSearch}
                    onChange={(e) => setAppSearch(e.target.value)}
                    placeholder="Search installed applications..."
                    className="w-full rounded-lg border border-edge bg-canvas pl-8 pr-3 py-1.5 text-tiny text-ink placeholder:text-ink-faint focus:border-accent focus:outline-none"
                  />
                </div>

                <div className="max-h-48 overflow-y-auto rounded-lg border border-edge bg-canvas divide-y divide-edge/40">
                  {filteredApps.length > 0 ? (
                    filteredApps.map((app) => {
                      const isSelected = selectedKey.action.value === (app.path || app.name);
                      return (
                        <button
                          key={app.path || app.name}
                          type="button"
                          onClick={() => {
                            const matchingIcon = findBestMatchingIcon(app.name);
                            handleUpdateKey({
                              title: selectedKey.title.startsWith('Key ') || selectedKey.title === '' ? app.name : selectedKey.title,
                              icon: matchingIcon || selectedKey.icon,
                              action: { type: 'app', value: app.path || app.name }
                            });
                          }}
                          className={`w-full flex items-center justify-between px-3 py-1.5 text-left text-tiny transition-colors hover:bg-raised ${
                            isSelected ? 'bg-raised text-accent font-medium' : 'text-ink'
                          }`}
                        >
                          <div className="flex items-center gap-2 min-w-0">
                            <Terminal className="h-3.5 w-3.5 shrink-0 text-accent/80" />
                            <span className="truncate">{app.name}</span>
                          </div>
                          <span className="text-[10px] text-ink-faint font-mono ml-2 shrink-0 max-w-[120px] truncate">
                            {app.path.split(/[\\/]/).pop()}
                          </span>
                        </button>
                      );
                    })
                  ) : (
                    <div className="p-3 text-center text-tiny text-ink-faint">
                      {installedApps.length === 0 ? 'Scanning installed apps...' : 'No matching apps found'}
                    </div>
                  )}
                </div>

                <div>
                  <label className="block text-[11px] font-medium text-ink-dim">Manual Path or Command</label>
                  <input
                    type="text"
                    value={selectedKey.action.value}
                    onChange={(e) =>
                      handleUpdateKey({
                        action: { type: 'app', value: e.target.value }
                      })
                    }
                    placeholder="e.g. notepad, calc, explorer, wt, or full .exe path"
                    className="mt-1 w-full rounded-lg border border-edge bg-canvas px-3 py-1.5 font-mono text-tiny text-ink placeholder:text-ink-faint focus:border-accent focus:outline-none"
                  />
                </div>
              </div>
            )}

            {/* Clear Key Button */}
            <div className="pt-2">
              <button
                type="button"
                onClick={() =>
                  handleUpdateKey({
                    title: `Key ${selectedKey.index + 1}`,
                    icon: 'code',
                    badge: undefined,
                    bgColor: '#0d1117',
                    iconColor: '#60a5fa',
                    action: { type: 'url', value: '' }
                  })
                }
                className="flex items-center gap-1.5 text-tiny text-ink-faint hover:text-danger"
              >
                <RotateCcw className="h-3.5 w-3.5" />
                <span>Reset Key to Default</span>
              </button>
            </div>
          </div>
        ) : (
          <div className="flex flex-1 items-center justify-center text-tiny text-ink-faint">
            Select a key on the Stream Deck Neo to configure it.
          </div>
        )}

        {/* Infobar Settings Modal */}
        {isEditingInfobar && (
          <div className="mt-4 border-t border-edge pt-4">
            <div className="flex items-center justify-between pb-2">
              <h4 className="text-tiny font-semibold text-ink">Infobar Display Settings</h4>
              <button
                type="button"
                onClick={() => setIsEditingInfobar(false)}
                className="text-tiny text-ink-dim hover:text-ink"
              >
                Done
              </button>
            </div>

            <div className="space-y-3">
              <div>
                <label className="block text-[11px] font-medium text-ink-dim">Display Mode</label>
                <div className="mt-1 grid grid-cols-4 gap-2">
                  {(['clock', 'media', 'page', 'text'] as const).map((mode) => (
                    <button
                      key={mode}
                      type="button"
                      onClick={() => handleUpdateInfobar({ mode })}
                      className={`rounded py-1 text-tiny capitalize transition-colors ${
                        (config.infobar?.mode || 'clock') === mode
                          ? 'bg-raised text-accent border border-accent/40 font-medium'
                          : 'bg-canvas text-ink-dim hover:text-ink border border-edge'
                      }`}
                    >
                      {mode}
                    </button>
                  ))}
                </div>
              </div>

              {config.infobar?.mode === 'text' && (
                <div>
                  <label className="block text-[11px] font-medium text-ink-dim">Custom Text</label>
                  <input
                    type="text"
                    value={config.infobar.customText || ''}
                    onChange={(e) => handleUpdateInfobar({ customText: e.target.value })}
                    placeholder="Custom status..."
                    className="mt-1 w-full rounded border border-edge bg-canvas px-2.5 py-1 text-tiny text-ink focus:border-accent focus:outline-none"
                  />
                </div>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  );
};
