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

export type OpenOutcome = "shared" | "downloaded" | "already" | "cancelled";

const downloadedKeys = new Set<string>();

function stem(filename: string) {
  return filename.replace(/\.epub$/i, "") || "book";
}

function tryCanShare(files: File[]) {
  if (typeof navigator.share !== "function") return false;
  if (typeof navigator.canShare !== "function") return true;
  try {
    return navigator.canShare({ files });
  } catch {
    return false;
  }
}

async function shareFiles(files: File[]): Promise<"shared" | "cancelled" | "skip"> {
  if (!tryCanShare(files)) return "skip";
  try {
    await navigator.share({ files });
    return "shared";
  } catch (err) {
    if (err instanceof DOMException && err.name === "AbortError") return "cancelled";
    return "skip";
  }
}

export async function openInReader(opts: {
  blob: Blob;
  filename: string;
  title: string;
  html?: string;
  viewUrl?: string;
  readerId?: string;
}): Promise<OpenOutcome> {
  const name = stem(opts.filename);
  const epub = new File([opts.blob], `${name}.epub`, { type: "application/epub+zip" });
  const asPdf = new File([opts.blob], `${name}.pdf`, { type: "application/pdf" });

  // Never share a webpage URL. Readers treat that as a link to import, not a book.
  // Chrome's Web Share allowlist is narrow; try EPUB first, then PDF disguise
  // (Librera accepts SEND */* and often sniffs ZIP/EPUB bytes), then HTML.
  const fileAttempts: File[][] = [[epub], [asPdf]];
  if (opts.html) {
    fileAttempts.push([new File([opts.html], `${name}.html`, { type: "text/html" })]);
  }

  for (const files of fileAttempts) {
    const result = await shareFiles(files);
    if (result === "shared" || result === "cancelled") return result;
  }

  const key = `${opts.filename}:${opts.blob.size}`;
  if (downloadedKeys.has(key)) return "already";
  downloadBlob(opts.blob, `${name}.epub`);
  downloadedKeys.add(key);
  return "downloaded";
}
