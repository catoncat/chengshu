import { createFileRoute } from "@tanstack/react-router";
import {
  convertToFile,
  parseExportFormat,
  type ConvertRequest,
  type ExportFormat,
} from "@/lib/convert/pipeline.server";

type Cached = { bytes: Buffer; filename: string; mime: string; at: number };

const CACHE = new Map<string, Cached>();
const TTL_MS = 30 * 60 * 1000;
const MAX_ENTRIES = 32;

function cacheKey(format: ExportFormat, body: ConvertRequest) {
  return `${format}:${body.url?.trim() || `text:${body.title ?? ""}:${(body.text ?? "").slice(0, 120)}`}`;
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
  const ascii = filename.replace(/[^\w.\-]+/g, "_");
  return `inline; filename="${ascii}"; filename*=UTF-8''${encoded}`;
}

export const Route = createFileRoute("/export")({
  server: {
    handlers: {
      GET: async ({ request }) => {
        try {
          const src = new URL(request.url);
          const format = parseExportFormat(src.searchParams.get("format"));
          const body: ConvertRequest = {
            url: src.searchParams.get("url") ?? undefined,
            text: src.searchParams.get("text") ?? undefined,
            title: src.searchParams.get("title") ?? undefined,
          };
          if (!body.url && !body.text) {
            return Response.json({ error: "缺少链接" }, { status: 400 });
          }
          const key = cacheKey(format, body);
          let cached = take(key);
          if (!cached) {
            const result = await convertToFile(body, format);
            cached = {
              bytes: result.bytes,
              filename: result.filename,
              mime: result.mime,
              at: Date.now(),
            };
            put(key, cached);
          }
          return new Response(new Uint8Array(cached.bytes), {
            headers: {
              "content-type": cached.mime,
              "content-disposition": disposition(cached.filename),
              "cache-control": "private, max-age=1800",
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
