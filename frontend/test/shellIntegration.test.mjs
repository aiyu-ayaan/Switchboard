// Run against the compiled main process: `pnpm build && pnpm test`.
//
// node:test and node:assert only — this pins one pure function, and a test
// framework would be a larger addition to the repo than the thing it checks.
import { test } from 'node:test';
import assert from 'node:assert/strict';

import { pathsFromArgv } from '../dist/main/shellIntegration.js';

test('a launch with no verb carries no files', () => {
  assert.deepEqual(pathsFromArgv(['C:\App\Switchboard.exe']), []);
});

test('a cold start from Explorer keeps the exe out of the file list', () => {
  assert.deepEqual(
    pathsFromArgv(['C:\App\Switchboard.exe', '--send', 'C:\Users\a\report.pdf']),
    ['C:\Users\a\report.pdf']
  );
});

test('the Player multi-select model hands over the whole selection at once', () => {
  assert.deepEqual(
    pathsFromArgv(['Switchboard.exe', '--send', 'a.txt', 'b.txt', 'c.txt']),
    ['a.txt', 'b.txt', 'c.txt']
  );
});

test("Chromium's own switches are not mistaken for files", () => {
  // A second instance's argv arrives with whatever Electron appended to it.
  assert.deepEqual(
    pathsFromArgv(['Switchboard.exe', '--allow-file-access-from-files', '--send', 'a.txt']),
    ['a.txt']
  );
  assert.deepEqual(
    pathsFromArgv(['Switchboard.exe', '--send', 'a.txt', '--no-sandbox']),
    ['a.txt']
  );
});

test('a path with spaces survives as one argument', () => {
  assert.deepEqual(pathsFromArgv(['exe', '--send', 'C:\My Files\a b.txt']), [
    'C:\My Files\a b.txt'
  ]);
});
