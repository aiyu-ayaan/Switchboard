import { ArrowLeft, Bell, Circle, Square } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { useEffect, useRef } from 'react';
import type { KeyboardEvent, PointerEvent, WheelEvent } from 'react';
import type { MirrorInput, MirrorNav } from '../../shared/types';
import { classifyPress, PHONE_KEYS, wheelNotches } from '../../shared/phoneGestures';
import type { Sample } from '../../shared/phoneGestures';
import { codecFromAnnexB } from '../h264';

const send = (input: MirrorInput) => {
  void window.switchboard.phone.input(input).catch(() => undefined);
};

/**
 * Decodes the phone's H.264 into a canvas.
 *
 * Same approach as the camera view -- `VideoDecoder` on the GPU, frames painted
 * to a canvas, nothing through React state -- but without the camera's
 * rotation and mirroring: a screen arrives the right way up.
 */
function usePhoneVideo(canvasRef: React.RefObject<HTMLCanvasElement | null>) {
  useEffect(() => {
    let disposed = false;
    let decoder: VideoDecoder | null = null;
    let configured = false;
    let needsKey = true;

    const reset = () => {
      needsKey = true;
      configured = false;
      if (decoder && decoder.state !== 'closed') {
        try {
          decoder.close();
        } catch {
          // already closed by the error that brought us here
        }
      }
      decoder = null;
    };

    const stop = window.switchboard.phone.onVideo((unit) => {
      if (disposed || typeof VideoDecoder === 'undefined') return;
      if (unit.key) needsKey = false;
      else if (needsKey) return;

      if (!decoder) {
        decoder = new VideoDecoder({
          output: (frame) => {
            try {
              const canvas = canvasRef.current;
              if (canvas && !disposed) {
                if (canvas.width !== frame.displayWidth || canvas.height !== frame.displayHeight) {
                  canvas.width = frame.displayWidth;
                  canvas.height = frame.displayHeight;
                }
                canvas.getContext('2d')?.drawImage(frame, 0, 0);
              }
            } finally {
              frame.close();
            }
          },
          error: reset
        });
      }
      const bytes = new Uint8Array(unit.data);
      // A rotation changes the stream's size; the phone sends a fresh SPS
      // with the next keyframe, and the decoder has to be configured for it.
      if (!configured || unit.key) {
        try {
          decoder.configure({ codec: codecFromAnnexB(bytes) ?? 'avc1.42E01E', optimizeForLatency: true });
          configured = true;
        } catch {
          reset();
          return;
        }
      }
      // Falling behind is fixed by skipping to the next keyframe, never by
      // queueing: a backlog here is latency that only grows.
      if (!unit.key && decoder.decodeQueueSize > 6) {
        needsKey = true;
        return;
      }
      try {
        decoder.decode(
          new EncodedVideoChunk({ type: unit.key ? 'key' : 'delta', timestamp: unit.timestamp, data: bytes })
        );
      } catch {
        reset();
      }
    });

    return () => {
      disposed = true;
      reset();
      stop();
    };
  }, [canvasRef]);
}

/**
 * The phone's screen, live, driven by the mouse and keyboard.
 *
 * Left button is the finger: click taps, hold long-presses, drag swipes along
 * the path it took. Right click is Back and middle click is Home, as in
 * scrcpy. The wheel scrolls. With the picture focused, typing types into the
 * phone's focused field and Ctrl+V pastes the desktop clipboard there.
 */
export function PhoneMirror({ live, control, className = '' }: { live: boolean; control: boolean; className?: string }) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const press = useRef<{ samples: Sample[]; start: number; x: number; y: number; travel: number } | null>(null);
  const wheel = useRef<{ notches: number; x: number; y: number; timer: number | null }>({
    notches: 0,
    x: 0.5,
    y: 0.5,
    timer: null
  });
  const typed = useRef<{ text: string; timer: number | null }>({ text: '', timer: null });

  usePhoneVideo(canvasRef);

  const fraction = (event: { clientX: number; clientY: number }) => {
    const rect = canvasRef.current!.getBoundingClientRect();
    return {
      x: Math.min(1, Math.max(0, (event.clientX - rect.left) / rect.width)),
      y: Math.min(1, Math.max(0, (event.clientY - rect.top) / rect.height))
    };
  };

  const onPointerDown = (event: PointerEvent<HTMLCanvasElement>) => {
    event.currentTarget.focus();
    if (event.button === 2) {
      send({ kind: 'nav', nav: 'back' });
      return;
    }
    if (event.button === 1) {
      event.preventDefault();
      send({ kind: 'nav', nav: 'home' });
      return;
    }
    if (event.button !== 0) return;
    event.currentTarget.setPointerCapture(event.pointerId);
    const { x, y } = fraction(event);
    press.current = { samples: [{ x, y, t: 0 }], start: performance.now(), x: event.clientX, y: event.clientY, travel: 0 };
  };

  const onPointerMove = (event: PointerEvent<HTMLCanvasElement>) => {
    const p = press.current;
    if (!p) return;
    const t = performance.now() - p.start;
    p.travel = Math.max(p.travel, Math.hypot(event.clientX - p.x, event.clientY - p.y));
    const last = p.samples[p.samples.length - 1];
    if (t - last.t >= 12) p.samples.push({ ...fraction(event), t: Math.round(t) });
  };

  const onPointerUp = (event: PointerEvent<HTMLCanvasElement>) => {
    const p = press.current;
    press.current = null;
    if (!p || event.button !== 0) return;
    const duration = performance.now() - p.start;
    const end = { ...fraction(event), t: Math.round(duration) };
    if (p.travel >= 1) p.samples.push(end);
    const input = classifyPress(p.samples, duration, p.travel);
    if (input) send(input);
  };

  const onWheel = (event: WheelEvent<HTMLCanvasElement>) => {
    const w = wheel.current;
    w.notches += wheelNotches(event.deltaY, event.deltaMode);
    Object.assign(w, fraction(event));
    // Wheel events arrive per detent and faster than the phone can perform a
    // swipe each; a swipe dispatched over an unfinished one cancels it. So
    // they are gathered and sent as one scroll every 120 ms.
    if (w.timer === null) {
      w.timer = window.setTimeout(() => {
        const notches = w.notches;
        w.notches = 0;
        w.timer = null;
        if (Math.abs(notches) > 0.01) send({ kind: 'scroll', dy: notches, points: [{ x: w.x, y: w.y }] });
      }, 120);
    }
  };

  const flushText = () => {
    const t = typed.current;
    if (t.timer !== null) window.clearTimeout(t.timer);
    t.timer = null;
    if (t.text) send({ kind: 'text', text: t.text });
    t.text = '';
  };

  const onKeyDown = (event: KeyboardEvent<HTMLCanvasElement>) => {
    if (event.ctrlKey || event.metaKey || event.altKey) return; // paste arrives as its own event
    const named = PHONE_KEYS[event.key];
    if (named) {
      event.preventDefault();
      flushText();
      send({ kind: 'key', key: named });
      return;
    }
    if (event.key.length === 1) {
      event.preventDefault();
      // Batched so a fast typist sends words, not one round trip a letter.
      typed.current.text += event.key;
      if (typed.current.timer === null) typed.current.timer = window.setTimeout(flushText, 40);
    }
  };

  return (
    <canvas
      ref={canvasRef}
      tabIndex={0}
      aria-label="Phone screen"
      title={control ? 'Click to tap · drag to swipe · right-click: Back · middle-click: Home' : undefined}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
      onPointerCancel={() => (press.current = null)}
      onWheel={onWheel}
      onKeyDown={onKeyDown}
      onPaste={(event) => {
        const text = event.clipboardData.getData('text');
        if (text) send({ kind: 'text', text });
      }}
      onContextMenu={(event) => event.preventDefault()}
      className={`max-h-full max-w-full rounded-[18px] bg-black outline-none ring-accent-dim focus:ring-2 ${
        live ? '' : 'invisible'
      } ${control ? 'cursor-pointer' : 'cursor-default'} ${className}`}
    />
  );
}

const NAV: Array<{ nav: MirrorNav; label: string; icon: LucideIcon }> = [
  { nav: 'back', label: 'Back', icon: ArrowLeft },
  { nav: 'home', label: 'Home', icon: Circle },
  { nav: 'recents', label: 'Recent apps', icon: Square },
  { nav: 'notifications', label: 'Notifications', icon: Bell }
];

/** The phone's own system buttons. */
export function PhoneNavBar({ disabled }: { disabled?: boolean }) {
  return (
    <div className="flex items-center justify-center gap-1">
      {NAV.map(({ nav, label, icon: Icon }) => (
        <button
          key={nav}
          type="button"
          title={label}
          aria-label={label}
          disabled={disabled}
          onClick={() => send({ kind: 'nav', nav })}
          className="flex h-8 w-10 items-center justify-center rounded text-ink-dim transition-colors hover:bg-raised hover:text-ink disabled:opacity-40"
        >
          <Icon aria-hidden="true" className="h-3.5 w-3.5" />
        </button>
      ))}
    </div>
  );
}
