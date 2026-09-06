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

export type OpenOutcome = "shared" | "downloaded" | "cancelled";

function asEpubFile(blob: Blob, filename: string) {
  const name = filename.endsWith(".epub") ? filename : `${filename}.epub`;
  return new File([blob], name, { type: "application/epub+zip" });
}

export async function openInReader(opts: {
  blob: Blob;
  filename: string;
  title: string;
  viewUrl?: string;
  readerId?: string;
}): Promise<OpenOutcome> {
  const file = asEpubFile(opts.blob, opts.filename);

  // Chrome's Web Share allowlist does not include EPUB, so canShare({files})
  // is almost always false. Still try — future browsers / other engines may.
  try {
    const filesOnly = { files: [file] };
    if (typeof navigator.share === "function") {
      const allowed =
        typeof navigator.canShare !== "function" || navigator.canShare(filesOnly);
      if (allowed) {
        await navigator.share(filesOnly);
        return "shared";
      }
    }
  } catch (err) {
    if (err instanceof DOMException && err.name === "AbortError") {
      return "cancelled";
    }
  }

  // Android Chrome opens local EPUB via the download bar's "打开".
  // VIEW intents against https://…epub never match KOReader (it listens for
  // content/file, not https), which is why the old button appeared dead.
  downloadBlob(opts.blob, file.name);
  return "downloaded";
}
