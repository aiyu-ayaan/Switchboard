import { useCallback, useEffect, useRef, useState } from 'react';
import type { LocalState } from '../shared/types';

const POLL_MS = 2000;

/**
 * Polls the daemon for host state.
 *
 * Polling rather than a socket: the local API is on loopback, the payload is
 * a few hundred bytes, and the desktop UI only has to notice changes made
 * elsewhere (a phone moving a slider). Swap in an event stream if the shape of
 * the data grows.
 */
export function useHostState() {
  const [state, setState] = useState<LocalState | null>(null);
  const [error, setError] = useState<string | null>(null);

  // While a slider is being dragged the poll must not overwrite the value the
  // user is holding, or the thumb snaps backwards mid-gesture.
  const paused = useRef(false);

  const refresh = useCallback(async () => {
    if (paused.current) return;
    try {
      setState(await window.switchboard.getState());
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Daemon unreachable');
    }
  }, []);

  useEffect(() => {
    refresh();
    const timer = setInterval(refresh, POLL_MS);
    return () => clearInterval(timer);
  }, [refresh]);

  const setPaused = useCallback((value: boolean) => {
    paused.current = value;
  }, []);

  /** Applies a local edit immediately so the UI tracks the gesture. */
  const patch = useCallback((fn: (draft: LocalState) => LocalState) => {
    setState((current) => (current ? fn(current) : current));
  }, []);

  return { state, error, refresh, setPaused, patch };
}

/**
 * Rate-limits writes to the daemon while keeping the last value.
 *
 * DDC/CI writes travel over the monitor's I2C bus and take tens of
 * milliseconds; a drag emits far more events than that. This sends the leading
 * edge, drops the middle, and always delivers the final value so the panel
 * ends where the user let go.
 */
export function useThrottledCommit<T>(commit: (value: T) => Promise<unknown>, intervalMs = 120) {
  const lastSent = useRef(0);
  const pending = useRef<T | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const flush = useCallback(() => {
    if (pending.current === null) return;
    const value = pending.current;
    pending.current = null;
    lastSent.current = Date.now();
    commit(value).catch(() => {
      /* transient DDC failures are reported by the next poll */
    });
  }, [commit]);

  useEffect(() => () => {
    if (timer.current) clearTimeout(timer.current);
  }, []);

  return useCallback(
    (value: T) => {
      pending.current = value;
      const elapsed = Date.now() - lastSent.current;
      if (elapsed >= intervalMs) {
        flush();
        return;
      }
      if (timer.current) clearTimeout(timer.current);
      timer.current = setTimeout(flush, intervalMs - elapsed);
    },
    [flush, intervalMs]
  );
}
