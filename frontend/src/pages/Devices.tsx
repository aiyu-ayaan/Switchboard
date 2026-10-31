import { useState } from 'react'
import { DeviceMobile, QrCode, Trash, Warning } from '@phosphor-icons/react'
import type { Route } from '@/components/Shell'
import { Button, Card, Dialog, ShapeIcon, StatusDot, useToast } from '@/components/ui'
import { sb, timeAgo, type DeviceView, type Status } from '@/lib/bridge'

export function Devices({ status, go }: { status: Status | null; go: (r: Route) => void }) {
  const toast = useToast()
  const [forget, setForget] = useState<DeviceView | null>(null)
  const [busy, setBusy] = useState(false)
  const devices = status?.devices ?? []

  const confirm = async () => {
    if (!forget) return
    setBusy(true)
    try {
      await sb.call('forget', { id: forget.id })
      toast(`${forget.name} was forgotten`)
      setForget(null)
    } catch (e) {
      toast((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <div className="rise mb-8 flex items-end justify-between gap-4">
        <div>
          <h1 className="text-page font-semibold tracking-[-0.01em]">Phones</h1>
          <p className="mt-1 text-body text-muted">Phones that may control this computer. Forget one to revoke its access straight away.</p>
        </div>
        {devices.length > 0 && (
          <Button variant="tonal" icon={<QrCode size={18} />} onClick={() => go('/pair')}>
            Pair a phone
          </Button>
        )}
      </div>

      {devices.length === 0 ? (
        <Card className="rise flex flex-col items-center px-8 py-16 text-center">
          <ShapeIcon icon={<DeviceMobile size={36} weight="fill" />} shape="flower" tone="tertiary" size={96} />
          <h2 className="mt-6 text-headline font-semibold">No phones yet</h2>
          <p className="mt-2 max-w-sm text-body text-muted">Pair your Android phone to control volume, brightness and media from anywhere in the room.</p>
          <Button className="mt-6" icon={<QrCode size={18} />} onClick={() => go('/pair')}>
            Pair a phone
          </Button>
        </Card>
      ) : (
        <div className="flex flex-col gap-2">
          {devices.map((d, i) => (
            <Card key={d.id} className="rise flex items-center gap-4 p-4 first:rounded-t-3xl last:rounded-b-3xl" style={{ ['--i' as string]: i }}>
              <ShapeIcon icon={<DeviceMobile size={24} weight="fill" />} tone={d.online ? 'primary' : 'neutral'} size={52} />
              <div className="min-w-0 flex-1">
                <p className="truncate text-title font-medium">{d.name}</p>
                <p className="mt-0.5 flex items-center gap-2 text-label text-muted">
                  <StatusDot on={d.online} pulse={d.online} />
                  {d.online ? 'Connected now' : `Last seen ${timeAgo(d.last_seen)}`}
                  <span aria-hidden>·</span>
                  Paired {new Date(d.paired_at * 1000).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' })}
                </p>
              </div>
              <Button variant="danger" size="sm" icon={<Trash size={16} />} onClick={() => setForget(d)}>
                Forget
              </Button>
            </Card>
          ))}
        </div>
      )}

      <Dialog
        open={!!forget}
        onClose={() => setForget(null)}
        icon={<ShapeIcon icon={<Warning size={28} weight="fill" />} shape="cookie9" tone="danger" size={56} />}
        title={`Forget ${forget?.name ?? ''}?`}
        actions={
          <>
            <Button variant="text" onClick={() => setForget(null)}>
              Cancel
            </Button>
            <Button variant="danger" loading={busy} onClick={confirm}>
              Forget
            </Button>
          </>
        }
      >
        It disconnects now and can no longer control this computer. To use it again, pair it with a new code.
      </Dialog>
    </>
  )
}
