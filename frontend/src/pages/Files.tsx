import { useEffect, useState } from 'react'
import { ArrowDown, ArrowUp, Check, File, FolderOpen, Files as FilesIcon, PaperPlaneTilt, X } from '@phosphor-icons/react'
import { Button, Card, Meter, SectionTitle, ShapeIcon, Switch, useToast } from '@/components/ui'
import { bytes, sb, timeAgo, useEvent, useHistory, useTransfers, type Status } from '@/lib/bridge'

const verb = { done: '', cancelled: 'cancelled', declined: 'declined' } as const

export function Files({ status }: { status: Status | null }) {
  const toast = useToast()
  const { events, active } = useTransfers()
  const history = useHistory(events.length)
  const [dropped, setDropped] = useState(0)
  useEvent((event, data) => {
    if (event === 'files_dropped') setDropped((data as { count: number }).count)
  })
  // A drop only lasts a couple of minutes; stop offering it after that.
  useEffect(() => {
    if (!dropped) return
    const t = setTimeout(() => setDropped(0), 120000)
    return () => clearTimeout(t)
  }, [dropped])

  const online = (status?.devices ?? []).filter((d) => d.online)
  const report = (n: number, phone: string) => n && toast(`Asked ${phone} to take ${n === 1 ? 'a file' : `${n} items`}`)
  const fail = (e: Error) => toast(e.message)
  const requests = status?.requests ?? []
  const answer = (xfer: string | undefined, accept: boolean) => sb.call('approve', { xfer, accept }).catch(fail)

  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-3">
      {requests.length > 0 && (
        <Card className="rise flex flex-col gap-3 bg-primary-container p-5 text-on-primary-container">
          <div className="flex flex-wrap items-center gap-2">
            <p className="min-w-0 flex-1 font-medium">
              {requests.length === 1 ? 'A phone wants to send you a file' : `${requests.length} files are waiting for you`}
            </p>
            {requests.length > 1 && (
              <>
                <Button size="sm" icon={<Check size={16} />} onClick={() => answer(undefined, true)}>
                  Accept all
                </Button>
                <Button variant="text" size="sm" icon={<X size={16} />} onClick={() => answer(undefined, false)}>
                  Decline all
                </Button>
              </>
            )}
          </div>
          {requests.slice(0, 5).map((r) => (
            <div key={r.xfer} className="flex flex-wrap items-center gap-2 rounded-2xl bg-surface/60 p-3">
              <div className="min-w-0 flex-1">
                <p className="truncate font-medium">{r.name}</p>
                <p className="text-caption">
                  from {r.device} · {bytes(r.size)}
                </p>
              </div>
              <Button size="sm" icon={<Check size={16} />} onClick={() => answer(r.xfer, true)}>
                Accept
              </Button>
              <Button variant="text" size="sm" icon={<X size={16} />} onClick={() => answer(r.xfer, false)}>
                Decline
              </Button>
            </div>
          ))}
          {requests.length > 5 && <p className="text-caption">and {requests.length - 5} more</p>}
        </Card>
      )}

      <Card className="rise flex items-center gap-3 p-5">
        <div className="min-w-0 flex-1">
          <p className="font-medium">Accept files from phones automatically</p>
          <p className="text-caption text-muted">
            {status?.auto_accept_files === false ? 'You are asked before each file is saved.' : "Files are saved to Downloads/Switchboard without asking."}
          </p>
        </div>
        <Switch label="Accept files from phones automatically" checked={status?.auto_accept_files !== false} disabled={!status} onChange={(on) => sb.call('set_auto_accept', { on }).catch(fail)} />
      </Card>

      <Card className="rise flex flex-col gap-4 p-6">
        <div className="flex items-center gap-3">
          <ShapeIcon icon={<FilesIcon size={22} weight="fill" />} shape="clover" tone="tertiary" size={44} />
          <div className="min-w-0 flex-1">
            <p className="text-title font-medium">Send to a phone</p>
            <p className="text-caption text-muted">Choose files or a folder, or drop them anywhere on this window. A folder is sent as a zip.</p>
          </div>
          <Button variant="tonal" size="sm" icon={<FolderOpen size={18} />} onClick={() => sb.openInbox().catch(fail)}>
            Open received files
          </Button>
        </div>

        {dropped > 0 && (
          <div className="flex flex-col gap-2 rounded-2xl bg-primary-container p-4 text-on-primary-container" role="status">
            <p className="font-medium">
              {dropped === 1 ? '1 item' : `${dropped} items`} ready. Which phone gets {dropped === 1 ? 'it' : 'them'}?
            </p>
            <div className="flex flex-wrap items-center gap-2">
              {online.length === 0 && <span className="text-label">No phone is connected.</span>}
              {online.map((d) => (
                <Button
                  key={d.id}
                  size="sm"
                  icon={<PaperPlaneTilt size={16} />}
                  onClick={() =>
                    sb.sendDropped(d.id).then(
                      (n) => {
                        setDropped(0)
                        report(n, d.name)
                      },
                      fail,
                    )
                  }
                >
                  {d.name}
                </Button>
              ))}
              <Button
                variant="text"
                size="sm"
                icon={<X size={16} />}
                onClick={() => {
                  setDropped(0)
                  sb.clearDropped()
                }}
              >
                Cancel
              </Button>
            </div>
          </div>
        )}

        {online.length === 0 ? (
          <p className="text-body text-muted">Connect a phone to send it files.</p>
        ) : (
          <div className="flex flex-col gap-2">
            {online.map((d) => (
              <div key={d.id} className="flex flex-wrap items-center gap-2 rounded-2xl bg-container p-3">
                <p className="min-w-0 flex-1 truncate font-medium">{d.name}</p>
                <Button variant="tonal" size="sm" icon={<File size={16} />} onClick={() => sb.sendFiles(d.id, 'files').then((n) => report(n, d.name), fail)}>
                  Files
                </Button>
                <Button variant="tonal" size="sm" icon={<FolderOpen size={16} />} onClick={() => sb.sendFiles(d.id, 'folder').then((n) => report(n, d.name), fail)}>
                  Folder
                </Button>
              </div>
            ))}
          </div>
        )}
      </Card>

      {active.length > 0 && (
        <>
          <SectionTitle>In progress</SectionTitle>
          <Card className="flex flex-col gap-4 p-5">
            {active.map((t) => (
              <div key={t.xfer} className="flex flex-col gap-1.5">
                <div className="flex items-center gap-2">
                  {t.dir === 'in' ? <ArrowDown size={16} className="text-muted" /> : <ArrowUp size={16} className="text-muted" />}
                  <span className="min-w-0 flex-1 truncate font-medium">{t.name}</span>
                  <span className="text-caption text-muted tabular">
                    {bytes(t.done)} of {bytes(t.total)}
                  </span>
                </div>
                <Meter value={t.total ? (t.done / t.total) * 100 : 100} tone={t.dir === 'in' ? 'primary' : 'tertiary'} />
              </div>
            ))}
          </Card>
        </>
      )}

      <SectionTitle>History</SectionTitle>
      <Card className="flex flex-col p-2">
        {history.length === 0 ? (
          <p className="p-4 text-body text-muted">Nothing has been sent or received yet.</p>
        ) : (
          history.map((h, i) => (
            <div key={i} className="flex items-center gap-3 rounded-xl p-3 hover:bg-container">
              {h.dir === 'in' ? <ArrowDown size={18} className="text-primary" /> : <ArrowUp size={18} className="text-tertiary" />}
              <div className="min-w-0 flex-1">
                <p className="truncate font-medium">{h.name}</p>
                <p className="text-caption text-muted">
                  {h.dir === 'in' ? 'from' : 'to'} {h.device}
                  {h.status !== 'done' && ` · ${verb[h.status]}`}
                  {h.size > 0 && ` · ${bytes(h.size)}`}
                </p>
              </div>
              <span className="shrink-0 text-caption text-muted">{timeAgo(h.at)}</span>
            </div>
          ))
        )}
      </Card>
    </div>
  )
}
