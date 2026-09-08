// The three decisions that install the wrong build silently when they are
// wrong: how versions order across pre-releases, what each channel accepts, and
// which asset a release offers this platform. Everything else in the updater is
// a fetch or a file write; these are the rules.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, readdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  accepts,
  assetFor,
  channelOf,
  compareVersions,
  parseReleases,
  parseVersion,
  pickRelease,
  sweepDownloads,
  versionOfFile
} from '../dist/main/updates.js';

const v = (text) => {
  const parsed = parseVersion(text);
  assert.ok(parsed, `expected ${text} to parse`);
  return parsed;
};

const release = (tag, assets = [`Switchboard-Setup-${tag.replace(/^v/, '')}.exe`]) => ({
  tag_name: tag,
  name: tag,
  body: '',
  published_at: '2026-01-01T00:00:00Z',
  assets: assets.map((name) => ({
    name,
    browser_download_url: `https://example.com/${name}`,
    size: 1
  }))
});

test('a finished release outranks every pre-release of the same version', () => {
  const order = ['1.2.3-alpha.1', '1.2.3-alpha.2', '1.2.3-beta.1', '1.2.3', '1.2.4-alpha.1'];
  for (let i = 1; i < order.length; i += 1) {
    assert.ok(
      compareVersions(v(order[i]), v(order[i - 1])) > 0,
      `${order[i]} should sort above ${order[i - 1]}`
    );
  }
});

test('only the shapes the release workflow produces are understood', () => {
  assert.ok(parseVersion('v1.0.0'));
  assert.ok(parseVersion('1.0.0-BETA.2'));
  assert.equal(parseVersion('1.0'), null);
  assert.equal(parseVersion('1.0.0-rc.1'), null);
  assert.equal(parseVersion('latest'), null);
  assert.equal(parseVersion(undefined), null);
});

test('a channel takes its own builds and everything steadier', () => {
  assert.equal(accepts('stable', v('1.0.0')), true);
  assert.equal(accepts('stable', v('1.0.0-beta.1')), false);
  assert.equal(accepts('beta', v('1.0.0-beta.1')), true);
  assert.equal(accepts('beta', v('1.0.0')), true, 'beta must still see the stable that supersedes it');
  assert.equal(accepts('beta', v('1.0.0-alpha.1')), false);
  assert.equal(accepts('alpha', v('1.0.0-alpha.1')), true);
});

test('the default channel is the one this build came from', () => {
  assert.equal(channelOf('1.0.0-alpha.3'), 'alpha');
  assert.equal(channelOf('1.0.0-beta.1'), 'beta');
  assert.equal(channelOf('1.0.0'), 'stable');
  assert.equal(channelOf('nonsense'), 'stable');
});

test('newer is by version and never by publish date', () => {
  // The stable release was cut first; the alpha that follows it is the newer
  // build, and an alpha install must not be "upgraded" backwards onto it.
  const releases = parseReleases([release('v1.0.0'), release('v1.1.0-alpha.1')]);
  assert.equal(pickRelease(releases, v('1.1.0-alpha.1'), 'alpha'), null);
  assert.equal(pickRelease(releases, v('0.9.0'), 'alpha').tag, 'v1.1.0-alpha.1');
  assert.equal(pickRelease(releases, v('0.9.0'), 'stable').tag, 'v1.0.0');
});

test('drafts and unparseable tags are skipped rather than guessed at', () => {
  const payload = [{ ...release('v2.0.0'), draft: true }, release('nightly'), release('v1.0.0')];
  const releases = parseReleases(payload);
  assert.deepEqual(
    releases.map((entry) => entry.tag),
    ['v1.0.0']
  );
});

test('only the installer is offered, and never to a development run', () => {
  const [withInstaller] = parseReleases([
    release('v1.0.0', ['Switchboard-1.0.0.apk', 'Switchboard-1.0.0.aab', 'Switchboard-Setup-1.0.0.exe'])
  ]);
  assert.equal(assetFor(withInstaller, true).name, 'Switchboard-Setup-1.0.0.exe');
  assert.equal(assetFor(withInstaller, false), null, 'a checkout has no install to replace');

  // A release whose Windows job failed still exists, and offers nothing here.
  const [androidOnly] = parseReleases([release('v1.0.0', ['Switchboard-1.0.0.apk'])]);
  assert.equal(assetFor(androidOnly, true), null);
});

test('a download names its own version, and anything else is not ours', () => {
  assert.equal(versionOfFile('Switchboard-Setup-1.2.3-beta.1.exe'), '1.2.3-beta.1');
  assert.equal(versionOfFile('Switchboard-Setup-1.2.3.exe.part'), null);
  assert.equal(versionOfFile('Switchboard-1.2.3.apk'), null);
  assert.equal(versionOfFile('setup.exe'), null);
});

test('the sweep keeps the newest upgrade and deletes the rest', () => {
  const directory = mkdtempSync(join(tmpdir(), 'switchboard-updates-'));
  try {
    for (const name of [
      'Switchboard-Setup-1.0.0.exe', // already installed
      'Switchboard-Setup-1.1.0.exe',
      'Switchboard-Setup-1.2.0.exe', // the one to keep
      'Switchboard-Setup-1.3.0.exe.part', // a transfer that never finished
      'notes.txt'
    ]) {
      writeFileSync(join(directory, name), 'x');
    }

    const pending = sweepDownloads(directory, '1.0.0');
    assert.equal(pending.version, '1.2.0');
    assert.deepEqual(readdirSync(directory), ['Switchboard-Setup-1.2.0.exe']);

    // Once that version is running there is nothing left worth keeping.
    assert.equal(sweepDownloads(directory, '1.2.0'), null);
    assert.deepEqual(readdirSync(directory), []);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test('a missing directory is not an error', () => {
  assert.equal(sweepDownloads(join(tmpdir(), 'switchboard-nope-does-not-exist'), '1.0.0'), null);
});
