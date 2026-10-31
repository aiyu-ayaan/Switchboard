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

export interface HostState {
  volume: { level: number; muted: boolean } | null
  displays: Display[]
  media: { player: string; title: string; artist: string; playing: boolean } | null
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
export function useHostState(everyMs = 4000) {
  const [state, setState] = useState<HostState | null>(null)
  useEffect(() => {
    let alive = true
    const tick = () =>
      sb.call<HostState>('state').then(
        (s) => alive && setState(s),
        () => {},
      )
    tick()
    const t = setInterval(() => !document.hidden && tick(), everyMs)
    return () => {
      alive = false
      clearInterval(t)
    }
  }, [everyMs])
  return state
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
