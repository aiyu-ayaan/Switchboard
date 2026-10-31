import { useCallback, useEffect, useState } from 'react'
import QRCode from 'react-qr-code'
import { ArrowClockwise, Check, DeviceMobile, Fingerprint, House, Keyboard, QrCode, WifiHigh } from '@phosphor-icons/react'
import type { Route } from '@/components/Shell'
import { Button, Card, Chip, ShapeIcon, WavyProgress, useToast } from '@/components/ui'
import { sb, useEvent, type Status } from '@/lib/bridge'

const TTL_MS = 300_000

function useNow(everyMs: number) {
  const [now, setNow] = useState(Date.now())
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), everyMs)
    return () => clearInterval(t)
  }, [everyMs])
  return now
}

function Step({ n, icon, title, text }: { n: number; icon: React.ReactNode; title: string; text: string }) {
  return (
    <li className="rise flex gap-4" style={{ ['--i' as string]: n }}>
      <ShapeIcon icon={icon} shape="cookie6" tone={n === 2 ? 'tertiary' : 'secondary'} size={40} />
      <div>
        <p className="font-medium">{title}</p>
        <p className="text-label text-muted">{text}</p>
      </div>
    </li>
  )
}

function Success({ name, again, go }: { name: string; again: () => void; go: (r: Route) => void }) {
  return (
    <div className="flex flex-col items-center py-16 text-center">
      <span className="pop">
        <ShapeIcon icon={<Check size={56} weight="bold" />} shape="sunny" tone="primary" size={140} />
      </span>
      <h1 className="rise mt-8 text-page font-semibold" style={{ ['--i' as string]: 2 }}>
        {name} is paired
      </h1>
      <p className="rise mt-2 max-w-md text-body text-muted" style={{ ['--i' as string]: 3 }}>
        It can control this computer now, and reconnects by itself whenever both are on the same network.
      </p>
      <div className="rise mt-8 flex gap-3" style={{ ['--i' as string]: 4 }}>
        <Button variant="tonal" icon={<QrCode size={18} />} onClick={again}>
          Pair another
        </Button>
        <Button icon={<House size={18} weight="fill" />} onClick={() => go('/')}>
          Done
        </Button>
      </div>
    </div>
  )
}

export function Pair({ status, go }: { status: Status | null; go: (r: Route) => void }) {
  const toast = useToast()
  const [paired, setPaired] = useState<string | null>(null)
  const now = useNow(500)
  const pairing = status?.pairing ?? null

  const start = useCallback(() => {
    setPaired(null)
    sb.call('pair_start').catch((e: Error) => toast(e.message))
  }, [toast])

  // A code exists only while this page is open.
  useEffect(() => {
    start()
    return () => {
      sb.call('pair_cancel').catch(() => {})
    }
  }, [start])

  useEvent(
    useCallback(
      (event: string, data: unknown) => {
        if (event === 'paired') setPaired((data as { name: string }).name)
        if (event === 'pair_failed') toast('A phone entered the wrong code. Check the code and try again.')
      },
      [toast],
    ),
  )

  const left = pairing ? Math.max(0, pairing.expires_at - now) : 0
  // Expired, or closed after too many wrong codes: show a fresh one. The
  // second case is a code that vanished while we were not just starting.
  const [seen, setSeen] = useState(false)
  useEffect(() => {
    if (pairing) setSeen(true)
  }, [pairing])
  const dead = (pairing && left === 0) || (seen && !pairing)
  useEffect(() => {
    if (!paired && dead) {
      setSeen(false)
      start()
    }
  }, [paired, dead, start])

  if (paired) return <Success name={paired} again={start} go={go} />

  const mins = Math.floor(left / 60000)
  const secs = Math.floor((left % 60000) / 1000)
  const [a, b] = (pairing?.code ?? '----·----').split(/[-·]/)

  return (
    <>
      <div className="rise mb-8">
        <h1 className="text-page font-semibold tracking-[-0.01em]">Pair a phone</h1>
        <p className="mt-1 text-body text-muted">Open Switchboard on your Android phone, tap Add computer, and scan this code.</p>
      </div>

      <div className="grid grid-cols-[minmax(0,360px)_1fr] gap-6">
        <Card className="rise flex flex-col items-center gap-5 p-6" tone="container">
          <div className="relative rounded-3xl bg-white p-5 shadow-sm">
            {pairing ? (
              <QRCode value={pairing.uri} size={272} level="M" bgColor="#FFFFFF" fgColor="#131317" aria-label="Pairing QR code" />
            ) : (
              <div className="grid h-[272px] w-[272px] place-items-center">
                <ShapeIcon icon={null} shape="sunny" size={56} spin />
              </div>
            )}
            <span className="absolute left-1/2 top-1/2 grid h-14 w-14 -translate-x-1/2 -translate-y-1/2 place-items-center rounded-2xl bg-white">
              <img src="./favicon.svg" alt="" className="h-11 w-11" />
            </span>
          </div>
          <div className="w-full">
            <WavyProgress value={left / TTL_MS} label="Time left on this code" />
            <div className="mt-2 flex items-center justify-between text-label text-muted">
              <span className="tabular">
                Expires in {mins}:{secs.toString().padStart(2, '0')}
              </span>
              <Button variant="text" size="sm" icon={<ArrowClockwise size={16} />} onClick={start}>
                New code
              </Button>
            </div>
          </div>
        </Card>

        <div className="flex flex-col gap-4">
          <Card className="rise p-6" style={{ ['--i' as string]: 1 }}>
            <p className="flex items-center gap-2 text-label font-medium text-muted">
              <Keyboard size={18} aria-hidden /> No camera? Type this code instead
            </p>
            <div className="mt-4 flex items-center gap-3" aria-label={`Pairing code ${pairing?.code ?? ''}`}>
              {[a, b].map((part, i) => (
                <div key={i} className="flex gap-1.5">
                  {(part ?? '----').split('').map((ch, j) => (
                    <span key={j} className="grid h-16 w-12 place-items-center rounded-xl bg-tertiary-container font-mono text-[30px] font-semibold text-on-tertiary-container">
                      {ch}
                    </span>
                  ))}
                  {i === 0 && <span className="mx-1 self-center text-headline text-subtle">–</span>}
                </div>
              ))}
            </div>
          </Card>

          <Card className="rise p-6" style={{ ['--i' as string]: 2 }}>
            <ol className="flex flex-col gap-5">
              <Step n={1} icon={<WifiHigh size={20} weight="bold" />} title="Same Wi-Fi" text={`Your phone must be on the network this computer uses (${status?.addresses.slice(0, 2).join(', ') || 'no network found'}).`} />
              <Step n={2} icon={<DeviceMobile size={20} weight="bold" />} title="Scan or type" text="In the app, tap Add computer. Scan the QR code or enter the 8-character code." />
              <Step n={3} icon={<Fingerprint size={20} weight="bold" />} title="Check the fingerprint" text="Your phone shows the same fingerprint as below. If it differs, cancel." />
            </ol>
            <div className="mt-6 flex flex-wrap items-center gap-2">
              <Chip tone="primary" icon={<Fingerprint size={16} />}>
                <span className="font-mono tracking-wider">{status?.fingerprint}</span>
              </Chip>
              <Chip>Single use · 5 minutes</Chip>
            </div>
          </Card>
        </div>
      </div>
    </>
  )
}
