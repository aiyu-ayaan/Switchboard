// Run against the compiled shared module: `pnpm build && pnpm test`.
import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  classifyPress,
  thin,
  wheelNotches,
  LONG_PRESS_MS,
  MAX_SWIPE_POINTS
} from '../dist/shared/phoneGestures.js';

const at = (x, y, t = 0) => ({ x, y, t });

test('a quick press that did not move is a tap where it went down', () => {
  assert.deepEqual(classifyPress([at(0.2, 0.3)], 80, 2), {
    kind: 'tap',
    points: [{ x: 0.2, y: 0.3 }]
  });
});

test('a held press that did not move is a long press', () => {
  assert.equal(classifyPress([at(0.5, 0.5)], LONG_PRESS_MS + 10, 3).kind, 'longPress');
});

test('a press that moved is a swipe along its path over its duration', () => {
  const samples = [at(0.5, 0.8, 0), at(0.5, 0.6, 40), at(0.5, 0.3, 90)];
  assert.deepEqual(classifyPress(samples, 90, 200), {
    kind: 'swipe',
    points: samples,
    duration: 90
  });
});

test('nothing pressed is nothing sent', () => {
  assert.equal(classifyPress([], 10, 0), null);
});

test('a long drag is thinned but keeps both ends', () => {
  const samples = Array.from({ length: 500 }, (_, i) => at(i / 500, 0.5, i));
  const out = thin(samples, MAX_SWIPE_POINTS);
  assert.equal(out.length, MAX_SWIPE_POINTS);
  assert.equal(out[0], samples[0]);
  assert.equal(out.at(-1), samples.at(-1));
});

test('wheel down scrolls down, in notches whatever the unit', () => {
  assert.equal(wheelNotches(100, 0), -1);
  assert.equal(wheelNotches(3, 1), -1);
  assert.equal(wheelNotches(-200, 0), 2);
});
