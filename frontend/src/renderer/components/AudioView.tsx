import {
  Play,
  SkipBack,
  SkipForward,
  Square,
  Volume2,
  VolumeX
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import type { LocalState, MediaAction } from '../../shared/types';
import { useThrottledCommit } from '../useHostState';
import { Card, Pane, Sidebar, SidebarItem } from './Shell';
import { LevelSlider } from './LevelSlider';

interface AudioViewProps {
  state: LocalState;
  patch: (fn: (draft: LocalState) => LocalState) => void;
  setPaused: (paused: boolean) => void;
}

const TRANSPORT: Array<{ action: MediaAction; label: string; icon: LucideIcon; primary?: boolean }> = [
  { action: 'prev', label: 'Previous track', icon: SkipBack },
  { action: 'toggle', label: 'Play or pause', icon: Play, primary: true },
  { action: 'next', label: 'Next track', icon: SkipForward },
  { action: 'stop', label: 'Stop', icon: Square }
];

export function AudioView({ state, patch, setPaused }: AudioViewProps) {
  const { volume } = state.host;
  const hasAudio = state.host.capabilities.includes('volume');
  const hasMedia = state.host.capabilities.includes('media');

  const sendVolume = useThrottledCommit((level: number) =>
    window.switchboard.setVolume(level, volume.muted)
  );

  const applyLocal = (level: number, muted: boolean) =>
    patch((draft) => ({ ...draft, host: { ...draft.host, volume: { level, muted } } }));

  const toggleMute = async () => {
    const next = !volume.muted;
    applyLocal(volume.level, next);
    await window.switchboard.setVolume(volume.level, next).catch(() => {});
  };

  return (
    <>
      <Sidebar title="Audio">
        <SidebarItem
          label="Master output"
          icon={volume.muted ? VolumeX : Volume2}
          detail={volume.muted ? 'Muted' : `${volume.level}%`}
          selected
          onSelect={() => {}}
        />
      </Sidebar>

      <Pane title="Audio" description="Master output and media transport">
        <div className="grid max-w-3xl gap-3">
          <Card
            title="Master volume"
            meta={
              <button
                type="button"
                onClick={toggleMute}
                disabled={!hasAudio}
                aria-pressed={volume.muted}
                className={`flex items-center gap-1.5 rounded border px-2 py-0.5 text-micro transition-colors disabled:opacity-40 ${
                  volume.muted
                    ? 'border-warn/40 bg-warn/10 text-warn'
                    : 'border-edge text-ink-dim hover:bg-raised hover:text-ink'
                }`}
              >
                {volume.muted ? (
                  <VolumeX aria-hidden="true" className="h-3 w-3" />
                ) : (
                  <Volume2 aria-hidden="true" className="h-3 w-3" />
                )}
                {volume.muted ? 'Muted' : 'Mute'}
              </button>
            }
          >
            <LevelSlider
              icon={volume.muted ? VolumeX : Volume2}
              label="Master volume"
              value={volume.level}
              min={0}
              max={100}
              disabled={!hasAudio}
              onGestureChange={setPaused}
              onChange={(level) => {
                applyLocal(level, volume.muted);
                sendVolume(level);
              }}
              onCommit={(level) => window.switchboard.setVolume(level, volume.muted).catch(() => {})}
            />
            {!hasAudio && (
              <p className="text-micro text-ink-faint">
                No audio endpoint is available on this host.
              </p>
            )}
          </Card>

          <Card title="Media transport">
            <div className="flex items-center gap-2">
              {TRANSPORT.map(({ action, label, icon: Icon, primary }) => (
                <button
                  key={action}
                  type="button"
                  aria-label={label}
                  title={label}
                  disabled={!hasMedia}
                  onClick={() => window.switchboard.media(action).catch(() => {})}
                  className={`flex h-10 w-10 items-center justify-center rounded-full transition-colors disabled:pointer-events-none disabled:opacity-40 ${
                    primary
                      ? 'bg-accent text-rail hover:brightness-110'
                      : 'border border-edge text-ink-dim hover:bg-raised hover:text-ink'
                  }`}
                >
                  <Icon aria-hidden="true" className="h-4 w-4" />
                </button>
              ))}
            </div>
            <p className="text-micro text-ink-faint">
              Commands reach whichever application currently owns the system media session.
            </p>
          </Card>
        </div>
      </Pane>
    </>
  );
}
