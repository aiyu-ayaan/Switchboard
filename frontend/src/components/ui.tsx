// Switchboard UI kit: Material 3 roles on Tailwind, with the expressive shapes
// (cookie, sunny) and wavy progress the Android app uses, so both read as one
// product.
import { createContext, useCallback, useContext, useEffect, useId, useMemo, useRef, useState, type ButtonHTMLAttributes, type CSSProperties, type ReactNode } from 'react'
import { CircleNotch } from '@phosphor-icons/react'
import { cn } from '@/lib/cn'

// --- Shapes -------------------------------------------------------------------

/** Closed path for a rounded n-lobed star in a 100x100 box (M3 Cookie/Sunny family). */
export function lobedPath(lobes: number, depth: number, rotate = 0) {
  const pts: string[] = []
  const steps = 240
  for (let i = 0; i <= steps; i++) {
    const t = (i / steps) * Math.PI * 2
    const r = 50 * (1 - depth + depth * (0.5 + 0.5 * Math.cos(lobes * (t + rotate))))
    pts.push(`${(50 + r * Math.cos(t)).toFixed(2)},${(50 + r * Math.sin(t)).toFixed(2)}`)
  }
  return `M${pts.join('L')}Z`
}

export const SHAPES = {
  cookie9: lobedPath(9, 0.12),
  cookie6: lobedPath(6, 0.14),
  sunny: lobedPath(8, 0.1),
  flower: lobedPath(5, 0.22),
  clover: lobedPath(4, 0.26, Math.PI / 4),
}

type Tone = 'primary' | 'secondary' | 'tertiary' | 'danger' | 'on' | 'neutral'

const toneFill: Record<Tone, string> = {
  primary: 'fill-primary-container text-on-primary-container',
  secondary: 'fill-secondary-container text-on-secondary-container',
  tertiary: 'fill-tertiary-container text-on-tertiary-container',
  danger: 'fill-danger-container text-on-danger-container',
  on: 'fill-on/20 text-on',
  neutral: 'fill-highest text-fg',
}

/** Icon on an expressive shape, like Android's ShapeIcon. */
export function ShapeIcon({
  icon,
  shape = 'cookie9',
  tone = 'primary',
  size = 48,
  className,
  spin,
}: {
  icon: ReactNode
  shape?: keyof typeof SHAPES
  tone?: Tone
  size?: number
  className?: string
  spin?: boolean
}) {
  return (
    <span className={cn('relative inline-grid shrink-0 place-items-center', toneFill[tone], className)} style={{ width: size, height: size }} aria-hidden>
      <svg viewBox="0 0 100 100" className={cn('absolute inset-0 h-full w-full', spin && 'spin-slow')}>
        <path d={SHAPES[shape]} />
      </svg>
      <span className="relative">{icon}</span>
    </span>
  )
}

// --- Wavy progress ----------------------------------------------------------------

/** M3 Expressive linear wavy indicator: a moving wave for done, a flat track for left. */
export function WavyProgress({ value, className, label }: { value: number; className?: string; label: string }) {
  const pct = Math.max(0, Math.min(1, value)) * 100
  const wave = useMemo(() => {
    let d = 'M0 6'
    for (let x = 0; x < 2400; x += 24) d += ' q6 -5 12 0 t12 0'
    return d
  }, [])
  return (
    <div className={cn('flex h-3 w-full items-center gap-1.5', className)} role="progressbar" aria-label={label} aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(pct)}>
      <div className="h-3 overflow-hidden transition-[width] duration-slow ease-std" style={{ width: `${pct}%` }}>
        <svg className="wave h-3 w-[2400px] overflow-visible" aria-hidden>
          <path className="stroke-primary" d={wave} fill="none" strokeWidth="4" strokeLinecap="round" />
        </svg>
      </div>
      <div className="relative h-1 flex-1 rounded-full bg-secondary-container">
        <span className="absolute right-0 top-0 h-1 w-1 rounded-full bg-primary" />
      </div>
    </div>
  )
}

/** Plain thick track for readings (brightness, volume) on the dashboard. */
export function Meter({ value, tone = 'primary' }: { value: number; tone?: 'primary' | 'tertiary' }) {
  return (
    <div className="flex h-2.5 w-full items-center gap-1">
      <div className={cn('h-full rounded-full transition-[width] duration-slow ease-std', tone === 'primary' ? 'bg-primary' : 'bg-tertiary')} style={{ width: `${value}%` }} />
      <div className={cn('h-full flex-1 rounded-full', tone === 'primary' ? 'bg-secondary-container' : 'bg-tertiary-container')} />
    </div>
  )
}

// --- Buttons ------------------------------------------------------------------------

type Variant = 'filled' | 'tonal' | 'outlined' | 'text' | 'danger' | 'tertiary'
const variants: Record<Variant, string> = {
  filled: 'bg-primary text-on-primary shadow-sm',
  tonal: 'bg-secondary-container text-on-secondary-container',
  tertiary: 'bg-tertiary-container text-on-tertiary-container',
  outlined: 'border border-border-strong text-primary',
  text: 'text-primary',
  danger: 'bg-danger-container text-on-danger-container',
}

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant
  size?: 'sm' | 'md' | 'lg'
  icon?: ReactNode
  loading?: boolean
}

export function Button({ variant = 'filled', size = 'md', icon, loading, children, className, disabled, type = 'button', ...rest }: ButtonProps) {
  return (
    <button
      type={type}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      className={cn(
        // Expressive press: the pill squares up a little while held.
        'state-layer no-drag inline-flex shrink-0 cursor-pointer select-none items-center justify-center gap-2 rounded-full font-medium transition-[border-radius,transform,background-color] duration-fast ease-std active:rounded-lg disabled:cursor-not-allowed disabled:opacity-40',
        variants[variant],
        size === 'sm' && 'h-9 text-label',
        size === 'md' && 'h-10 text-body',
        size === 'lg' && 'h-14 text-title',
        // Icon-only buttons are square; padding would squeeze the icon to nothing.
        children ? { sm: 'px-4', md: 'px-5', lg: 'px-7' }[size] : { sm: 'w-9', md: 'w-10', lg: 'w-14' }[size],
        className,
      )}
      {...rest}
    >
      {loading ? <CircleNotch className="animate-spin" size={18} aria-hidden /> : icon}
      {children}
    </button>
  )
}

// --- Containers -------------------------------------------------------------------

export function Card({ children, className, style, tone = 'panel' }: { children?: ReactNode; className?: string; style?: CSSProperties; tone?: 'panel' | 'container' | 'high' }) {
  return (
    <section
      style={style}
      className={cn('rounded-2xl', tone === 'panel' ? 'bg-panel' : tone === 'container' ? 'bg-container' : 'bg-raised', className)}
    >
      {children}
    </section>
  )
}

export function SectionTitle({ children, action }: { children: ReactNode; action?: ReactNode }) {
  return (
    <div className="mb-3 mt-10 flex items-center justify-between px-1 first:mt-0">
      <h2 className="text-title font-semibold">{children}</h2>
      {action}
    </div>
  )
}

export function StatusDot({ on, pulse }: { on: boolean; pulse?: boolean }) {
  return (
    <span className="relative inline-flex h-2.5 w-2.5 shrink-0" aria-hidden>
      {on && pulse && <span className="pulse-ring absolute inset-0 rounded-full bg-on" />}
      <span className={cn('relative h-2.5 w-2.5 rounded-full', on ? 'bg-on' : 'bg-subtle')} />
    </span>
  )
}

export function Chip({ children, icon, tone = 'neutral' }: { children: ReactNode; icon?: ReactNode; tone?: 'neutral' | 'on' | 'primary' | 'warn' }) {
  return (
    <span
      className={cn(
        'inline-flex h-8 items-center gap-2 whitespace-nowrap rounded-lg border px-3 text-label font-medium',
        tone === 'neutral' && 'border-border text-muted',
        tone === 'on' && 'border-on/30 bg-on/10 text-on',
        tone === 'primary' && 'border-transparent bg-primary-container text-on-primary-container',
        tone === 'warn' && 'border-warn/30 bg-warn/10 text-warn',
      )}
    >
      {icon}
      {children}
    </span>
  )
}

// --- Inputs ----------------------------------------------------------------------

export function Switch({ checked, onChange, label, disabled }: { checked: boolean; onChange: (v: boolean) => void; label: string; disabled?: boolean }) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      onClick={() => onChange(!checked)}
      className={cn(
        'no-drag group relative inline-flex h-8 w-[52px] shrink-0 cursor-pointer items-center rounded-full border-2 transition-colors duration-base ease-std disabled:cursor-not-allowed disabled:opacity-40',
        checked ? 'border-primary bg-primary' : 'border-border-strong bg-highest',
      )}
    >
      <span
        className={cn(
          'absolute grid place-items-center rounded-full transition-all duration-base ease-spring group-active:h-7 group-active:w-7',
          checked ? 'left-[22px] h-6 w-6 bg-on-primary text-primary' : 'left-[6px] h-4 w-4 bg-border-strong',
        )}
      >
        {checked && (
          <svg viewBox="0 0 16 16" className="h-3.5 w-3.5" aria-hidden>
            <path d="M3.5 8.5l3 3 6-7" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        )}
      </span>
    </button>
  )
}

/** M3 connected button group, single select. */
export function Segmented<T extends string>({ value, options, onChange, label }: { value: T; options: { value: T; label: string; icon?: ReactNode }[]; onChange: (v: T) => void; label: string }) {
  return (
    <div role="radiogroup" aria-label={label} className="no-drag inline-flex gap-0.5">
      {options.map((o, i) => {
        const active = o.value === value
        return (
          <button
            key={o.value}
            type="button"
            role="radio"
            aria-checked={active}
            onClick={() => onChange(o.value)}
            className={cn(
              'state-layer inline-flex h-10 cursor-pointer items-center gap-2 px-4 text-label font-medium transition-[border-radius,background-color] duration-base ease-spring',
              active ? 'rounded-full bg-primary text-on-primary' : 'bg-secondary-container text-on-secondary-container',
              !active && i === 0 && 'rounded-l-full rounded-r-md',
              !active && i === options.length - 1 && 'rounded-l-md rounded-r-full',
              !active && i > 0 && i < options.length - 1 && 'rounded-md',
            )}
          >
            {o.icon}
            {o.label}
          </button>
        )
      })}
    </div>
  )
}

export function TextField({ label, value, onChange, onEnter, maxLength }: { label: string; value: string; onChange: (v: string) => void; onEnter?: () => void; maxLength?: number }) {
  const id = useId()
  return (
    <div className="relative">
      <input
        id={id}
        value={value}
        maxLength={maxLength}
        onChange={(e) => onChange(e.target.value)}
        onKeyDown={(e) => e.key === 'Enter' && onEnter?.()}
        placeholder=" "
        className="peer h-14 w-full rounded-t-sm border-b-2 border-border-strong bg-highest px-4 pt-5 text-title text-fg outline-none transition-colors focus:border-primary"
      />
      <label htmlFor={id} className="pointer-events-none absolute left-4 top-2 text-caption text-muted transition-all peer-placeholder-shown:top-4 peer-placeholder-shown:text-title peer-focus:top-2 peer-focus:text-caption peer-focus:text-primary">
        {label}
      </label>
    </div>
  )
}

// --- Dialog -----------------------------------------------------------------------

export function Dialog({ open, onClose, icon, title, children, actions }: { open: boolean; onClose: () => void; icon?: ReactNode; title: string; children: ReactNode; actions: ReactNode }) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!open) return
    const prev = document.activeElement as HTMLElement | null
    ref.current?.querySelector<HTMLElement>('button')?.focus()
    const esc = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', esc)
    return () => {
      window.removeEventListener('keydown', esc)
      prev?.focus()
    }
  }, [open, onClose])
  if (!open) return null
  return (
    <div className="fixed inset-0 z-sheet grid place-items-center bg-black/50 p-6" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div ref={ref} role="dialog" aria-modal aria-label={title} className="pop w-full max-w-sm rounded-3xl bg-container p-6 shadow-2xl">
        {icon && <div className="mb-4 flex justify-center">{icon}</div>}
        <h2 className={cn('text-headline font-medium', icon && 'text-center')}>{title}</h2>
        <div className="mt-3 text-body text-muted">{children}</div>
        <div className="mt-6 flex justify-end gap-2">{actions}</div>
      </div>
    </div>
  )
}

// --- Snackbar ----------------------------------------------------------------------

type Toast = { id: number; text: string }
const ToastCtx = createContext<(text: string) => void>(() => {})
export const useToast = () => useContext(ToastCtx)

export function Toaster({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([])
  const push = useCallback((text: string) => {
    const id = Date.now() + Math.random()
    setToasts((t) => [...t.slice(-2), { id, text }])
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), 4000)
  }, [])
  return (
    <ToastCtx.Provider value={push}>
      {children}
      <div className="pointer-events-none fixed inset-x-0 bottom-6 z-toast flex flex-col items-center gap-2" aria-live="polite">
        {toasts.map((t) => (
          <div key={t.id} className="rise rounded-sm bg-inverse px-4 py-3 text-body text-on-inverse shadow-lg">
            {t.text}
          </div>
        ))}
      </div>
    </ToastCtx.Provider>
  )
}

// --- Slider ------------------------------------------------------------------------

/**
 * M3 slider for a live hardware value. Sends at most every 150 ms while dragged
 * and once more on release; ignores polled values for 2 s after a touch so a
 * stale reading cannot yank the handle back.
 */
export function Slider({ value, onCommit, label, disabled, icon }: { value: number | null; onCommit: (v: number) => void; label: string; disabled?: boolean; icon?: ReactNode }) {
  const [local, setLocal] = useState(value ?? 0)
  const touched = useRef(0)
  const last = useRef(0)
  const timer = useRef<number>()
  useEffect(() => {
    if (value != null && Date.now() - touched.current > 2000) setLocal(value)
  }, [value])
  useEffect(() => () => clearTimeout(timer.current), [])
  const send = (v: number, final = false) => {
    touched.current = Date.now()
    clearTimeout(timer.current)
    const wait = 150 - (Date.now() - last.current)
    if (final || wait <= 0) {
      last.current = Date.now()
      onCommit(v)
    } else {
      timer.current = window.setTimeout(() => send(v, true), wait)
    }
  }
  return (
    <div className="flex items-center gap-3">
      {icon && <span className="text-muted">{icon}</span>}
      <input
        type="range"
        className="m3-range no-drag flex-1 disabled:cursor-not-allowed disabled:opacity-40"
        aria-label={label}
        min={0}
        max={100}
        value={local}
        disabled={disabled || value == null}
        style={{ '--fill': `${local}%` } as CSSProperties}
        onChange={(e) => {
          const v = Number(e.target.value)
          setLocal(v)
          send(v)
        }}
        onPointerUp={(e) => send(Number((e.target as HTMLInputElement).value), true)}
      />
      <span className="w-11 text-right text-label font-semibold tabular">{value == null ? '—' : `${local}%`}</span>
    </div>
  )
}

// --- Ring -------------------------------------------------------------------------

/** M3 circular determinate indicator: thick rounded arc, gap, then the track. */
export function Ring({ value, size = 96, children, tone = 'primary', label }: { value: number; size?: number; children?: ReactNode; tone?: 'primary' | 'tertiary'; label: string }) {
  const v = Math.max(0, Math.min(100, value))
  const r = 42
  const c = 2 * Math.PI * r
  const gap = v > 0 && v < 100 ? 8 : 0
  const arc = Math.max(0, (v / 100) * c - gap)
  return (
    <div className="relative grid place-items-center" style={{ width: size, height: size }} role="meter" aria-label={label} aria-valuenow={Math.round(v)} aria-valuemin={0} aria-valuemax={100}>
      <svg viewBox="0 0 100 100" className="absolute inset-0 -rotate-90">
        <circle cx="50" cy="50" r={r} fill="none" strokeWidth="8" strokeLinecap="round" className={tone === 'primary' ? 'stroke-secondary-container' : 'stroke-tertiary-container'} strokeDasharray={`${Math.max(0, c - arc - gap * 2)} ${c}`} strokeDashoffset={-(arc + gap)} />
        <circle cx="50" cy="50" r={r} fill="none" strokeWidth="8" strokeLinecap="round" className={cn('transition-[stroke-dasharray] duration-slow ease-std', tone === 'primary' ? 'stroke-primary' : 'stroke-tertiary')} strokeDasharray={`${arc} ${c}`} />
      </svg>
      <div className="relative text-center">{children}</div>
    </div>
  )
}
