import { createFileRoute } from "@tanstack/react-router";
import { convertToEpub, type ConvertRequest } from "@/lib/convert/pipeline.server";

export const Route = createFileRoute("/api/convert")({
  server: {
    handlers: {
      POST: async ({ request }) => {
        try {
          const body = (await request.json()) as ConvertRequest;
          const result = await convertToEpub(body);
          return Response.json(result);
        } catch (err) {
          const message = err instanceof Error ? err.message : "转换失败";
          return Response.json({ error: message }, { status: 400 });
        }
      },
    },
  },
});
