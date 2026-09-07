// The deck's icon catalogue is declared twice, once per platform, because the
// two UI toolkits have no common icon set. A key stores only a slug, so a slug
// present on one side and missing on the other put a different picture on the
// same key depending on which screen you looked at -- `linkedin` rendered as a
// LinkedIn mark on the desktop and as a fallback glyph on the phone.
//
// Nothing in either compiler can catch that, so it is caught here: the two
// declarations are read as text and their slug sets compared.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const repo = join(here, '..', '..');

const desktopSource = readFileSync(
  join(repo, 'frontend', 'src', 'renderer', 'components', 'DeckView.tsx'),
  'utf8'
);
const mobileSource = readFileSync(
  join(
    repo,
    'mobile', 'app', 'src', 'main', 'java', 'com', 'switchboard', 'app', 'ui', 'DeckScreen.kt'
  ),
  'utf8'
);

/** Slugs declared as keys of the renderer's ICONS_MAP. */
function desktopSlugs() {
  const body = desktopSource.slice(
    desktopSource.indexOf('export const ICONS_MAP'),
    desktopSource.indexOf('const ICON_LABELS')
  );
  return new Set([...body.matchAll(/^\s{2}(\w+):/gm)].map((m) => m[1]));
}

/** Slugs declared as the first argument of each mobile DeckIconItem. */
function mobileSlugs() {
  const body = mobileSource.slice(
    mobileSource.indexOf('private val DECK_ICONS'),
    mobileSource.indexOf('private data class ActionKind')
  );
  return new Set([...body.matchAll(/DeckIconItem\("([^"]+)"/g)].map((m) => m[1]));
}

test('both platforms declare a non-empty icon catalogue', () => {
  assert.ok(desktopSlugs().size > 0, 'no desktop slugs parsed — the ICONS_MAP shape moved');
  assert.ok(mobileSlugs().size > 0, 'no mobile slugs parsed — the DECK_ICONS shape moved');
});

test('every desktop icon slug renders on the phone', () => {
  const mobile = mobileSlugs();
  const missing = [...desktopSlugs()].filter((slug) => !mobile.has(slug));
  assert.deepEqual(missing, [], `slugs the phone cannot draw: ${missing.join(', ')}`);
});

test('the phone offers no icon slug the desktop cannot draw', () => {
  const desktop = desktopSlugs();
  const extra = [...mobileSlugs()].filter((slug) => !desktop.has(slug));
  assert.deepEqual(extra, [], `slugs the desktop cannot draw: ${extra.join(', ')}`);
});

test('the icons the host ships in its default deck exist on both platforms', () => {
  const protocolSource = readFileSync(
    join(repo, 'backend', 'internal', 'protocol', 'protocol.go'),
    'utf8'
  );
  const defaults = protocolSource.slice(protocolSource.indexOf('func DefaultDeckConfig'));
  const used = new Set([...defaults.matchAll(/Icon:\s*"([^"]+)"/g)].map((m) => m[1]));
  assert.ok(used.size > 0, 'no default icons parsed — DefaultDeckConfig moved');

  const desktop = desktopSlugs();
  const mobile = mobileSlugs();
  const unknown = [...used].filter((slug) => !desktop.has(slug) || !mobile.has(slug));
  assert.deepEqual(unknown, [], `default deck uses unrenderable icons: ${unknown.join(', ')}`);
});
