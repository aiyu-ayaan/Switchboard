import { useCallback, useEffect, useState } from 'react'
import { ArrowCircleUp } from '@phosphor-icons/react'
import { Button, Card, Dialog, Segmented, ShapeIcon, WavyProgress, useToast } from '@/components/ui'
import { sb, useEvent, useUpdateProgress, type UpdateChannel, type UpdateInfo, type UpdateOffer } from '@/lib/bridge'

const install = async (toast: (t: string) => void) => {
  const why = await sb.installUpdate()
  if (why) toast(why)
}

/** At start: offers a newer release on the chosen channel. "Later" is remembered for that version. */
export function UpdatePrompt() {
  const toast = useToast()
  const progress = useUpdateProgress()
  const [offer, setOffer] = useState<UpdateOffer | null>(null)
  // The check at start may have finished before this page loaded, or may still be running.
  useEffect(() => {
    sb.updateInfo().then((i) => i?.offer && setOffer(i.offer))
  }, [])
  useEvent(useCallback((event: string, data: unknown) => event === 'update_available' && setOffer(data as UpdateOffer), []))
  const later = () => {
    if (progress != null) return
    sb.skipUpdate()
    setOffer(null)
  }
  if (!offer) return null
  return (
    <Dialog
      open
      onClose={later}
      icon={<ShapeIcon icon={<ArrowCircleUp size={24} weight="bold" />} size={48} />}
      title={`Switchboard ${offer.version} is out`}
      actions={
        <>
          <Button variant="text" onClick={later} disabled={progress != null}>Later</Button>
          <Button onClick={() => install(toast)} loading={progress != null}>
            {progress != null ? `Downloading ${Math.round(progress * 100)}%` : 'Update'}
          </Button>
        </>
      }
    >
      <p>Install it now? Your paired phones stay paired.</p>
      {progress != null && <WavyProgress className="mt-4" value={progress} label="Downloading the update" />}
    </Dialog>
  )
}

/** Settings > Updates: the channel, whether it has something newer, and the button to take it. */
export function UpdatesCard({ style }: { style?: React.CSSProperties }) {
  const toast = useToast()
  const progress = useUpdateProgress()
  const [info, setInfo] = useState<UpdateInfo | null>(null)
  // undefined: still asking; null: nothing newer.
  const [found, setFound] = useState<UpdateOffer | null | undefined>(undefined)
  const [failed, setFailed] = useState(false)

  const check = useCallback(async () => {
    setFound(undefined)
    setFailed(false)
    try {
      setFound(await sb.checkUpdate())
    } catch {
      setFailed(true)
      setFound(null)
    }
  }, [])
  useEffect(() => {
    sb.updateInfo().then((i) => {
      setInfo(i)
      if (i?.canUpdate) check()
    })
  }, [check])

  if (!info) return null
  const setChannel = async (c: UpdateChannel) => {
    await sb.setUpdateChannel(c)
    setInfo({ ...info, channel: c })
    check()
  }
  const title = !info.canUpdate
    ? 'Updates come with rebuilding'
    : found === undefined
      ? 'Checking for updates…'
      : failed
        ? "Couldn't check for updates"
        : found
          ? `Switchboard ${found.version} is out`
          : "You're up to date"
  const text = !info.canUpdate
    ? `This is a development build (${info.version}).`
    : found
      ? info.selfInstall
        ? 'It replaces this copy and restarts.'
        : 'The installer is saved to Downloads and opened.'
      : `You have ${info.version}.`

  return (
    <Card className="rise divide-y divide-border/60" style={style}>
      <div className="flex items-center gap-4 px-5 py-4">
        <ShapeIcon icon={<ArrowCircleUp size={20} weight="bold" />} shape="cookie6" size={40} />
        <div className="min-w-0 flex-1">
          <p className="font-medium">{title}</p>
          <p className="text-label text-muted">{text}</p>
        </div>
        {info.canUpdate &&
          (found ? (
            <Button onClick={() => install(toast)} loading={progress != null}>
              {progress != null ? `${Math.round(progress * 100)}%` : 'Update'}
            </Button>
          ) : (
            <Button variant="outlined" onClick={check} loading={found === undefined}>Check again</Button>
          ))}
      </div>
      {progress != null && (
        <div className="px-5 py-4">
          <WavyProgress value={progress} label="Downloading the update" />
        </div>
      )}
      {info.canUpdate && (
        <div className="px-5 py-4">
          <Segmented
            label="Update channel"
            value={info.channel}
            onChange={setChannel}
            options={[
              { value: 'stable', label: 'Stable' },
              { value: 'beta', label: 'Beta' },
              { value: 'alpha', label: 'Alpha' },
            ]}
          />
          <p className="mt-2 text-label text-muted">Beta also takes stable releases; Alpha takes every release.</p>
        </div>
      )}
    </Card>
  )
}
