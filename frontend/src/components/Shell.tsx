import { useEffect, useRef, useState, type ReactNode } from 'react'
import { DeviceMobile, Files, GearSix, House, QrCode, type Icon } from '@phosphor-icons/react'
import { cn } from '@/lib/cn'
import type { Status } from '@/lib/bridge'
import { StatusDot } from './ui'

export type Route = '/' | '/pair' | '/devices' | '/files' | '/settings'

const NAV: { to: Route; label: string; icon: Icon }[] = [
  { to: '/', label: 'Home', icon: House },
  { to: '/pair', label: 'Pair a phone', icon: QrCode },
  { to: '/devices', label: 'Phones', icon: DeviceMobile },
  { to: '/files', label: 'Files', icon: Files },
  { to: '/settings', label: 'Settings', icon: GearSix },
]

function Mark({ dev }: { dev: boolean }) {
  return (
    <span className="flex items-center gap-2.5">
      <img src="./favicon.svg" alt="" className="h-7 w-7" />
      <span className="text-title font-semibold tracking-[-0.01em]">Switchboard</span>
      {dev && <span className="rounded-md bg-tertiary-container px-1.5 py-0.5 text-[10px] font-bold uppercase tracking-wider text-on-tertiary-container">Dev</span>}
    </span>
  )
}

/** Floating rail that widens into a drawer on hover, as in Domus. */
function Rail({ route, go, phones }: { route: Route; go: (r: Route) => void; phones: number }) {
  const [open, setOpen] = useState(false)
  const timer = useRef<number>()
  useEffect(() => () => clearTimeout(timer.current), [])
  return (
    <nav
      aria-label="Main"
      onMouseEnter={() => {
        clearTimeout(timer.current)
        setOpen(true)
      }}
      onMouseLeave={() => {
        timer.current = window.setTimeout(() => setOpen(false), 150)
      }}
      onFocus={() => setOpen(true)}
      onBlur={() => setOpen(false)}
      className={cn(
        'no-drag fixed start-4 top-1/2 z-nav flex -translate-y-1/2 flex-col gap-1.5 overflow-hidden rounded-[36px] bg-container p-2 shadow-xl transition-[width] duration-base ease-std',
        open ? 'w-[232px]' : 'w-[72px]',
      )}
    >
      {NAV.map((n) => {
        const active = n.to === route
        return (
          <button
            key={n.to}
            onClick={() => go(n.to)}
            aria-current={active ? 'page' : undefined}
            title={open ? undefined : n.label}
            className={cn(
              'state-layer flex h-14 w-full cursor-pointer items-center gap-4 rounded-full px-[18px] text-body transition-colors duration-base',
              active ? 'bg-primary-container font-semibold text-on-primary-container' : 'text-muted hover:text-fg',
            )}
          >
            <span className="relative flex h-6 w-6 shrink-0 items-center justify-center">
              <n.icon size={24} weight={active ? 'fill' : 'regular'} aria-hidden />
              {n.to === '/devices' && phones > 0 && <span className="absolute -right-1 -top-1 h-2.5 w-2.5 rounded-full bg-on ring-2 ring-container" />}
            </span>
            <span className={cn('whitespace-nowrap transition-opacity duration-fast', open ? 'opacity-100' : 'opacity-0')}>{n.label}</span>
          </button>
        )
      })}
    </nav>
  )
}

export function Shell({ route, go, status, dev, children }: { route: Route; go: (r: Route) => void; status: Status | null; dev: boolean; children: ReactNode }) {
  const online = status?.devices.filter((d) => d.online).length ?? 0
  const main = useRef<HTMLElement>(null)
  useEffect(() => main.current?.scrollTo(0, 0), [route])
  return (
    <div className="flex h-full flex-col">
      {/* Title bar: draggable; the OS draws its buttons over the right edge. */}
      <header className="app-drag flex h-11 shrink-0 items-center gap-4 pe-36 ps-5">
        <Mark dev={dev} />
        {status && (
          <span className="flex items-center gap-2 text-caption text-muted">
            <StatusDot on={online > 0} pulse />
            {online > 0 ? `${online} phone${online > 1 ? 's' : ''} connected` : 'Waiting for a phone'}
          </span>
        )}
      </header>
      <Rail route={route} go={go} phones={online} />
      <main ref={main} className="min-h-0 flex-1 overflow-y-auto">
        <div key={route} className="mx-auto max-w-[1040px] px-8 pb-16 ps-[120px] pt-6">
          {children}
        </div>
      </main>
    </div>
  )
}
