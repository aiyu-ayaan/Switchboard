// A GitHub release body, drawn rather than printed.
//
// The body is `### Features` with a list under it, `**bold**`, and the odd
// fenced block. Putting that on screen exactly as it arrives — hashes,
// asterisks and backticks included — is the shape that makes somebody stop
// reading the thing telling them what changed.
//
// Deliberately small: headings at one size, bullets with the marker in a gutter
// of its own, fenced code on its own ground, and the inline marks. A changelog
// is three of those shapes and never a document.
import React from 'react';

type Block =
  | { kind: 'heading'; text: string }
  | { kind: 'bullet'; text: string }
  | { kind: 'code'; text: string }
  | { kind: 'text'; text: string };

/** Splits a release body into the handful of block shapes it actually uses. */
export function parseNotes(markdown: string): Block[] {
  const blocks: Block[] = [];
  const lines = markdown.replace(/\r\n/g, '\n').split('\n');

  let fence: string[] | null = null;
  let paragraph: string[] = [];

  const flush = () => {
    const text = paragraph.join(' ').trim();
    if (text) blocks.push({ kind: 'text', text });
    paragraph = [];
  };

  for (const line of lines) {
    if (/^\s*```/.test(line)) {
      if (fence) {
        blocks.push({ kind: 'code', text: fence.join('\n') });
        fence = null;
      } else {
        flush();
        fence = [];
      }
      continue;
    }
    if (fence) {
      fence.push(line);
      continue;
    }

    const heading = /^\s*#{1,6}\s+(.*)$/.exec(line);
    if (heading) {
      flush();
      blocks.push({ kind: 'heading', text: heading[1].trim() });
      continue;
    }

    const bullet = /^\s*(?:[-*+]|\d+\.)\s+(.*)$/.exec(line);
    if (bullet) {
      flush();
      blocks.push({ kind: 'bullet', text: bullet[1].trim() });
      continue;
    }

    // The rule under a table header would otherwise draw as a row of dashes.
    if (/^\s*\|?[\s:|-]+\|[\s:|-]*$/.test(line) && line.includes('-')) continue;

    if (line.trim() === '') flush();
    else paragraph.push(line.trim());
  }

  if (fence) blocks.push({ kind: 'code', text: fence.join('\n') });
  flush();
  return blocks;
}

/** `**bold**`, `` `code` `` and `_italic_`, which is all a release note uses. */
function inline(text: string): React.ReactNode[] {
  const nodes: React.ReactNode[] = [];
  const pattern = /(\*\*[^*]+\*\*|`[^`]+`|_[^_]+_)/g;
  let last = 0;
  let key = 0;

  for (const match of text.matchAll(pattern)) {
    const at = match.index ?? 0;
    if (at > last) nodes.push(text.slice(last, at));
    const token = match[0];
    if (token.startsWith('**')) {
      nodes.push(
        <strong key={key++} className="font-semibold text-ink">
          {token.slice(2, -2)}
        </strong>
      );
    } else if (token.startsWith('`')) {
      nodes.push(
        <code key={key++} className="rounded bg-raised px-1 font-mono text-[11px] text-accent">
          {token.slice(1, -1)}
        </code>
      );
    } else {
      nodes.push(<em key={key++}>{token.slice(1, -1)}</em>);
    }
    last = at + token.length;
  }
  if (last < text.length) nodes.push(text.slice(last));
  return nodes;
}

export function ReleaseNotes({ markdown }: { markdown: string }) {
  const blocks = React.useMemo(() => parseNotes(markdown), [markdown]);
  if (blocks.length === 0) {
    return <p className="text-micro text-ink-faint">This release came with no notes.</p>;
  }

  return (
    <div className="space-y-1.5">
      {blocks.map((block, index) => {
        if (block.kind === 'heading') {
          return (
            <h4
              key={index}
              className="pt-1.5 text-micro font-semibold uppercase tracking-wider text-ink-dim"
            >
              {block.text}
            </h4>
          );
        }
        if (block.kind === 'bullet') {
          return (
            <div key={index} className="flex gap-2 text-micro text-ink-dim">
              <span aria-hidden="true" className="select-none text-ink-faint">
                •
              </span>
              <span className="min-w-0 flex-1">{inline(block.text)}</span>
            </div>
          );
        }
        if (block.kind === 'code') {
          return (
            <pre
              key={index}
              className="overflow-x-auto rounded border border-edge bg-raised/60 p-2 font-mono text-[11px] text-ink-dim"
            >
              {block.text}
            </pre>
          );
        }
        return (
          <p key={index} className="text-micro text-ink-dim">
            {inline(block.text)}
          </p>
        );
      })}
    </div>
  );
}
