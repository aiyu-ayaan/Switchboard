import {
  AudioLines,
  Music,
  Pause,
  Play,
  SkipBack,
  SkipForward,
  Square,
  Volume2,
  VolumeX
} from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import type { AudioSession, LocalState } from '../../shared/types';
import { useThrottledCommit } from '../useHostState';
import { Card, EmptyState, Pane, Sidebar, SidebarItem } from './Shell';
import { LevelSlider } from './LevelSlider';

interface AudioViewProps {
  state: LocalState;
  patch: (fn: (draft: LocalState) => LocalState) => void;
  setPaused: (paused: boolean) => void;
}

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

  const media = state.host.media;
  const isPlaying = media.status === 'playing';
  const [artworkUrl, setArtworkUrl] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    if (!media.artworkId) {
      setArtworkUrl(null);
      return;
    }
    window.switchboard.getMediaArtwork().then((art) => {
      if (!cancelled && art && art.data) {
        setArtworkUrl(`data:${art.mimeType || 'image/jpeg'};base64,${art.data}`);
      }
    }).catch(() => {
      if (!cancelled) setArtworkUrl(null);
    });
    return () => {
      cancelled = true;
    };
  }, [media.artworkId]);

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
            {hasMedia && media.active && (media.title || media.artist) && (
              <div className="mb-3 flex items-center gap-3.5 rounded-lg border border-edge bg-raised/40 p-2.5">
                {artworkUrl ? (
                  <img
                    src={artworkUrl}
                    alt="Album art"
                    className="h-12 w-12 shrink-0 rounded-md object-cover border border-edge/60"
                  />
                ) : (
                  <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-md border border-edge/60 bg-sunken text-ink-dim">
                    <Music aria-hidden="true" className="h-6 w-6" />
                  </div>
                )}
                <div className="min-w-0 flex-1">
                  <div className="truncate text-sm font-medium text-ink">
                    {media.title || 'Unknown title'}
                  </div>
                  {media.artist && (
                    <div className="truncate text-xs text-ink-dim">
                      {media.artist}
                    </div>
                  )}
                  {media.source && (
                    <div className="mt-0.5 text-micro uppercase tracking-wider text-ink-faint">
                      {media.source}
                    </div>
                  )}
                </div>
              </div>
            )}
            <div className="flex items-center gap-2">
              <button
                type="button"
                aria-label="Previous track"
                title="Previous track"
                disabled={!hasMedia}
                onClick={() => window.switchboard.media('prev').catch(() => {})}
                className="flex h-10 w-10 items-center justify-center rounded-full border border-edge text-ink-dim transition-colors hover:bg-raised hover:text-ink disabled:pointer-events-none disabled:opacity-40"
              >
                <SkipBack aria-hidden="true" className="h-4 w-4" />
              </button>
              <button
                type="button"
                aria-label={isPlaying ? 'Pause' : 'Play'}
                title={isPlaying ? 'Pause' : 'Play'}
                disabled={!hasMedia}
                onClick={() => window.switchboard.media(isPlaying ? 'pause' : 'play').catch(() => {})}
                className="flex h-10 w-10 items-center justify-center rounded-full bg-accent text-rail transition-colors hover:brightness-110 disabled:pointer-events-none disabled:opacity-40"
              >
                {isPlaying ? (
                  <Pause aria-hidden="true" className="h-4 w-4" />
                ) : (
                  <Play aria-hidden="true" className="h-4 w-4" />
                )}
              </button>
              <button
                type="button"
                aria-label="Next track"
                title="Next track"
                disabled={!hasMedia}
                onClick={() => window.switchboard.media('next').catch(() => {})}
                className="flex h-10 w-10 items-center justify-center rounded-full border border-edge text-ink-dim transition-colors hover:bg-raised hover:text-ink disabled:pointer-events-none disabled:opacity-40"
              >
                <SkipForward aria-hidden="true" className="h-4 w-4" />
              </button>
              <button
                type="button"
                aria-label="Stop"
                title="Stop"
                disabled={!hasMedia}
                onClick={() => window.switchboard.media('stop').catch(() => {})}
                className="flex h-10 w-10 items-center justify-center rounded-full border border-edge text-ink-dim transition-colors hover:bg-raised hover:text-ink disabled:pointer-events-none disabled:opacity-40"
              >
                <Square aria-hidden="true" className="h-4 w-4" />
              </button>
            </div>
            <p className="mt-2 text-micro text-ink-faint">
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
