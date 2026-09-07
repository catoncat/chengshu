export function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename.endsWith(".epub") ? filename : `${filename}.epub`;
  anchor.type = "application/epub+zip";
  anchor.rel = "noopener";
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 30_000);
}

export type OpenOutcome = "shared" | "opened" | "downloaded" | "cancelled";

export async function openInReader(opts: {
  blob: Blob;
  filename: string;
  title: string;
  viewUrl?: string;
  readerId?: string;
}): Promise<OpenOutcome> {
  // URL share always summons the Android sheet. File share cannot: Chrome
  // blocks EPUB from Web Share, and <a download> just piles up Downloads.
  if (opts.viewUrl && typeof navigator.share === "function") {
    try {
      const data = { title: opts.title, url: opts.viewUrl, text: opts.title };
      const allowed =
        typeof navigator.canShare !== "function" || navigator.canShare({ url: opts.viewUrl });
      if (allowed) {
        await navigator.share(data);
        return "shared";
      }
    } catch (err) {
      if (err instanceof DOMException && err.name === "AbortError") {
        return "cancelled";
      }
    }
  }

  if (opts.viewUrl) {
    window.open(opts.viewUrl, "_blank", "noopener");
    return "opened";
  }

  downloadBlob(opts.blob, opts.filename);
  return "downloaded";
}
