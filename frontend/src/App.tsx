import { useEffect, useState } from 'react'
import { WarningCircle } from '@phosphor-icons/react'
import { Shell, type Route } from '@/components/Shell'
import { Card, ShapeIcon, Toaster } from '@/components/ui'
import { sb, useSettings, useStatus } from '@/lib/bridge'
import { Devices } from '@/pages/Devices'
import { Home } from '@/pages/Home'
import { Pair } from '@/pages/Pair'
import { Settings } from '@/pages/Settings'

const ROUTES: Route[] = ['/', '/pair', '/devices', '/settings']
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
        ) : route === '/settings' ? (
          <Settings status={status} />
        ) : (
          <Home status={status} go={go} />
        )}
      </Shell>
    </Toaster>
  )
}
