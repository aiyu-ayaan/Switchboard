import { useEffect, useState, type ReactNode } from 'react'
import { Desktop, Fingerprint, Info, Moon, Power, Sun, SunHorizon, TrayArrowDown } from '@phosphor-icons/react'
import { Button, Card, Chip, SectionTitle, Segmented, ShapeIcon, Switch, TextField, useToast } from '@/components/ui'
import { sb, useSettings, type Status } from '@/lib/bridge'

function Row({ icon, title, text, children }: { icon: ReactNode; title: string; text: ReactNode; children: ReactNode }) {
  return (
    <div className="flex items-center gap-4 px-5 py-4">
      {icon}
      <div className="min-w-0 flex-1">
        <p className="font-medium">{title}</p>
        <p className="text-label text-muted">{text}</p>
      </div>
      {children}
    </div>
  )
}

export function Settings({ status }: { status: Status | null }) {
  const toast = useToast()
  const { settings, update } = useSettings()
  const [name, setName] = useState('')
  const [saving, setSaving] = useState(false)
  useEffect(() => {
    if (status) setName((n) => n || status.name)
  }, [status])

  const rename = async () => {
    setSaving(true)
    try {
      await sb.call('rename', { name })
      toast('Name saved. Phones see it on their next connection.')
    } catch (e) {
      toast((e as Error).message)
    } finally {
      setSaving(false)
    }
  }

  if (!settings) return null
  const os = settings.platform === 'win32' ? 'Windows' : 'your desktop session'

  return (
    <>
      <div className="rise mb-2">
        <h1 className="text-page font-semibold tracking-[-0.01em]">Settings</h1>
      </div>

      <SectionTitle>This computer</SectionTitle>
      <Card className="rise flex items-end gap-3 p-5" style={{ ['--i' as string]: 1 }}>
        <div className="flex-1">
          <TextField label="Name shown on your phone" value={name} maxLength={64} onChange={setName} onEnter={rename} />
        </div>
        <Button onClick={rename} loading={saving} disabled={!name.trim() || name.trim() === status?.name} size="lg">
          Save
        </Button>
      </Card>

      <SectionTitle>Startup</SectionTitle>
      <Card className="rise divide-y divide-border/60" style={{ ['--i' as string]: 2 }}>
        <Row
          icon={<ShapeIcon icon={<Power size={20} weight="bold" />} size={40} />}
          title="Start with the computer"
          text={`Switchboard starts in the background when you sign in to ${os}, so your phone can connect straight away.`}
        >
          <Switch label="Start with the computer" checked={settings.autostart} onChange={(v) => update('autostart', v)} />
        </Row>
        <Row
          icon={<ShapeIcon icon={<TrayArrowDown size={20} weight="bold" />} shape="cookie6" tone="secondary" size={40} />}
          title="Keep running when the window closes"
          text={settings.tray ? 'Closing the window leaves Switchboard in the tray. Quit it from the tray menu.' : 'No tray is available on this desktop, so closing the window quits.'}
        >
          <Switch label="Keep running in the tray" checked={settings.closeToTray} disabled={!settings.tray} onChange={(v) => update('closeToTray', v)} />
        </Row>
      </Card>

      <SectionTitle>Appearance</SectionTitle>
      <Card className="rise p-5" style={{ ['--i' as string]: 3 }}>
        <Segmented
          label="Theme"
          value={settings.theme}
          onChange={(v) => update('theme', v)}
          options={[
            { value: 'system', label: 'System', icon: <SunHorizon size={18} /> },
            { value: 'light', label: 'Light', icon: <Sun size={18} /> },
            { value: 'dark', label: 'Dark', icon: <Moon size={18} /> },
          ]}
        />
      </Card>

      <SectionTitle>Security</SectionTitle>
      <Card className="rise divide-y divide-border/60" style={{ ['--i' as string]: 4 }}>
        <Row
          icon={<ShapeIcon icon={<Fingerprint size={20} weight="bold" />} shape="clover" tone="tertiary" size={40} />}
          title="Fingerprint"
          text="Phones pin this key when they pair; a different computer pretending to be this one cannot connect."
        >
          <Chip tone="primary"><span className="font-mono tracking-wider">{status?.fingerprint}</span></Chip>
        </Row>
        <Row
          icon={<ShapeIcon icon={<Desktop size={20} weight="bold" />} shape="flower" tone="secondary" size={40} />}
          title="Network"
          text={`Listening on port ${status?.port} · ${status?.addresses.join(', ') || 'no network'}`}
        >
          <Chip>X25519 · ChaCha20-Poly1305</Chip>
        </Row>
      </Card>

      <SectionTitle>About</SectionTitle>
      <Card className="rise p-5" style={{ ['--i' as string]: 5 }}>
        <div className="flex items-center gap-4">
          <img src="./favicon.svg" alt="" className="h-12 w-12" />
          <div className="flex-1">
            <p className="font-medium">Switchboard {settings.version}{settings.flavor === 'dev' && ' (dev)'}</p>
            <p className="text-label text-muted">Your phone as a remote for this computer.</p>
          </div>
          <Info size={20} className="text-subtle" aria-hidden />
        </div>
      </Card>
    </>
  )
}
