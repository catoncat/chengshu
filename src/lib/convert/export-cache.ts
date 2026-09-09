import { createHash } from "node:crypto";

export type CachedFile = { bytes: Buffer; filename: string; mime: string; title: string; at: number };
type Body = { url?: string; text?: string; title?: string; html?: string; byline?: string };

const CACHE = new Map<string, CachedFile>();
const TTL_MS = 45 * 1000;
const MAX_BYTES = 32 * 1024 * 1024;

export function cacheKey(format: string, body: Body) {
  const h = createHash("sha256");
  h.update(format);
  h.update("\0");
  h.update(body.url ?? "");
  h.update("\0");
  h.update(body.title ?? "");
  h.update("\0");
  h.update(body.byline ?? "");
  h.update("\0");
  h.update(body.html ?? "");
  h.update("\0");
  h.update(body.text ?? "");
  return h.digest("hex");
}

export function take(key: string): CachedFile | undefined {
  const hit = CACHE.get(key);
  if (!hit) return undefined;
  if (Date.now() - hit.at > TTL_MS) {
    CACHE.delete(key);
    return undefined;
  }
  return hit;
}

export function put(key: string, value: CachedFile) {
  CACHE.set(key, value);
  let total = 0;
  for (const item of CACHE.values()) total += item.bytes.length;
  while (total > MAX_BYTES && CACHE.size > 0) {
    const oldest = CACHE.keys().next().value;
    if (!oldest) break;
    const removed = CACHE.get(oldest);
    CACHE.delete(oldest);
    total -= removed?.bytes.length ?? 0;
  }
}

export function _resetForTests() {
  CACHE.clear();
}
