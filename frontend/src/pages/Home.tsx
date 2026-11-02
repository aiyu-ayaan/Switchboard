import { ArrowRight, BatteryCharging, Repeat, RepeatOnce, Shuffle, FolderOpen, PaperPlaneTilt, BatteryHigh, CircleHalf, Clock, Cpu, DeviceMobile, Fingerprint, Headphones, LockKey, Monitor, MusicNotes, Pause, Play, QrCode, SkipBack, SkipForward, SpeakerHigh, SpeakerSlash, Sun } from '@phosphor-icons/react'
import type { Route } from '@/components/Shell'
import { Button, Ring, ShapeIcon, Slider, StatusDot, useToast } from '@/components/ui'
import { bytes, clock, run, sb, timeAgo, useCover, useFileEvents, useHostState, type Control, type Status } from '@/lib/bridge'
import { cn } from '@/lib/cn'

function greeting() {
  const h = new Date().getHours()
  return h < 5 ? 'Good night' : h < 12 ? 'Good morning' : h < 18 ? 'Good afternoon' : 'Good evening'
}

/** Decorative blob in the hero card corner, the same motif as Android's home card. */
function Blob({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 200 200" className={cn('pointer-events-none absolute', className)} aria-hidden>
      <path
        className="fill-current"
        d="M44.7,-58.6C57.3,-50.5,66.5,-36.6,71.2,-21.2C75.9,-5.8,76.1,11.1,69.6,24.8C63.1,38.6,49.9,49.2,35.4,57.3C20.9,65.3,5.1,70.8,-11.3,70.5C-27.7,70.1,-44.7,63.9,-55.4,52.1C-66.1,40.2,-70.5,22.8,-71.4,5.5C-72.4,-11.8,-69.9,-28.9,-60.7,-41.3C-51.5,-53.7,-35.6,-61.3,-20.3,-67.7C-5,-74.2,9.7,-79.5,23.4,-76.5C37.2,-73.6,50,-62.4,44.7,-58.6Z"
        transform="translate(100 100)"
      />
    </svg>
  )
}

function uptime(secs: number) {
  const d = Math.floor(secs / 86400)
  const h = Math.floor((secs % 86400) / 3600)
  const m = Math.floor((secs % 3600) / 60)
  return d ? `${d}d ${h}h` : h ? `${h}h ${m}m` : `${m}m`
}

const tile = 'rise relative overflow-hidden rounded-3xl bg-panel p-5'
const delay = (i: number) => ({ ['--i' as string]: i })

export function Home({ status, go }: { status: Status | null; go: (r: Route) => void }) {
  const { state, refresh } = useHostState()
  const toast = useToast()
  const files = useFileEvents()
  const cover = useCover(state?.media?.art)
  const act = (c: Control) =>
    run(c).then(
      () => setTimeout(refresh, 250),
      (e: Error) => toast(e.message),
    )
  if (!status) return <Starting />
  const online = status.devices.filter((d) => d.online)
  const media = state?.media
  const vol = state?.volume
  const mem = state ? (state.stats.mem_used / state.stats.mem_total) * 100 : 0
  const bat = state?.stats.battery

  return (
    <div className="grid grid-cols-12 gap-3">
      {/* Identity + pairing: the big tile. */}
      <section className={cn(tile, 'col-span-12 flex min-h-[260px] flex-col justify-between bg-primary-container p-7 text-on-primary-container lg:col-span-8')} style={delay(0)}>
        <Blob className="-right-16 -top-20 h-80 w-80 text-on-primary-container/10" />
        <Blob className="-bottom-24 right-48 h-44 w-44 rotate-90 text-on-primary-container/5" />
        <div className="relative">
          <p className="text-title opacity-80">{greeting()}</p>
          <h1 className="mt-1 truncate text-display font-semibold tracking-[-0.02em]">{status.name}</h1>
          <div className="mt-3 flex flex-wrap gap-2">
            <span className="inline-flex h-8 items-center gap-2 rounded-lg bg-on-primary-container/10 px-3 text-label font-medium">
              <StatusDot on pulse /> Ready on port {status.port}
            </span>
            <span className="inline-flex h-8 items-center gap-2 rounded-lg bg-on-primary-container/10 px-3 text-label font-medium">
              <LockKey size={16} /> End-to-end encrypted
            </span>
          </div>
        </div>
        <div className="relative mt-6 flex items-end justify-between gap-6">
          <p className="max-w-md text-body opacity-80">
            {online.length
              ? `${online.map((d) => d.name).join(', ')} ${online.length > 1 ? 'are' : 'is'} connected and in control.`
              : status.devices.length
                ? 'Open Switchboard on your phone and it connects by itself.'
                : 'Pair your phone with a QR code or a short code. No account, no password.'}
          </p>
          <Button size="lg" icon={status.devices.length ? <QrCode size={22} weight="bold" /> : <ArrowRight size={22} weight="bold" />} onClick={() => go('/pair')}>
            {status.devices.length ? 'Pair another' : 'Pair a phone'}
          </Button>
        </div>
      </section>

      {/* Now playing. */}
      <section className={cn(tile, 'col-span-12 flex flex-col justify-between gap-4 bg-tertiary-container text-on-tertiary-container md:col-span-6 lg:col-span-4')} style={delay(1)}>
        <div className="flex items-center justify-between">
          <span className="text-label font-medium opacity-80">{media?.player ?? 'Media'}</span>
          {media && <span className="rounded-full bg-on-tertiary-container/10 px-2.5 py-1 text-caption font-medium">{media.playing ? 'Playing' : 'Paused'}</span>}
        </div>
        <div className="flex justify-center">
          {cover ? (
            <img src={cover} alt="" className="h-[104px] w-[104px] rounded-2xl object-cover" />
          ) : (
            <ShapeIcon icon={<MusicNotes size={40} weight="fill" />} shape="sunny" tone="primary" size={104} spin={media?.playing} />
          )}
        </div>
        <div className="min-w-0 text-center">
          <p className="truncate text-title font-semibold">{media?.title || 'Nothing playing'}</p>
          <p className="truncate text-label opacity-80">{media?.artist || (media ? ' ' : 'Start a player to control it')}</p>
        </div>
        <div className="flex items-center justify-center gap-2">
          <Button variant="tonal" aria-label="Previous" disabled={!media} icon={<SkipBack size={20} weight="fill" />} onClick={() => act({ type: 'media', action: 'previous' })} />
          <Button size="lg" className="w-20" aria-label={media?.playing ? 'Pause' : 'Play'} disabled={!media} icon={media?.playing ? <Pause size={26} weight="fill" /> : <Play size={26} weight="fill" />} onClick={() => act({ type: 'media', action: 'play_pause' })} />
          <Button variant="tonal" aria-label="Next" disabled={!media} icon={<SkipForward size={20} weight="fill" />} onClick={() => act({ type: 'media', action: 'next' })} />
        </div>
        {media?.length ? (
          <div className="flex flex-col gap-1">
            <Slider
              label="Track position"
              disabled={!media.can_seek}
              value={media.position == null ? null : Math.min(100, (media.position / media.length) * 100)}
              onCommit={(pct) => act({ type: 'seek', position: Math.round((pct / 100) * media.length!) })}
            />
            <div className="flex justify-between text-caption tabular opacity-80">
              <span>{clock(media.position ?? 0)}</span>
              <span>{clock(media.length)}</span>
            </div>
          </div>
        ) : null}
        {media && (media.shuffle != null || media.repeat != null) && (
          <div className="flex items-center justify-center gap-2">
            <Button
              variant={media.shuffle ? 'filled' : 'tonal'}
              size="sm"
              aria-label="Shuffle"
              aria-pressed={!!media.shuffle}
              disabled={media.shuffle == null}
              icon={<Shuffle size={18} />}
              onClick={() => act({ type: 'set_shuffle', on: !media.shuffle })}
            />
            <Button
              variant={media.repeat && media.repeat !== 'off' ? 'filled' : 'tonal'}
              size="sm"
              aria-label={`Repeat ${media.repeat ?? 'off'}`}
              disabled={media.repeat == null}
              icon={media.repeat === 'track' ? <RepeatOnce size={18} /> : <Repeat size={18} />}
              onClick={() => act({ type: 'set_repeat', mode: media.repeat === 'off' ? 'all' : media.repeat === 'all' ? 'track' : 'off' })}
            />
          </div>
        )}
        {(state?.players.length ?? 0) > 1 && (
          <div role="radiogroup" aria-label="Player" className="flex flex-wrap justify-center gap-1.5">
            {state!.players.map((p) => (
              <button
                key={p.id}
                role="radio"
                aria-checked={p.id === media?.id}
                onClick={() => act({ type: 'select_player', player: p.id })}
                className={cn('state-layer h-8 cursor-pointer rounded-full px-3 text-caption font-medium', p.id === media?.id ? 'bg-on-tertiary-container/20' : 'bg-on-tertiary-container/10 opacity-80')}
              >
                {p.name}
              </button>
            ))}
          </div>
        )}
      </section>

      {/* Volume. */}
      <section className={cn(tile, 'col-span-12 flex flex-col justify-between gap-4 md:col-span-6 lg:col-span-4')} style={delay(2)}>
        <div className="flex items-start justify-between">
          <div>
            <p className="text-label font-medium text-muted">Volume</p>
            <p className="mt-1 text-page font-semibold tabular">{vol ? (vol.muted ? 'Muted' : `${vol.level}%`) : '—'}</p>
          </div>
          <Button
            variant={vol?.muted ? 'filled' : 'tonal'}
            aria-label={vol?.muted ? 'Unmute' : 'Mute'}
            aria-pressed={!!vol?.muted}
            disabled={!vol}
            icon={vol?.muted ? <SpeakerSlash size={20} weight="fill" /> : <SpeakerHigh size={20} weight="fill" />}
            onClick={() => vol && act({ type: 'set_mute', muted: !vol.muted })}
          />
        </div>
        <Slider label="Volume" value={vol?.level ?? null} onCommit={(level) => act({ type: 'set_volume', level })} />
      </section>

      {/* Mixer: where sound goes and how loud each program is. */}
      <section className={cn(tile, 'col-span-12 flex flex-col gap-4 md:col-span-6 lg:col-span-8')} style={delay(2)}>
        <div className="flex items-center gap-3">
          <ShapeIcon icon={<Headphones size={22} weight="fill" />} shape="cookie6" tone="secondary" size={44} />
          <p className="text-title font-medium">Sound</p>
        </div>
        {(state?.outputs.length ?? 0) > 1 && (
          <div role="radiogroup" aria-label="Output device" className="flex flex-wrap gap-2">
            {state!.outputs.map((o) => (
              <button
                key={o.id}
                role="radio"
                aria-checked={o.default}
                onClick={() => !o.default && act({ type: 'set_output', output: o.id })}
                className={cn(
                  'state-layer h-9 cursor-pointer rounded-full px-4 text-label font-medium',
                  o.default ? 'bg-secondary-container text-on-secondary-container' : 'bg-container text-muted hover:text-fg',
                )}
              >
                {o.name}
              </button>
            ))}
          </div>
        )}
        {(state?.apps.length ?? 0) === 0 ? (
          <p className="text-body text-muted">Nothing is playing sound right now.</p>
        ) : (
          <div className="flex flex-col gap-3">
            {state!.apps.map((a) => (
              <div key={a.id} className="flex items-center gap-3">
                <div className="w-36 min-w-0">
                  <p className="truncate text-label font-medium">{a.name}</p>
                  {a.detail && <p className="truncate text-caption text-muted">{a.detail}</p>}
                </div>
                <div className="flex-1">
                  <Slider label={`${a.name} volume`} value={a.level} onCommit={(level) => act({ type: 'set_app_volume', app: a.id, level })} />
                </div>
                <Button
                  variant={a.muted ? 'filled' : 'tonal'}
                  size="sm"
                  aria-label={a.muted ? `Unmute ${a.name}` : `Mute ${a.name}`}
                  aria-pressed={a.muted}
                  icon={a.muted ? <SpeakerSlash size={16} weight="fill" /> : <SpeakerHigh size={16} weight="fill" />}
                  onClick={() => act({ type: 'set_app_mute', app: a.id, muted: !a.muted })}
                />
              </div>
            ))}
          </div>
        )}
      </section>

      {/* Files: send to a phone, and what arrived. */}
      <section className={cn(tile, 'col-span-12 flex flex-col gap-4 md:col-span-6 lg:col-span-4')} style={delay(2)}>
        <div className="flex items-center gap-3">
          <ShapeIcon icon={<PaperPlaneTilt size={22} weight="fill" />} shape="clover" tone="tertiary" size={44} />
          <div className="min-w-0 flex-1">
            <p className="text-title font-medium">Files</p>
            <p className="text-caption text-muted">Saved to Downloads/Switchboard</p>
          </div>
          <Button variant="tonal" size="sm" aria-label="Open the folder" icon={<FolderOpen size={18} />} onClick={() => sb.openInbox().catch((e: Error) => toast(e.message))} />
        </div>
        {online.length === 0 ? (
          <p className="text-body text-muted">Connect a phone to send it files.</p>
        ) : (
          <div className="flex flex-wrap gap-2">
            {online.map((d) => (
              <Button
                key={d.id}
                variant="tonal"
                size="sm"
                icon={<PaperPlaneTilt size={16} />}
                onClick={() =>
                  sb.sendFile(d.id).then(
                    (name) => name && toast(`Asked ${d.name} to take ${name}`),
                    (e: Error) => toast(e.message),
                  )
                }
              >
                Send to {d.name}
              </Button>
            ))}
          </div>
        )}
        {files.length > 0 && (
          <ul className="flex flex-col gap-1.5" aria-label="Recent files">
            {files.slice(0, 4).map((f, i) => (
              <li key={i} className="flex items-center justify-between gap-3 text-label">
                <span className="min-w-0 truncate font-medium">{f.name}</span>
                <span className="shrink-0 text-caption text-muted">
                  {f.kind === 'received' ? 'from' : f.kind === 'sent' ? 'to' : 'declined by'} {f.device}
                </span>
              </li>
            ))}
          </ul>
        )}
      </section>

      {/* System rings. */}
      <section className={cn(tile, 'col-span-6 flex flex-col items-center justify-center gap-2 md:col-span-3 lg:col-span-2')} style={delay(3)}>
        <Ring value={state?.stats.cpu ?? 0} label="CPU">
          <p className="text-headline font-semibold tabular">{state ? Math.round(state.stats.cpu) : '—'}</p>
          <p className="-mt-1 text-caption text-muted">%</p>
        </Ring>
        <p className="flex items-center gap-1.5 text-label font-medium text-muted">
          <Cpu size={16} /> CPU
        </p>
      </section>
      <section className={cn(tile, 'col-span-6 flex flex-col items-center justify-center gap-2 md:col-span-3 lg:col-span-2')} style={delay(4)}>
        <Ring value={mem} tone="tertiary" label="Memory">
          <p className="text-headline font-semibold tabular">{state ? Math.round(mem) : '—'}</p>
          <p className="-mt-1 text-caption text-muted">%</p>
        </Ring>
        <p className="text-label font-medium text-muted">{state ? `${bytes(state.stats.mem_used)} RAM` : 'Memory'}</p>
      </section>
      <section className={cn(tile, 'col-span-6 flex flex-col justify-between gap-3 md:col-span-3 lg:col-span-2')} style={delay(5)}>
        <ShapeIcon icon={bat?.charging ? <BatteryCharging size={22} weight="fill" /> : <BatteryHigh size={22} weight="fill" />} shape="clover" tone={bat ? 'on' : 'neutral'} size={44} />
        <div>
          <p className="text-page font-semibold tabular">{bat ? `${bat.percent}%` : '—'}</p>
          <p className="text-label text-muted">{bat ? (bat.charging ? 'Charging' : 'On battery') : 'No battery'}</p>
        </div>
      </section>
      <section className={cn(tile, 'col-span-6 flex flex-col justify-between gap-3 md:col-span-3 lg:col-span-2')} style={delay(6)}>
        <ShapeIcon icon={<Clock size={22} weight="fill" />} shape="flower" tone="secondary" size={44} />
        <div>
          <p className="text-page font-semibold tabular">{state ? uptime(state.stats.uptime) : '—'}</p>
          <p className="text-label text-muted">Uptime</p>
        </div>
      </section>

      {/* One tile per display. */}
      {(state?.displays ?? []).map((d, i) => (
        <section key={d.id} className={cn(tile, 'col-span-12 flex flex-col gap-4 md:col-span-6 lg:col-span-4')} style={delay(7 + i)}>
          <div className="flex items-center gap-3">
            <ShapeIcon icon={<Monitor size={22} weight="fill" />} shape={d.kind === 'internal' ? 'cookie9' : 'cookie6'} tone={d.kind === 'internal' ? 'secondary' : 'tertiary'} size={44} />
            <div className="min-w-0 flex-1">
              <p className="truncate text-title font-medium">{d.name}</p>
              <p className="text-caption text-muted">{d.kind === 'internal' ? 'Built-in · backlight' : 'External · DDC/CI'}</p>
            </div>
            <span className="text-page font-semibold tabular">{d.brightness ?? '—'}</span>
          </div>
          <Slider label={`${d.name} brightness`} icon={<Sun size={20} />} value={d.brightness} onCommit={(level) => act({ type: 'set_brightness', display: d.id, level })} />
          {d.kind === 'external' && (
            <Slider label={`${d.name} contrast`} icon={<CircleHalf size={20} />} value={d.contrast} onCommit={(level) => act({ type: 'set_contrast', display: d.id, level })} />
          )}
        </section>
      ))}
      {state && state.displays.length === 0 && (
        <section className={cn(tile, 'col-span-12 text-body text-muted')}>No controllable display found. External monitors need DDC/CI turned on in their own menu.</section>
      )}
      {!state && <section className={cn(tile, 'col-span-12 h-32 animate-pulse')} />}

      {/* Phones + security. */}
      <section className={cn(tile, 'col-span-12 md:col-span-7')} style={delay(10)}>
        <div className="mb-3 flex items-center justify-between">
          <p className="text-label font-medium text-muted">Phones</p>
          <Button variant="text" size="sm" onClick={() => go('/devices')}>
            Manage
          </Button>
        </div>
        {status.devices.length === 0 ? (
          <p className="text-body text-muted">No phone paired yet.</p>
        ) : (
          <div className="flex flex-col gap-2">
            {status.devices.slice(0, 3).map((d) => (
              <div key={d.id} className="flex items-center gap-3 rounded-2xl bg-container p-3">
                <ShapeIcon icon={<DeviceMobile size={18} weight="fill" />} tone={d.online ? 'primary' : 'neutral'} size={36} />
                <p className="min-w-0 flex-1 truncate font-medium">{d.name}</p>
                <span className="flex items-center gap-1.5 text-caption text-muted">
                  <StatusDot on={d.online} /> {d.online ? 'Online' : timeAgo(d.last_seen)}
                </span>
              </div>
            ))}
          </div>
        )}
      </section>
      <section className={cn(tile, 'col-span-12 flex flex-col justify-between gap-4 bg-secondary-container text-on-secondary-container md:col-span-5')} style={delay(11)}>
        <div className="flex items-center gap-3">
          <ShapeIcon icon={<Fingerprint size={22} weight="fill" />} shape="cookie9" tone="primary" size={44} />
          <div>
            <p className="text-label font-medium opacity-80">Fingerprint</p>
            <p className="font-mono text-title font-semibold tracking-wider">{status.fingerprint}</p>
          </div>
        </div>
        <p className="text-label opacity-80">
          {status.os} · {status.addresses[0] ?? 'offline'} · X25519 + ChaCha20-Poly1305
        </p>
      </section>
    </div>
  )
}

export function Starting() {
  return (
    <div className="grid h-[60vh] place-items-center">
      <div className="flex flex-col items-center gap-4 text-muted">
        <ShapeIcon icon={null} shape="sunny" size={56} spin />
        Starting Switchboard…
      </div>
    </div>
  )
}
