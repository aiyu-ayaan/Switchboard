import { useEffect, useState } from 'react'
import { UploadSimple, WarningCircle } from '@phosphor-icons/react'
import { Shell, type Route } from '@/components/Shell'
import { Card, ShapeIcon, Toaster } from '@/components/ui'
import { sb, useEvent, useSettings, useStatus } from '@/lib/bridge'
import { Devices } from '@/pages/Devices'
import { Files } from '@/pages/Files'
import { Home } from '@/pages/Home'
import { Pair } from '@/pages/Pair'
import { Settings } from '@/pages/Settings'

const ROUTES: Route[] = ['/', '/pair', '/devices', '/files', '/settings']
const fromHash = (): Route => {
  const h = location.hash.slice(1) as Route
  return ROUTES.includes(h) ? h : '/'
}

export function App() {
  const [route, setRoute] = useState<Route>(fromHash)
  const { status, fatal } = useStatus()
  const { settings } = useSettings()

  const go = (r: Route) => {
    location.hash = r
    setRoute(r)
  }
  useEffect(() => sb.onNavigate((r) => go(r as Route)), [])
  // Dropping files anywhere takes you to where you choose the phone.
  // The count lives here, not on the Files page, which may not exist yet when the drop happens.
  const [dropped, setDropped] = useState(0)
  useEvent((event, data) => {
    if (event === 'files_dropped') {
      setDropped((data as { count: number }).count)
      go('/files')
    }
  })
  // Show a drop target while files are dragged over the window.
  const [dragging, setDragging] = useState(false)
  useEffect(() => {
    const files = (e: DragEvent) => !!e.dataTransfer?.types.includes('Files')
    const on = (e: DragEvent) => files(e) && setDragging(true)
    const off = (e: DragEvent) => {
      // Leaving the window has no element to move to.
      if (e.type !== 'dragleave' || !e.relatedTarget) setDragging(false)
    }
    window.addEventListener('dragenter', on)
    window.addEventListener('dragover', on)
    window.addEventListener('dragleave', off)
    window.addEventListener('drop', off)
    window.addEventListener('dragend', off)
    return () => {
      window.removeEventListener('dragenter', on)
      window.removeEventListener('dragover', on)
      window.removeEventListener('dragleave', off)
      window.removeEventListener('drop', off)
      window.removeEventListener('dragend', off)
    }
  }, [])
  const phones = (status?.devices ?? []).filter((d) => d.online)
  useEffect(() => {
    const f = () => setRoute(fromHash())
    window.addEventListener('hashchange', f)
    return () => window.removeEventListener('hashchange', f)
  }, [])

  return (
    <Toaster>
      <Shell route={route} go={go} status={status} dev={settings?.flavor === 'dev'}>
        {fatal ? (
          <Card className="rise mt-10 flex items-center gap-4 bg-danger-container p-6 text-on-danger-container">
            <ShapeIcon icon={<WarningCircle size={24} weight="fill" />} tone="danger" size={48} />
            <div>
              <p className="font-semibold">Switchboard cannot run</p>
              <p className="text-label">{fatal}</p>
            </div>
          </Card>
        ) : route === '/pair' ? (
          <Pair status={status} go={go} />
        ) : route === '/devices' ? (
          <Devices status={status} go={go} />
        ) : route === '/files' ? (
          <Files status={status} dropped={dropped} setDropped={setDropped} />
        ) : route === '/settings' ? (
          <Settings status={status} />
        ) : (
          <Home status={status} go={go} />
        )}
      </Shell>
      {dragging && (
        <div className="pointer-events-none fixed inset-0 z-50 grid place-items-center bg-primary/15 p-6 backdrop-blur-sm" role="status">
          <div className="flex items-center gap-3 rounded-3xl border-2 border-dashed border-primary bg-surface px-8 py-6 text-primary shadow-lg">
            <UploadSimple size={28} weight="bold" />
            <p className="text-title font-medium">
              {phones.length === 0 ? 'Connect a phone to send files' : phones.length === 1 ? `Drop to send to ${phones[0].name}` : 'Drop to choose a phone'}
            </p>
          </div>
        </div>
      )}
    </Toaster>
  )
}
