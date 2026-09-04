import {
  ArrowDownToLine,
  ArrowUpFromLine,
  FolderOpen,
  Pause,
  Play,
  Smartphone,
  Upload,
  X
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { useState } from 'react';
import type { FileTransfer, HostSettings, LocalState } from '../../shared/types';
import { Card, EmptyState, Pane, Sidebar, SidebarItem } from './Shell';

interface FilesViewProps {
  state: LocalState;
  refresh: () => void;
}

const LIVE: FileTransfer['status'][] = ['pending', 'active', 'paused'];

export function FilesView({ state, refresh }: FilesViewProps) {
  const { devices, transfers, settings } = state;
  const online = devices.filter((d) => d.online);

  // Falls back to whichever device is reachable, so a first drop needs no
  // selection step when only one phone is connected.
  const [chosen, setChosen] = useState<string | null>(null);
  const target = online.find((d) => d.id === chosen) ?? online[0] ?? null;

  const [dropping, setDropping] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const live = transfers.filter((t) => LIVE.includes(t.status));
  const past = transfers
    .filter((t) => !LIVE.includes(t.status))
    .sort((a, b) => (b.finishedAt ?? b.startedAt) - (a.finishedAt ?? a.startedAt));

  const send = async (files: FileList | null) => {
    setDropping(false);
    if (!target || !files || files.length === 0) return;
    // The renderer cannot read File.path, so the preload bridge resolves each
    // handle through webUtils before the daemon ever sees a name.
    const paths = Array.from(files).map((file) => window.switchboard.pathForFile(file));
    try {
      await window.switchboard.sendFiles(target.id, paths);
      setError(null);
      refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not queue the files');
    }
  };

  const control = async (transferId: string, action: 'pause' | 'resume' | 'cancel') => {
    await window.switchboard.controlTransfer(transferId, action).catch(() => {});
    refresh();
  };

  return (
    <>
      <Sidebar title="Send to">
        {devices.length === 0 ? (
          <p className="px-2 py-3 text-micro text-ink-faint">Nothing paired yet.</p>
        ) : (
          devices.map((device) => (
            <SidebarItem
              key={device.id}
              label={device.name}
              icon={Smartphone}
              detail={device.online ? 'Online' : 'Offline'}
              selected={target?.id === device.id}
              onSelect={() => device.online && setChosen(device.id)}
            />
          ))
        )}
      </Sidebar>

      <Pane
        title="Files"
        description={
          target ? `Sending to ${target.name}` : 'No device is online to receive files'
        }
      >
        <div className="grid max-w-4xl gap-3">
          <label
            onDragOver={(e) => {
              e.preventDefault();
              if (target) setDropping(true);
            }}
            onDragLeave={() => setDropping(false)}
            onDrop={(e) => {
              e.preventDefault();
              send(e.dataTransfer.files);
            }}
            className={`flex cursor-pointer flex-col items-center justify-center gap-2 rounded-lg border border-dashed px-6 py-10 text-center transition-colors ${
              !target
                ? 'cursor-not-allowed border-edge opacity-50'
                : dropping
                  ? 'border-accent bg-accent/10'
                  : 'border-edge hover:bg-card'
            }`}
          >
            {/* Dropping is a dragging gesture, so the same zone is a picker:
                WCAG 2.2 wants a single-pointer alternative. */}
            <input
              type="file"
              multiple
              disabled={!target}
              className="sr-only"
              onChange={(e) => {
                send(e.target.files);
                e.target.value = '';
              }}
            />
            <Upload aria-hidden="true" className="h-6 w-6 text-ink-faint" />
            <p className="text-tiny font-medium text-ink-dim">
              {target ? `Drop files to send to ${target.name}` : 'Connect a device to send files'}
            </p>
            <p className="text-micro text-ink-faint">
              {target ? 'Or click to choose them' : 'Paired devices appear on the left once online'}
            </p>
          </label>

          {error ? <p className="text-micro text-danger">{error}</p> : null}

          <Card title={`Transferring (${live.length})`}>
            {live.length === 0 ? (
              <p className="text-micro text-ink-faint">Nothing is moving right now.</p>
            ) : (
              <ul className="divide-y divide-edge">
                {live.map((transfer) => (
                  <LiveRow
                    key={transfer.transferId}
                    transfer={transfer}
                    rateUnit={settings.rateUnit}
                    onControl={control}
                  />
                ))}
              </ul>
            )}
          </Card>

          <Card title={`History (${past.length})`}>
            {past.length === 0 ? (
              <EmptyState
                icon={FolderOpen}
                title="No transfers yet"
                hint={`Files received from a phone land in ${settings.downloadDir}. Change that in Settings.`}
              />
            ) : (
              <ul className="divide-y divide-edge">
                {past.map((transfer) => (
                  <HistoryRow key={transfer.transferId} transfer={transfer} />
                ))}
              </ul>
            )}
          </Card>
        </div>
      </Pane>
    </>
  );
}

function LiveRow({
  transfer,
  rateUnit,
  onControl
}: {
  transfer: FileTransfer;
  rateUnit: HostSettings['rateUnit'];
  onControl: (transferId: string, action: 'pause' | 'resume' | 'cancel') => void;
}) {
  const percent = transfer.size > 0 ? Math.round((transfer.transferred / transfer.size) * 100) : 0;
  const paused = transfer.status === 'paused';

  return (
    <li className="py-2.5">
      <div className="flex items-center gap-3">
        <DirectionIcon direction={transfer.direction} />
        <div className="min-w-0 flex-1">
          <p className="truncate text-tiny text-ink">{transfer.name}</p>
          <p className="font-mono text-micro tabular-nums text-ink-faint">
            {/* State is spelled out, never signalled by the bar's colour alone. */}
            {formatSize(transfer.transferred)} of {formatSize(transfer.size)} ·{' '}
            {paused ? 'Paused' : transfer.status === 'pending' ? 'Queued' : formatRate(transfer.bytesPerSec, rateUnit)}
          </p>
        </div>

        <div className="flex shrink-0 items-center gap-1">
          <IconButton
            icon={paused ? Play : Pause}
            label={`${paused ? 'Resume' : 'Pause'} ${transfer.name}`}
            onClick={() => onControl(transfer.transferId, paused ? 'resume' : 'pause')}
          />
          <IconButton
            icon={X}
            label={`Cancel ${transfer.name}`}
            danger
            onClick={() => onControl(transfer.transferId, 'cancel')}
          />
        </div>
      </div>

      <div
        role="progressbar"
        aria-label={`${transfer.name} progress`}
        aria-valuenow={percent}
        className="mt-2 h-1 overflow-hidden rounded bg-raised"
      >
        <div
          className={`h-full rounded transition-[width] ${paused ? 'bg-warn' : 'bg-level'}`}
          style={{ width: `${percent}%` }}
        />
      </div>
    </li>
  );
}

function HistoryRow({ transfer }: { transfer: FileTransfer }) {
  const failed = transfer.status === 'failed';

  return (
    <li className="flex items-center gap-3 py-2.5">
      <DirectionIcon direction={transfer.direction} />
      <div className="min-w-0 flex-1">
        <p className="truncate text-tiny text-ink">{transfer.name}</p>
        <p className="font-mono text-micro tabular-nums text-ink-faint">
          {formatSize(transfer.size)} · {STATUS_LABEL[transfer.status]} ·{' '}
          {formatTime(transfer.finishedAt ?? transfer.startedAt)}
          {transfer.deviceName ? ` · ${transfer.deviceName}` : ''}
        </p>
        {failed && transfer.error ? (
          <p className="truncate text-micro text-danger">{transfer.error}</p>
        ) : null}
      </div>

      {transfer.status === 'completed' ? (
        <IconButton
          icon={FolderOpen}
          label={`Show ${transfer.name} in folder`}
          onClick={() => window.switchboard.revealTransfer(transfer.transferId).catch(() => {})}
        />
      ) : null}
    </li>
  );
}

function DirectionIcon({ direction }: { direction: FileTransfer['direction'] }) {
  // Named from the phone's point of view: an upload arrives at this host.
  const Icon = direction === 'upload' ? ArrowDownToLine : ArrowUpFromLine;
  return (
    <Icon
      aria-label={direction === 'upload' ? 'Received' : 'Sent'}
      className="h-4 w-4 shrink-0 text-ink-faint"
    />
  );
}

function IconButton({
  icon: Icon,
  label,
  danger,
  onClick
}: {
  icon: LucideIcon;
  label: string;
  danger?: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-label={label}
      title={label}
      className={`shrink-0 rounded p-1.5 text-ink-faint transition-colors ${
        danger ? 'hover:bg-danger/15 hover:text-danger' : 'hover:bg-raised hover:text-ink'
      }`}
    >
      <Icon aria-hidden="true" className="h-3.5 w-3.5" />
    </button>
  );
}

const STATUS_LABEL: Record<FileTransfer['status'], string> = {
  pending: 'Queued',
  active: 'Transferring',
  paused: 'Paused',
  completed: 'Completed',
  failed: 'Failed',
  cancelled: 'Cancelled'
};

/** Mbps counts bits, MBps counts bytes; the factor of eight is the whole point. */
function formatRate(bytesPerSec: number, unit: HostSettings['rateUnit']): string {
  return unit === 'Mbps'
    ? `${((bytesPerSec * 8) / 1e6).toFixed(1)} Mb/s`
    : `${(bytesPerSec / 1e6).toFixed(1)} MB/s`;
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  const units = ['KB', 'MB', 'GB', 'TB'];
  let value = bytes / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value.toFixed(value < 10 ? 1 : 0)} ${units[unit]}`;
}

function formatTime(epochMs: number): string {
  const when = new Date(epochMs);
  const sameDay = when.toDateString() === new Date().toDateString();
  return sameDay
    ? when.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
    : when.toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}
