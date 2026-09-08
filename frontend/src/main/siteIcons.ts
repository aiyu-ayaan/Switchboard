// Website icon resolution for deck link keys.
//
// Kept out of the main entry point so the parsing can be exercised without an
// Electron runtime: everything here is plain Node.

/** The site's own icon as a data URI, or '' when it does not offer a usable one. */
export async function resolveSiteIcon(origin: string): Promise<string> {
  // The declared icons come first: they are PNG or SVG at a size meant for a
  // tile, where /favicon.ico is often a 16px ICO that Android cannot decode at
  // all. The phone renders the same data URI this returns.
  const declared = await declaredIconUrls(origin);
  for (const candidate of [...declared, `${origin}/favicon.ico`]) {
    const data = await fetchImageData(candidate);
    if (data) return data;
  }
  return '';
}

/** `<link rel="...icon...">` hrefs from the site's home page, largest first. */
async function declaredIconUrls(origin: string): Promise<string[]> {
  try {
    const response = await fetch(origin, { signal: AbortSignal.timeout(5_000) });
    if (!response.ok) return [];
    // Only the head is needed, and a page body can be megabytes.
    return parseIconLinks((await response.text()).slice(0, 200_000), origin);
  } catch {
    return [];
  }
}

/**
 * The icon links a page declares, best first.
 *
 * Exported for its own test: a site's markup is the one input here that is not
 * ours, and a regex over it is exactly the kind of thing that quietly stops
 * matching.
 */
export function parseIconLinks(html: string, origin: string): string[] {
  const found: Array<{ href: string; size: number }> = [];
  for (const [tag] of html.matchAll(/<link\b[^>]*>/gi)) {
    const rel = /\brel\s*=\s*["']?([^"'>]+)/i.exec(tag)?.[1]?.toLowerCase() ?? '';
    if (!rel.split(/\s+/).some((token) => token === 'icon' || token === 'apple-touch-icon')) continue;
    const href = /\bhref\s*=\s*["']([^"']+)/i.exec(tag)?.[1];
    if (!href) continue;
    const declaredSize = Number(/\bsizes\s*=\s*["']?(\d+)/i.exec(tag)?.[1] ?? 0);
    // An apple-touch-icon has no sizes attribute but is 180px by convention,
    // which is the one worth having on a key.
    found.push({ href, size: declaredSize || (rel.includes('apple') ? 180 : 32) });
  }

  return found
    .sort((a, b) => b.size - a.size)
    .map(({ href }) => {
      try {
        return new URL(href, origin).toString();
      } catch {
        return '';
      }
    })
    .filter(Boolean)
    .slice(0, 4);
}

async function fetchImageData(url: string): Promise<string> {
  try {
    const response = await fetch(url, { signal: AbortSignal.timeout(5_000) });
    if (!response.ok) return '';
    const type = (response.headers.get('content-type') ?? '').split(';')[0].trim().toLowerCase();
    if (!type.startsWith('image/')) return '';
    const bytes = Buffer.from(await response.arrayBuffer());
    // The icon is stored in the deck config and pushed to the phone, so a
    // site serving something enormous must not bloat every config read.
    if (bytes.length === 0 || bytes.length > 256_000) return '';
    return `data:${type};base64,${bytes.toString('base64')}`;
  } catch {
    return '';
  }
}
