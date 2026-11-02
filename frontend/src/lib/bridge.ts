// Typed access to the preload bridge (electron/preload.cjs) and the daemon's
// status and state shapes (backend/src/app.rs, core/src/protocol.rs).
import { useCallback, useEffect, useState } from 'react'

export interface DeviceView {
  id: string
  name: string
  paired_at: number
  last_seen: number
  online: boolean
}

export interface Status {
  host_id: string
  name: string
  os: string
  port: number
  addresses: string[]
  fingerprint: string
  pairing: { uri: string; code: string; expires_at: number } | null
  devices: DeviceView[]
}

export interface Display {
  id: string
  name: string
  kind: 'internal' | 'external'
  brightness: number | null
  contrast: number | null
}

export interface AudioOutput {
  id: string
  name: string
  default: boolean
}

export interface AudioApp {
  id: string
  name: string
  detail: string
  level: number
  muted: boolean
}

export type RepeatMode = 'off' | 'track' | 'all'

export interface Media {
  id: string
  player: string
  title: string
  artist: string
  playing: boolean
  /** Milliseconds. */
  position: number | null
  length: number | null
  can_seek: boolean
  shuffle: boolean | null
  repeat: RepeatMode | null
  /** Changes with the cover; fetch it with `media_art`. */
  art: string | null
}

export interface HostState {
  volume: { level: number; muted: boolean } | null
  outputs: AudioOutput[]
  apps: AudioApp[]
  displays: Display[]
  media: Media | null
  players: { id: string; name: string; playing: boolean }[]
  stats: { cpu: number; mem_used: number; mem_total: number; uptime: number; battery: { percent: number; charging: boolean } | null }
}

export interface AppSettings {
  autostart: boolean
  closeToTray: boolean
  theme: 'system' | 'light' | 'dark'
  version: string
  platform: string
  flavor: 'dev' | 'prod'
  tray: boolean
}

interface Bridge {
  call<T = unknown>(cmd: string, args?: Record<string, unknown>): Promise<T>
  /** Opens a file or folder dialog, then offers the choice to that phone. Resolves to how many were offered (0 if cancelled). */
  sendFiles(device: string, kind: 'files' | 'folder'): Promise<number>
  /** Offers what the user dropped on the window. */
  sendDropped(device: string): Promise<number>
  clearDropped(): Promise<void>
  openInbox(): Promise<void>
  snapshot(): Promise<{ status: Status | null; fatal: string | null; running: boolean }>
  settings(): Promise<AppSettings>
  set(key: string, value: unknown): Promise<void>
  onEvent(fn: (m: { event: string; data: unknown }) => void): () => void
  onNavigate(fn: (route: string) => void): () => void
}

declare global {
  interface Window {
    switchboard: Bridge
  }
}

export const sb = window.switchboard

export function useEvent(fn: (event: string, data: unknown) => void) {
  useEffect(() => sb.onEvent((m) => fn(m.event, m.data)), [fn])
}

/** Daemon status, pushed on every change. */
export function useStatus() {
  const [status, setStatus] = useState<Status | null>(null)
  const [fatal, setFatal] = useState<string | null>(null)
  useEffect(() => {
    sb.snapshot().then((s) => {
      if (s.status) setStatus(s.status)
      setFatal(s.fatal)
    })
    // The daemon may have answered `ready` before this window existed.
    sb.call<Status>('status').then(setStatus, () => {})
    return sb.onEvent(({ event, data }) => {
      if (event === 'status') {
        setStatus(data as Status)
        setFatal(null)
      }
      if (event === 'fatal') setFatal(data as string)
    })
  }, [])
  return { status, fatal }
}

/** What a phone would see, polled while the page is on screen. */
export function useHostState(everyMs = 3000) {
  const [state, setState] = useState<HostState | null>(null)
  const [tick, setTick] = useState(0)
  useEffect(() => {
    let alive = true
    const load = () =>
      sb.call<HostState>('state').then(
        (s) => alive && setState(s),
        () => {},
      )
    load()
    const t = setInterval(() => !document.hidden && load(), everyMs)
    return () => {
      alive = false
      clearInterval(t)
    }
  }, [everyMs, tick])
  const refresh = useCallback(() => setTick((n) => n + 1), [])
  return { state, refresh }
}

export interface FileEvent {
  kind: 'received' | 'sent' | 'declined' | 'cancelled'
  device: string
  name: string
  size?: number
  at: number
}

export interface FileProgress {
  xfer: string
  dir: 'in' | 'out'
  device: string
  name: string
  done: number
  total: number
}

export interface HistoryRow {
  at: number
  device: string
  name: string
  size: number
  dir: 'in' | 'out'
  status: 'done' | 'cancelled' | 'declined'
}

/** Files moving now, and the ones that finished since this window opened. */
export function useTransfers() {
  const [events, setEvents] = useState<FileEvent[]>([])
  const [active, setActive] = useState<Record<string, FileProgress>>({})
  useEffect(
    () =>
      sb.onEvent(({ event, data }) => {
        if (event === 'file_progress') {
          const p = data as FileProgress
          setActive((a) => ({ ...a, [p.xfer]: p }))
          return
        }
        const kind = ({ file_received: 'received', file_sent: 'sent', file_declined: 'declined', file_cancelled: 'cancelled' } as const)[event as string]
        if (!kind) return
        const d = data as { xfer?: string; device: string; name: string; size?: number }
        const x = d.xfer
        if (x)
          setActive((a) => {
            const { [x]: _gone, ...rest } = a
            return rest
          })
        setEvents((e) => [{ kind, device: d.device, name: d.name, size: d.size, at: Date.now() }, ...e].slice(0, 20))
      }),
    [],
  )
  return { events, active: Object.values(active) }
}

/** Past transfers, reloaded whenever one finishes. */
export function useHistory(refreshKey: number) {
  const [rows, setRows] = useState<HistoryRow[]>([])
  useEffect(() => {
    sb.call<HistoryRow[]>('history').then(setRows, () => {})
  }, [refreshKey])
  return rows
}

export function useSettings() {
  const [settings, setSettings] = useState<AppSettings | null>(null)
  useEffect(() => {
    sb.settings().then(setSettings)
  }, [])
  const update = useCallback(async <K extends keyof AppSettings>(key: K, value: AppSettings[K]) => {
    setSettings((s) => (s ? { ...s, [key]: value } : s))
    await sb.set(key, value)
    setSettings(await sb.settings())
  }, [])
  return { settings, update }
}

export function timeAgo(secs: number) {
  const d = Math.max(0, Date.now() / 1000 - secs)
  if (d < 60) return 'just now'
  if (d < 3600) return `${Math.floor(d / 60)} min ago`
  if (d < 86400) return `${Math.floor(d / 3600)} h ago`
  return new Date(secs * 1000).toLocaleDateString(undefined, { day: 'numeric', month: 'short' })
}

export function bytes(n: number) {
  const gb = n / 1024 ** 3
  return gb >= 1 ? `${gb.toFixed(1)} GB` : `${Math.round(n / 1024 ** 2)} MB`
}

export type Control =
  | { type: 'set_volume'; level: number }
  | { type: 'set_mute'; muted: boolean }
  | { type: 'set_output'; output: string }
  | { type: 'set_app_volume'; app: string; level: number }
  | { type: 'set_app_mute'; app: string; muted: boolean }
  | { type: 'set_brightness'; display: string; level: number }
  | { type: 'set_contrast'; display: string; level: number }
  | { type: 'media'; action: 'play_pause' | 'next' | 'previous' }
  | { type: 'seek'; position: number }
  | { type: 'set_shuffle'; on: boolean }
  | { type: 'set_repeat'; mode: RepeatMode }
  | { type: 'select_player'; player: string }
  | { type: 'media_art' }

/** Runs a control on this computer, the same path a phone command takes. */
export const run = <T = unknown>(command: Control) => sb.call<T>('run', { command })

/** The cover of the current track as a data URL, or null while there is none. */
export function useCover(art: string | null | undefined) {
  const [url, setUrl] = useState<string | null>(null)
  useEffect(() => {
    setUrl(null)
    if (!art) return
    let alive = true
    run<string>({ type: 'media_art' }).then(
      (b64) => alive && b64 && setUrl(`data:image/*;base64,${b64.replace(/-/g, '+').replace(/_/g, '/')}`),
      () => {},
    )
    return () => {
      alive = false
    }
  }, [art])
  return url
}

export function clock(ms: number) {
  const s = Math.max(0, Math.floor(ms / 1000))
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = String(s % 60).padStart(2, '0')
  return h ? `${h}:${String(m).padStart(2, '0')}:${sec}` : `${m}:${sec}`
}
