import {
  AudioLines,
  Check,
  Mic,
  MicOff,
  Music,
  Speaker,
  Pause,
  Play,
  SkipBack,
  SkipForward,
  Square,
  Volume2,
  VolumeX
} from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import type { AudioDevice, AudioSession, LocalState } from '../../shared/types';
import { useThrottledCommit } from '../useHostState';
import { Card, EmptyState, Pane, Sidebar, SidebarItem } from './Shell';
import { LevelSlider } from './LevelSlider';

interface AudioViewProps {
  state: LocalState;
  patch: (fn: (draft: LocalState) => LocalState) => void;
  setPaused: (paused: boolean) => void;
}

export function AudioView({ state, patch, setPaused }: AudioViewProps) {
  const { volume, mic, mixer, outputs, inputs } = state.host;
  const hasAudio = state.host.capabilities.includes('volume');
  const hasMedia = state.host.capabilities.includes('media');
  const hasMixer = state.host.capabilities.includes('mixer');
  const hasOutputs = state.host.capabilities.includes('outputs');
  const hasMic = state.host.capabilities.includes('mic');
  const hasInputs = state.host.capabilities.includes('inputs');
  const activeOutput = outputs.find((o) => o.default);
  const activeInput = inputs?.find((i) => i.default);
  const micState = mic ?? { level: 100, muted: false };

  const sendVolume = useThrottledCommit((level: number) =>
    window.switchboard.setVolume(level, volume.muted)
  );

  const sendMicVolume = useThrottledCommit((level: number) =>
    window.switchboard.setMicVolume(level, micState.muted)
  );

  const applyLocal = (level: number, muted: boolean) =>
    patch((draft) => ({ ...draft, host: { ...draft.host, volume: { level, muted } } }));

  const applyMicLocal = (level: number, muted: boolean) =>
    patch((draft) => ({ ...draft, host: { ...draft.host, mic: { level, muted } } }));

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

  const toggleMicMute = async () => {
    const next = !micState.muted;
    applyMicLocal(micState.level, next);
    await window.switchboard.setMicVolume(micState.level, next).catch(() => {});
  };

  // Moving the default endpoint takes Windows a moment, and the poll would
  // otherwise show the old device still ticked. The list is patched first so
  // the tick lands under the pointer, and the reply reconciles it.
  const selectOutput = async (deviceId: string) => {
    if (deviceId === activeOutput?.id) return;
    patch((draft) => ({
      ...draft,
      host: {
        ...draft.host,
        outputs: draft.host.outputs.map((o) => ({ ...o, default: o.id === deviceId }))
      }
    }));
    await window.switchboard.setAudioOutput(deviceId).catch(() => {});
  };

  const selectInput = async (deviceId: string) => {
    if (deviceId === activeInput?.id) return;
    patch((draft) => ({
      ...draft,
      host: {
        ...draft.host,
        inputs: (draft.host.inputs ?? []).map((i) => ({ ...i, default: i.id === deviceId }))
      }
    }));
    await window.switchboard.setAudioInput(deviceId).catch(() => {});
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
        {hasMic && (
          <SidebarItem
            label="Microphone"
            icon={micState.muted ? MicOff : Mic}
            detail={micState.muted ? 'Muted' : `${micState.level}%`}
            onSelect={() => {}}
          />
        )}
        {hasOutputs && (
          <SidebarItem
            label="Output device"
            icon={Speaker}
            detail={activeOutput?.name ?? 'None'}
            onSelect={() => {}}
          />
        )}
        {Boolean(hasInputs && activeInput) && (
          <SidebarItem
            label="Input device"
            icon={Mic}
            detail={activeInput?.name ?? 'None'}
            onSelect={() => {}}
          />
        )}
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

          {hasMic && (
            <Card
              title="Microphone"
              meta={
                <button
                  type="button"
                  onClick={toggleMicMute}
                  aria-pressed={micState.muted}
                  className={`flex items-center gap-1.5 rounded border px-2 py-0.5 text-micro transition-colors ${
                    micState.muted
                      ? 'border-warn/40 bg-warn/10 text-warn'
                      : 'border-edge text-ink-dim hover:bg-raised hover:text-ink'
                  }`}
                >
                  {micState.muted ? (
                    <MicOff aria-hidden="true" className="h-3 w-3" />
                  ) : (
                    <Mic aria-hidden="true" className="h-3 w-3" />
                  )}
                  {micState.muted ? 'Muted' : 'Mute'}
                </button>
              }
            >
              <LevelSlider
                icon={micState.muted ? MicOff : Mic}
                label="Microphone volume"
                value={micState.level}
                min={0}
                max={100}
                onGestureChange={setPaused}
                onChange={(level) => {
                  applyMicLocal(level, micState.muted);
                  sendMicVolume(level);
                }}
                onCommit={(level) =>
                  window.switchboard.setMicVolume(level, micState.muted).catch(() => {})
                }
              />
            </Card>
          )}

          {hasOutputs && (
            <Card title="Output device">
              {outputs.length === 0 ? (
                <EmptyState
                  icon={Speaker}
                  title="No playback devices"
                  hint="Windows reports no active output endpoints. Plug in or enable a device and it appears on the next poll."
                />
              ) : (
                <div className="grid gap-1">
                  {outputs.map((device) => (
                    <OutputRow key={device.id} device={device} onSelect={selectOutput} />
                  ))}
                </div>
              )}
              <p className="mt-2 text-micro text-ink-faint">
                Switching the output moves playback, communications and multimedia together,
                so the whole host follows the choice.
              </p>
            </Card>
          )}

          {Boolean(hasInputs && inputs && inputs.length > 0) && (
            <Card title="Input Device">
              <div className="grid gap-1">
                {inputs!.map((device) => (
                  <InputRow key={device.id} device={device} onSelect={selectInput} />
                ))}
              </div>
              <p className="mt-2 text-micro text-ink-faint">
                Switching the recording endpoint changes the active microphone across the system.
              </p>
            </Card>
          )}

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

function OutputRow({
  device,
  onSelect
}: {
  device: AudioDevice;
  onSelect: (deviceId: string) => void;
}) {
  return (
    <button
      type="button"
      role="radio"
      aria-checked={device.default}
      onClick={() => onSelect(device.id)}
      className={`flex items-center gap-2 rounded border px-2.5 py-2 text-left text-xs transition-colors ${
        device.default
          ? 'border-accent/50 bg-accent/10 text-ink'
          : 'border-edge text-ink-dim hover:bg-raised hover:text-ink'
      }`}
    >
      <Speaker aria-hidden="true" className="h-3.5 w-3.5 shrink-0" />
      <span className="min-w-0 flex-1 truncate">{device.name}</span>
      {device.default && <Check aria-hidden="true" className="h-3.5 w-3.5 shrink-0 text-accent" />}
    </button>
  );
}

function InputRow({
  device,
  onSelect
}: {
  device: AudioDevice;
  onSelect: (deviceId: string) => void;
}) {
  return (
    <button
      type="button"
      role="radio"
      aria-checked={device.default}
      onClick={() => onSelect(device.id)}
      className={`flex items-center gap-2 rounded border px-2.5 py-2 text-left text-xs transition-colors ${
        device.default
          ? 'border-accent/50 bg-accent/10 text-ink'
          : 'border-edge text-ink-dim hover:bg-raised hover:text-ink'
      }`}
    >
      <Mic aria-hidden="true" className="h-3.5 w-3.5 shrink-0" />
      <span className="min-w-0 flex-1 truncate">{device.name}</span>
      {device.default && <Check aria-hidden="true" className="h-3.5 w-3.5 shrink-0 text-accent" />}
    </button>
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
