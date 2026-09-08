// A deck key that opens a website wears the site's own logo, which means
// reading markup we do not control. The regexes below are the whole mechanism,
// so they are pinned here: a site that declares its icon in a shape these miss
// is a key that falls back to a generic globe.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseIconLinks } from '../dist/main/siteIcons.js';

test('the largest declared icon wins, and relative hrefs resolve', () => {
  const html = `
    <link rel="stylesheet" href="/app.css">
    <link rel="icon" sizes="32x32" href="/favicon-32.png">
    <link rel="icon" sizes="192x192" href="/icons/large.png">
    <link rel="apple-touch-icon" href="touch.png">
  `;
  assert.deepEqual(parseIconLinks(html, 'https://example.com'), [
    'https://example.com/icons/large.png',
    'https://example.com/touch.png',
    'https://example.com/favicon-32.png'
  ]);
});

test('attribute order, quoting and multi-value rel are all real markup', () => {
  const html = `
    <link href='/a.png' rel='shortcut icon'>
    <link REL="ICON" HREF="/b.svg" type="image/svg+xml" sizes="any">
  `;
  assert.deepEqual(parseIconLinks(html, 'https://example.com'), [
    'https://example.com/a.png',
    'https://example.com/b.svg'
  ]);
});

test('links that are not icons, and icons with no href, are left alone', () => {
  const html = `
    <link rel="preconnect" href="https://cdn.example.com">
    <link rel="canonical" href="/home">
    <link rel="icon">
  `;
  assert.deepEqual(parseIconLinks(html, 'https://example.com'), []);
});

test('an absolute href on another host is kept as declared', () => {
  const html = '<link rel="icon" href="https://cdn.example.net/logo.png">';
  assert.deepEqual(parseIconLinks(html, 'https://example.com'), [
    'https://cdn.example.net/logo.png'
  ]);
});
