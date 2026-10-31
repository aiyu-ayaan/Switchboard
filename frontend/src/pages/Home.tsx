import { ArrowRight, BatteryCharging, BatteryHigh, Cpu, DeviceMobile, LockKey, Monitor, MusicNotes, Pause, Play, QrCode, SpeakerHigh, SpeakerSlash } from '@phosphor-icons/react'
import type { Route } from '@/components/Shell'
import { Button, Card, Chip, Meter, SectionTitle, ShapeIcon, StatusDot } from '@/components/ui'
import { bytes, timeAgo, useHostState, type Status } from '@/lib/bridge'
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

function Hero({ status, go }: { status: Status; go: (r: Route) => void }) {
  const online = status.devices.filter((d) => d.online)
  const paired = status.devices.length
  return (
    <Card className="rise relative overflow-hidden bg-primary-container p-8 text-on-primary-container">
      <Blob className="-right-16 -top-20 h-80 w-80 text-on-primary-container/10" />
      <Blob className="-bottom-24 right-40 h-48 w-48 rotate-90 text-on-primary-container/5" />
      <div className="relative flex items-end justify-between gap-8">
        <div className="min-w-0">
          <p className="flex items-center gap-2 text-label font-medium opacity-80">
            <DeviceMobile size={18} aria-hidden />
            {online.length ? 'Connected now' : paired ? 'Paired phones' : 'No phone yet'}
          </p>
          {online.length ? (
            <>
              <p className="mt-3 text-display font-semibold tracking-[-0.02em] tabular">{online.length}</p>
              <p className="mt-1 text-title">{online.map((d) => d.name).join(', ')} {online.length > 1 ? 'are' : 'is'} in control</p>
            </>
          ) : paired ? (
            <>
              <p className="mt-3 text-display font-semibold tracking-[-0.02em] tabular">{paired}</p>
              <p className="mt-1 text-title">Open Switchboard on your phone and it connects by itself</p>
            </>
          ) : (
            <>
              <p className="mt-3 text-page font-semibold tracking-[-0.01em]">Pair your phone in seconds</p>
              <p className="mt-2 max-w-md text-body opacity-80">Scan a QR code or type a short code. No account, no password — the link is end-to-end encrypted on your own network.</p>
            </>
          )}
        </div>
        <Button size="lg" variant="filled" icon={paired ? <QrCode size={22} weight="bold" /> : <ArrowRight size={22} weight="bold" />} onClick={() => go('/pair')} aria-label="Pair a phone">
          {paired ? 'Pair another' : 'Show code'}
        </Button>
      </div>
    </Card>
  )
}

function Tile({ i, icon, label, value, sub, children }: { i: number; icon: React.ReactNode; label: string; value: React.ReactNode; sub?: React.ReactNode; children?: React.ReactNode }) {
  return (
    <Card className="rise flex flex-col gap-4 p-5" style={{ ['--i' as string]: i }}>
      <div className="flex items-center gap-3">
        {icon}
        <span className="text-label font-medium text-muted">{label}</span>
      </div>
      <div>
        <p className="truncate text-headline font-semibold tabular">{value}</p>
        {sub && <p className="mt-0.5 truncate text-label text-muted">{sub}</p>}
      </div>
      {children}
    </Card>
  )
}

export function Home({ status, go }: { status: Status | null; go: (r: Route) => void }) {
  const state = useHostState()
  if (!status) return <Starting />
  const ext = state?.displays.filter((d) => d.kind === 'external').length ?? 0
  return (
    <>
      <div className="rise mb-8">
        <p className="text-title text-muted">{greeting()}</p>
        <h1 className="mt-1 text-display font-semibold tracking-[-0.02em]">{status.name}</h1>
        <div className="mt-4 flex flex-wrap gap-2">
          <Chip tone="on" icon={<StatusDot on />}>Ready on port {status.port}</Chip>
          <Chip icon={<LockKey size={16} />}>End-to-end encrypted</Chip>
          <Chip>{status.os}</Chip>
        </div>
      </div>

      <Hero status={status} go={go} />

      <SectionTitle>What your phone controls</SectionTitle>
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Tile
          i={1}
          icon={<ShapeIcon icon={state?.volume?.muted ? <SpeakerSlash size={22} weight="fill" /> : <SpeakerHigh size={22} weight="fill" />} size={44} />}
          label="Volume"
          value={state?.volume ? (state.volume.muted ? 'Muted' : `${state.volume.level}%`) : '—'}
          sub="Default output"
        >
          <Meter value={state?.volume && !state.volume.muted ? state.volume.level : 0} />
        </Tile>
        <Tile
          i={2}
          icon={<ShapeIcon icon={<Monitor size={22} weight="fill" />} shape="cookie6" tone="tertiary" size={44} />}
          label="Displays"
          value={state ? state.displays.length : '—'}
          sub={state ? `${ext} over DDC/CI${state.displays.some((d) => d.kind === 'internal') ? ' · built-in panel' : ''}` : 'Detecting…'}
        />
        <Tile
          i={3}
          icon={<ShapeIcon icon={<MusicNotes size={22} weight="fill" />} shape="flower" tone="secondary" size={44} />}
          label={state?.media?.player ?? 'Media'}
          value={state?.media?.title || 'Nothing playing'}
          sub={state?.media?.artist || (state?.media ? (state.media.playing ? 'Playing' : 'Paused') : 'Play something to control it')}
        />
        <Tile
          i={4}
          icon={<ShapeIcon icon={<Cpu size={22} weight="fill" />} shape="clover" tone="primary" size={44} />}
          label="System"
          value={state ? `${Math.round(state.stats.cpu)}% CPU` : '—'}
          sub={state ? `${bytes(state.stats.mem_used)} of ${bytes(state.stats.mem_total)}` : ''}
        >
          {state && <Meter value={(state.stats.mem_used / state.stats.mem_total) * 100} tone="tertiary" />}
        </Tile>
      </div>

      {state && state.displays.length > 0 && (
        <>
          <SectionTitle>Displays</SectionTitle>
          <Card className="rise divide-y divide-border/60" style={{ ['--i' as string]: 2 }}>
            {state.displays.map((d) => (
              <div key={d.id} className="flex items-center gap-4 px-5 py-4">
                <ShapeIcon icon={<Monitor size={20} />} shape="cookie9" tone={d.kind === 'internal' ? 'secondary' : 'tertiary'} size={40} />
                <div className="w-48 min-w-0">
                  <p className="truncate font-medium">{d.name}</p>
                  <p className="text-caption text-muted">{d.kind === 'internal' ? 'Built-in · backlight' : 'External · DDC/CI'}</p>
                </div>
                <div className="flex-1">
                  <Meter value={d.brightness ?? 0} />
                </div>
                <span className="w-12 text-right text-label font-medium tabular">{d.brightness ?? '—'}%</span>
              </div>
            ))}
          </Card>
        </>
      )}

      {state?.media && (
        <Card className="rise mt-3 flex items-center gap-4 p-5" style={{ ['--i' as string]: 3 }}>
          <ShapeIcon icon={state.media.playing ? <Play size={20} weight="fill" /> : <Pause size={20} weight="fill" />} shape="sunny" tone="tertiary" size={44} spin={state.media.playing} />
          <div className="min-w-0 flex-1">
            <p className="truncate font-medium">{state.media.title || 'Unknown title'}</p>
            <p className="truncate text-label text-muted">{[state.media.artist, state.media.player].filter(Boolean).join(' · ')}</p>
          </div>
          {state.stats.battery && (
            <Chip icon={state.stats.battery.charging ? <BatteryCharging size={16} /> : <BatteryHigh size={16} />}>{state.stats.battery.percent}%</Chip>
          )}
        </Card>
      )}

      {status.devices.length > 0 && (
        <>
          <SectionTitle action={<Button variant="text" size="sm" onClick={() => go('/devices')}>Manage</Button>}>Phones</SectionTitle>
          <div className="grid grid-cols-2 gap-3">
            {status.devices.slice(0, 4).map((d, i) => (
              <Card key={d.id} className="rise flex items-center gap-4 p-4" style={{ ['--i' as string]: i }}>
                <ShapeIcon icon={<DeviceMobile size={20} weight="fill" />} tone={d.online ? 'primary' : 'neutral'} size={40} />
                <div className="min-w-0">
                  <p className="truncate font-medium">{d.name}</p>
                  <p className="flex items-center gap-1.5 text-caption text-muted">
                    <StatusDot on={d.online} /> {d.online ? 'Online now' : `Seen ${timeAgo(d.last_seen)}`}
                  </p>
                </div>
              </Card>
            ))}
          </div>
        </>
      )}
    </>
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
