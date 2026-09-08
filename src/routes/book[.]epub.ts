import { createFileRoute } from "@tanstack/react-router";
import { convertToEpub, type ConvertRequest } from "@/lib/convert/pipeline.server";

type Cached = { bytes: Buffer; filename: string; at: number };

const CACHE = new Map<string, Cached>();
const TTL_MS = 45 * 1000;
const MAX_ENTRIES = 24;

function cacheKey(body: ConvertRequest) {
  return body.url?.trim() || `text:${body.title ?? ""}:${(body.text ?? "").slice(0, 120)}`;
}

function take(key: string): Cached | undefined {
  const hit = CACHE.get(key);
  if (!hit) return undefined;
  if (Date.now() - hit.at > TTL_MS) {
    CACHE.delete(key);
    return undefined;
  }
  return hit;
}

function put(key: string, value: Cached) {
  CACHE.set(key, value);
  if (CACHE.size <= MAX_ENTRIES) return;
  const oldest = CACHE.keys().next().value;
  if (oldest) CACHE.delete(oldest);
}

function disposition(filename: string) {
  const encoded = encodeURIComponent(filename);
  return `inline; filename="book.epub"; filename*=UTF-8''${encoded}`;
}

export const Route = createFileRoute("/book.epub")({
  server: {
    handlers: {
      GET: async ({ request }) => {
        try {
          const src = new URL(request.url);
          const body: ConvertRequest = {
            url: src.searchParams.get("url") ?? undefined,
            text: src.searchParams.get("text") ?? undefined,
            title: src.searchParams.get("title") ?? undefined,
          };
          if (!body.url && !body.text) {
            return Response.json({ error: "缺少链接" }, { status: 400 });
          }
          const key = cacheKey(body);
          let cached = take(key);
          if (!cached) {
            const result = await convertToEpub(body);
            cached = {
              bytes: Buffer.from(result.epubBase64, "base64"),
              filename: result.filename,
              at: Date.now(),
            };
            put(key, cached);
          }
          return new Response(new Uint8Array(cached.bytes), {
            headers: {
              "content-type": "application/epub+zip",
              "content-disposition": disposition(cached.filename),
              "cache-control": "private, no-store, no-cache, max-age=0",
              "x-content-type-options": "nosniff",
            },
          });
        } catch (err) {
          const message = err instanceof Error ? err.message : "转换失败";
          return Response.json({ error: message }, { status: 400 });
        }
      },
    },
  },
});
