import {
  AudioLines,
  Play,
  SkipBack,
  SkipForward,
  Square,
  Volume2,
  VolumeX
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { useCallback } from 'react';
import type { AudioSession, LocalState, MediaAction } from '../../shared/types';
import { useThrottledCommit } from '../useHostState';
import { Card, EmptyState, Pane, Sidebar, SidebarItem } from './Shell';
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
  const { volume, mixer } = state.host;
  const hasAudio = state.host.capabilities.includes('volume');
  const hasMedia = state.host.capabilities.includes('media');
  const hasMixer = state.host.capabilities.includes('mixer');

  const sendVolume = useThrottledCommit((level: number) =>
    window.switchboard.setVolume(level, volume.muted)
  );

  const applyLocal = (level: number, muted: boolean) =>
    patch((draft) => ({ ...draft, host: { ...draft.host, volume: { level, muted } } }));

  const applySession = useCallback(
    (id: string, level: number, muted: boolean) => {
      patch((draft) => ({
        ...draft,
        host: {
          ...draft.host,
          mixer: draft.host.mixer.map((s) => (s.id === id ? { ...s, level, muted } : s))
        }
      }));
    },
    [patch]
  );

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

      <Pane
        title="Audio"
        description={
          hasMixer
            ? 'Master output, per-application levels and media transport'
            : 'Master output and media transport'
        }
      >
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

          {hasMixer && (
            <Card title="Applications">
              {mixer.length === 0 ? (
                <EmptyState
                  icon={AudioLines}
                  title="Nothing is using the mixer"
                  hint="Applications appear here once they open an audio stream. Start playback and the row shows up on the next poll."
                />
              ) : (
                mixer.map((session) => (
                  <SessionRow
                    key={session.id}
                    session={session}
                    onLocalChange={applySession}
                    setPaused={setPaused}
                  />
                ))
              )}
            </Card>
          )}

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

function SessionRow({
  session,
  onLocalChange,
  setPaused
}: {
  session: AudioSession;
  onLocalChange: (id: string, level: number, muted: boolean) => void;
  setPaused: (paused: boolean) => void;
}) {
  // Per-session writes go to the OS mixer one call at a time; a raw drag would
  // issue one per pixel, exactly as with DDC/CI brightness.
  const sendLevel = useThrottledCommit((level: number) =>
    window.switchboard.setSessionVolume(session.id, level, session.muted)
  );

  const toggleMute = async () => {
    const next = !session.muted;
    onLocalChange(session.id, session.level, next);
    await window.switchboard.setSessionVolume(session.id, session.level, next).catch(() => {});
  };

  return (
    // A silent player is dimmed rather than dropped: a row vanishing under the
    // pointer mid-gesture is worse than a quiet one.
    <div className={session.active ? undefined : 'opacity-50'}>
      <div className="mb-1.5 flex items-center justify-between gap-3">
        <span className="truncate text-micro text-ink-dim">{session.name}</span>
        <button
          type="button"
          onClick={toggleMute}
          aria-pressed={session.muted}
          aria-label={`Mute ${session.name}`}
          className={`shrink-0 rounded border p-1 transition-colors ${
            session.muted
              ? 'border-warn/40 bg-warn/10 text-warn'
              : 'border-edge text-ink-faint hover:bg-raised hover:text-ink'
          }`}
        >
          {session.muted ? (
            <VolumeX aria-hidden="true" className="h-3 w-3" />
          ) : (
            <Volume2 aria-hidden="true" className="h-3 w-3" />
          )}
        </button>
      </div>
      <LevelSlider
        icon={session.muted ? VolumeX : Volume2}
        label={`${session.name} volume`}
        value={session.level}
        min={0}
        max={100}
        onGestureChange={setPaused}
        onChange={(level) => {
          onLocalChange(session.id, level, session.muted);
          sendLevel(level);
        }}
        onCommit={(level) =>
          window.switchboard.setSessionVolume(session.id, level, session.muted).catch(() => {})
        }
      />
    </div>
  );
}
