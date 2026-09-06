import { createFileRoute } from "@tanstack/react-router";
import { convertToEpub, type ConvertRequest } from "@/lib/convert/pipeline.server";

function inlineDisposition(filename: string) {
  const encoded = encodeURIComponent(filename);
  return `inline; filename="book.epub"; filename*=UTF-8''${encoded}`;
}

export const Route = createFileRoute("/api/epub")({
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
          const result = await convertToEpub(body);
          const bytes = Buffer.from(result.epubBase64, "base64");
          return new Response(bytes, {
            headers: {
              "content-type": "application/epub+zip",
              "content-disposition": inlineDisposition(result.filename),
              "cache-control": "private, max-age=120",
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
