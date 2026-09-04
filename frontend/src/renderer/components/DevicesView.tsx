import { QrCode, RefreshCw, Smartphone, Trash2 } from 'lucide-react';
import { useEffect, useState } from 'react';
import QRCodeLib from 'qrcode';
import type { LocalState } from '../../shared/types';
import { Card, EmptyState, Pane, Sidebar, SidebarItem } from './Shell';

interface DevicesViewProps {
  state: LocalState;
  refresh: () => void;
}

export function DevicesView({ state, refresh }: DevicesViewProps) {
  const { pairing, devices } = state;
  const [qr, setQr] = useState<string | null>(null);
  const [pendingRevoke, setPendingRevoke] = useState<string | null>(null);

  // Rendered locally; the payload never leaves the machine.
  useEffect(() => {
    let cancelled = false;
    QRCodeLib.toDataURL(pairing.qrPayload, {
      margin: 1,
      width: 320,
      color: { dark: '#16161e', light: '#c8d1f0' }
    })
      .then((url) => !cancelled && setQr(url))
      .catch(() => !cancelled && setQr(null));
    return () => {
      cancelled = true;
    };
  }, [pairing.qrPayload]);

  const revoke = async (deviceId: string) => {
    await window.switchboard.revokeDevice(deviceId).catch(() => {});
    setPendingRevoke(null);
    refresh();
  };

  const rotate = async () => {
    await window.switchboard.rotatePairing().catch(() => {});
    refresh();
  };

  return (
    <>
      <Sidebar title="Devices">
        {devices.length === 0 ? (
          <p className="px-2 py-3 text-micro text-ink-faint">Nothing paired yet.</p>
        ) : (
          devices.map((device) => (
            <SidebarItem
              key={device.id}
              label={device.name}
              icon={Smartphone}
              detail={device.online ? 'Online' : undefined}
              selected={false}
              onSelect={() => {}}
            />
          ))
        )}
      </Sidebar>

      <Pane
        title="Devices"
        description="Scan to pair a phone, or revoke access"
        actions={
          <button
            type="button"
            onClick={rotate}
            className="flex items-center gap-1.5 rounded border border-edge px-2.5 py-1 text-micro text-ink-dim transition-colors hover:bg-raised hover:text-ink"
          >
            <RefreshCw aria-hidden="true" className="h-3 w-3" />
            New code
          </button>
        }
      >
        <div className="grid max-w-4xl gap-3 lg:grid-cols-[auto_1fr]">
          <Card title="Pair a device">
            <div className="flex flex-col items-center gap-3">
              {qr ? (
                <img
                  src={qr}
                  alt={`Pairing QR code for ${pairing.hostName}. Pairing code ${pairing.code}.`}
                  className="h-56 w-56 rounded bg-ink p-2"
                />
              ) : (
                <div className="flex h-56 w-56 items-center justify-center rounded bg-raised">
                  <QrCode aria-hidden="true" className="h-8 w-8 text-ink-faint" />
                </div>
              )}

              <div className="text-center">
                <p className="text-micro text-ink-faint">Or type this code on your phone</p>
                <p className="font-mono text-lg font-semibold tracking-[0.25em] text-ink">
                  {pairing.code}
                </p>
              </div>

              <dl className="w-full space-y-1 border-t border-edge pt-2 font-mono text-micro">
                <Row label="Host" value={`${pairing.host}:${pairing.port}`} />
                <Row label="Expires" value={expiryLabel(pairing.expiresAt)} />
              </dl>
            </div>
          </Card>

          <Card title={`Paired devices (${devices.length})`}>
            {devices.length === 0 ? (
              <EmptyState
                icon={Smartphone}
                title="No paired devices"
                hint="Open Switchboard on your phone and scan the code to the left. Pairing happens over the local network only."
              />
            ) : (
              <ul className="divide-y divide-edge">
                {devices.map((device) => (
                  <li key={device.id} className="flex items-center gap-3 py-2.5">
                    <Smartphone aria-hidden="true" className="h-4 w-4 shrink-0 text-ink-faint" />
                    <div className="min-w-0 flex-1">
                      <p className="truncate text-tiny text-ink">{device.name}</p>
                      <p className="text-micro text-ink-faint">
                        {/* Status is spelled out, not signalled by colour alone. */}
                        {device.online ? 'Connected now' : `Last seen ${relative(device.lastSeen)}`}
                      </p>
                    </div>

                    {pendingRevoke === device.id ? (
                      <div className="flex shrink-0 items-center gap-1.5">
                        <button
                          type="button"
                          onClick={() => revoke(device.id)}
                          className="rounded bg-danger px-2 py-1 text-micro font-medium text-canvas"
                        >
                          Forget
                        </button>
                        <button
                          type="button"
                          onClick={() => setPendingRevoke(null)}
                          className="rounded border border-edge px-2 py-1 text-micro text-ink-dim hover:text-ink"
                        >
                          Cancel
                        </button>
                      </div>
                    ) : (
                      <button
                        type="button"
                        onClick={() => setPendingRevoke(device.id)}
                        aria-label={`Forget ${device.name}`}
                        className="shrink-0 rounded p-1.5 text-ink-faint transition-colors hover:bg-danger/15 hover:text-danger"
                      >
                        <Trash2 aria-hidden="true" className="h-3.5 w-3.5" />
                      </button>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </Card>
        </div>
      </Pane>
    </>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between gap-2">
      <dt className="text-ink-faint">{label}</dt>
      <dd className="truncate text-ink-dim">{value}</dd>
    </div>
  );
}

function expiryLabel(expiresAt: number): string {
  const seconds = Math.round((expiresAt - Date.now()) / 1000);
  if (seconds <= 0) return 'expired';
  if (seconds < 60) return `${seconds}s`;
  return `${Math.round(seconds / 60)}m`;
}

function relative(iso: string): string {
  const seconds = Math.round((Date.now() - new Date(iso).getTime()) / 1000);
  if (seconds < 60) return 'just now';
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h ago`;
  return `${Math.floor(seconds / 86400)}d ago`;
}
