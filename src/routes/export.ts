import { createFileRoute } from "@tanstack/react-router";
import {
  convertToFile,
  parseExportFormat,
  type ConvertRequest,
  type ExportFormat,
} from "@/lib/convert/pipeline.server";
import { cacheKey, take, put } from "@/lib/convert/export-cache";
function disposition(filename: string) {
  const encoded = encodeURIComponent(filename);
  const ext = (filename.match(/\.[A-Za-z0-9]+$/) || [""])[0];
  const ascii = filename.replace(/[^\w.\-]+/g, "_").replace(/^_+/, "").replace(/_+/g, "_");
  const fallback = ascii && !ascii.startsWith(".") ? ascii : `book${ext || ".bin"}`;
  return `inline; filename="${fallback}"; filename*=UTF-8''${encoded}`;
}

function fromQuery(src: URL): ConvertRequest {
  return {
    url: src.searchParams.get("url") ?? undefined,
    text: src.searchParams.get("text") ?? undefined,
    title: src.searchParams.get("title") ?? undefined,
  };
}

function fromJson(raw: unknown): ConvertRequest {
  const o = raw && typeof raw === "object" ? (raw as Record<string, unknown>) : {};
  const str = (k: string) => (typeof o[k] === "string" ? (o[k] as string) : undefined);
  return {
    url: str("url"),
    text: str("text"),
    title: str("title"),
    html: str("html"),
    byline: str("byline"),
  };
}

async function respond(format: ExportFormat, body: ConvertRequest) {
  if (!body.url && !body.text && !body.html) {
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
      title: result.title,
      at: Date.now(),
    };
    put(key, cached);
  }
  return new Response(new Uint8Array(cached.bytes), {
    headers: {
      "content-type": cached.mime,
      "content-disposition": disposition(cached.filename),
      "cache-control": "private, no-store, no-cache, max-age=0",
      "x-content-type-options": "nosniff",
      "X-Title": encodeURIComponent(cached.title || cached.filename.replace(/\.[^.]+$/, "")),
    },
  });
}

function fail(err: unknown) {
  const message = err instanceof Error ? err.message : "转换失败";
  return Response.json({ error: message }, { status: 400 });
}

export const Route = createFileRoute("/export")({
  server: {
    handlers: {
      GET: async ({ request }) => {
        try {
          const src = new URL(request.url);
          return await respond(parseExportFormat(src.searchParams.get("format")), fromQuery(src));
        } catch (err) {
          return fail(err);
        }
      },
      POST: async ({ request }) => {
        try {
          const src = new URL(request.url);
          const format = parseExportFormat(src.searchParams.get("format"));
          const body = fromJson(await request.json().catch(() => ({})));
          return await respond(format, body);
        } catch (err) {
          return fail(err);
        }
      },
    },
  },
});
