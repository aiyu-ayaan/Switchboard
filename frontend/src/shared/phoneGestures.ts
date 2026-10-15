// Turning a mouse on the desktop into touches on the phone.
//
// A mouse gesture is only known once the button comes up: a press that went
// nowhere is a tap or, held, a long press; one that moved is a swipe along the
// path it took, over the time it took -- which is what makes a flick on the
// desktop fling a list on the phone just as far. Kept apart from the component
// so the thresholds can be pinned by a test rather than by feel.
import type { MirrorInput, MirrorPoint } from './types';

/** A press that travelled less than this, in screen pixels, did not move. */
export const TAP_SLOP_PX = 6;
/** Held at least this long without moving, a press is a long press. */
export const LONG_PRESS_MS = 500;
/** A swipe longer than this many points is thinned; the phone needs the shape, not every sample. */
export const MAX_SWIPE_POINTS = 48;

/** One sample: position as fractions of the phone screen, time in ms since the press. */
export interface Sample extends MirrorPoint {
  t: number;
}

/**
 * Classifies a finished press.
 *
 * `travelPx` is the farthest the pointer got from where it went down, in
 * pixels of the desktop window, because "did it move" is a question about the
 * user's hand, not about the phone's resolution.
 */
export function classifyPress(samples: Sample[], durationMs: number, travelPx: number): MirrorInput | null {
  if (samples.length === 0) return null;
  const first = samples[0];
  if (travelPx < TAP_SLOP_PX) {
    return durationMs >= LONG_PRESS_MS
      ? { kind: 'longPress', points: [{ x: first.x, y: first.y }] }
      : { kind: 'tap', points: [{ x: first.x, y: first.y }] };
  }
  return { kind: 'swipe', points: thin(samples, MAX_SWIPE_POINTS), duration: Math.max(1, Math.round(durationMs)) };
}

/** Keeps the first and last samples and an even spread between them. */
export function thin(samples: Sample[], max: number): Sample[] {
  if (samples.length <= max) return samples;
  const out: Sample[] = [];
  const step = (samples.length - 1) / (max - 1);
  for (let i = 0; i < max; i += 1) out.push(samples[Math.round(i * step)]);
  return out;
}

/**
 * Wheel travel to notches. Browsers report pixels for a precision touchpad
 * and lines for a mouse wheel; 100 pixels or 3 lines is one detent. Wheel
 * down is negative, matching scroll.dy's "positive scrolls up".
 */
export function wheelNotches(deltaY: number, deltaMode: number): number {
  const pixels = deltaMode === 1 ? deltaY * (100 / 3) : deltaMode === 2 ? deltaY * 800 : deltaY;
  return -pixels / 100;
}

/** Desktop key names the phone understands, by `KeyboardEvent.key`. */
export const PHONE_KEYS: Record<string, string> = {
  Backspace: 'backspace',
  Delete: 'delete',
  Enter: 'enter',
  Escape: 'escape',
  ArrowLeft: 'left',
  ArrowRight: 'right',
  Home: 'home',
  End: 'end'
};
