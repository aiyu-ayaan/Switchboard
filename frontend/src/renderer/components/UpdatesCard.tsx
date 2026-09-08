// Settings → Updates.
//
// The check and the download happen in the main process without being asked;
// what this draws is what they found, and the one button that is a decision:
// restart and install.
import { AlertTriangle, Check, Download, Loader2, RefreshCw, Rocket } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import type { UpdateChannel, UpdateStatus } from '../../shared/types';
import { Card } from './Shell';
import { ReleaseNotes } from './ReleaseNotes';

const CHANNELS: Array<{ value: UpdateChannel; label: string; hint: string }> = [
  { value: 'stable', label: 'Stable', hint: 'Finished releases only' },
  { value: 'beta', label: 'Beta', hint: 'Release candidates, plus every stable release' },
  { value: 'alpha', label: 'Alpha', hint: 'Everything, the moment it is built' }
];

function megabytes(bytes: number): string {
  return bytes > 0 ? `${(bytes / 1_000_000).toFixed(1)} MB` : '';
}

function checkedAt(at: number | undefined): string {
  if (!at) return 'not yet checked';
  return `last checked ${new Date(at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`;
}

export function UpdatesCard() {
  const [status, setStatus] = useState<UpdateStatus | null>(null);
  const [installError, setInstallError] = useState<string | null>(null);

  useEffect(() => {
    void window.switchboard.updates.status().then(setStatus).catch(() => undefined);
    return window.switchboard.updates.onStatus(setStatus);
  }, []);

  const check = useCallback(() => {
    setInstallError(null);
    void window.switchboard.updates.check().then(setStatus).catch(() => undefined);
  }, []);

  const install = useCallback(async () => {
    setInstallError(null);
    const result = await window.switchboard.updates.install().catch(() => ({
      started: false,
      error: 'The installer could not be started'
    }));
    // A success never returns: the app quits a moment later, which is the
    // update happening.
    if (!result.started) setInstallError(result.error ?? 'The installer could not be started');
  }, []);

  const setChannel = useCallback((channel: UpdateChannel) => {
    setInstallError(null);
    void window.switchboard.updates.setChannel(channel).then(setStatus).catch(() => undefined);
  }, []);

  if (!status) return null;

  const busy = status.phase === 'checking' || status.phase === 'downloading';
  const progress = status.progress ?? 0;

  return (
    <Card
      title="Updates"
      meta={
        <span className="font-mono text-[11px] text-ink-faint">v{status.installedVersion}</span>
      }
    >
      <div className="space-y-3">
        <div className="flex items-center gap-3">
          {status.phase === 'ready' ? (
            <Rocket aria-hidden="true" className="h-4 w-4 shrink-0 text-level" />
          ) : status.phase === 'downloading' ? (
            <Download aria-hidden="true" className="h-4 w-4 shrink-0 text-accent" />
          ) : status.phase === 'error' ? (
            <AlertTriangle aria-hidden="true" className="h-4 w-4 shrink-0 text-danger" />
          ) : status.phase === 'uptodate' ? (
            <Check aria-hidden="true" className="h-4 w-4 shrink-0 text-level" />
          ) : (
            <RefreshCw aria-hidden="true" className="h-4 w-4 shrink-0 text-ink-dim" />
          )}

          <p className="min-w-0 flex-1 text-micro text-ink-dim">
            {status.packaged === false
              ? 'This is a development build. Updates apply to installed copies only.'
              : status.phase === 'checking'
                ? 'Checking GitHub for a newer release…'
                : status.phase === 'downloading'
                  ? `Downloading ${status.update?.version ?? ''}${
                      progress >= 0 ? ` — ${Math.round(progress * 100)}%` : ''
                    }`
                  : status.phase === 'ready'
                    ? `Switchboard ${status.update?.version} is downloaded and ready to install.`
                    : status.phase === 'error'
                      ? (status.error ?? 'The update check failed')
                      : status.phase === 'uptodate'
                        ? `Switchboard is up to date — ${checkedAt(status.lastCheckedAt)}.`
                        : `On the ${status.channel} channel — ${checkedAt(status.lastCheckedAt)}.`}
          </p>

          <button
            type="button"
            onClick={check}
            disabled={busy || status.packaged === false}
            className="shrink-0 rounded border border-edge px-2.5 py-1 text-micro text-ink-dim transition-colors hover:bg-raised hover:text-ink disabled:opacity-40"
          >
            {busy ? (
              <Loader2 aria-hidden="true" className="h-3.5 w-3.5 animate-spin" />
            ) : (
              'Check now'
            )}
          </button>
        </div>

        {status.phase === 'downloading' && progress >= 0 ? (
          <div className="h-1 overflow-hidden rounded-full bg-raised">
            <div
              className="h-full rounded-full bg-accent transition-[width] duration-200"
              style={{ width: `${Math.round(progress * 100)}%` }}
            />
          </div>
        ) : null}

        {status.phase === 'ready' && status.update ? (
          <div className="space-y-2 rounded border border-level/40 bg-level/5 p-3">
            <div className="flex items-center justify-between gap-3">
              <div className="min-w-0">
                <p className="truncate text-tiny font-semibold text-ink">{status.update.name}</p>
                <p className="text-micro text-ink-faint">
                  Replacing v{status.installedVersion}
                  {status.update.size ? ` · ${megabytes(status.update.size)}` : ''}
                </p>
              </div>
              <button
                type="button"
                onClick={install}
                className="shrink-0 rounded border border-level bg-level/15 px-2.5 py-1 text-micro font-medium text-level transition-colors hover:bg-level/25"
              >
                Restart and install
              </button>
            </div>
            {status.update.notes ? (
              <div className="max-h-56 overflow-y-auto border-t border-edge pt-2">
                <ReleaseNotes markdown={status.update.notes} />
              </div>
            ) : null}
          </div>
        ) : null}

        {installError ? <p className="text-micro text-danger">{installError}</p> : null}

        <div className="border-t border-edge pt-3">
          <p className="mb-2 text-micro font-medium text-ink-dim">Release channel</p>
          <div className="flex flex-wrap gap-2">
            {CHANNELS.map(({ value, label, hint }) => (
              <button
                key={value}
                type="button"
                title={hint}
                onClick={() => setChannel(value)}
                aria-pressed={status.channel === value}
                className={`rounded border px-2.5 py-1 text-micro transition-colors ${
                  status.channel === value
                    ? 'border-accent bg-accent/10 text-accent'
                    : 'border-edge text-ink-dim hover:bg-raised hover:text-ink'
                }`}
              >
                {label}
              </button>
            ))}
          </div>
          <p className="mt-2 text-micro text-ink-faint">
            {CHANNELS.find((entry) => entry.value === status.channel)?.hint}. Alpha and beta builds
            have not finished testing.
          </p>
        </div>
      </div>
    </Card>
  );
}
