import {
  androidViewIntent,
  isAndroid,
  type ReaderId,
} from "./readers";

export function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 30_000);
}

export type OpenOutcome = "shared" | "intent" | "downloaded" | "cancelled";

export async function openInReader(opts: {
  blob: Blob;
  filename: string;
  title: string;
  viewUrl?: string;
  readerId: ReaderId;
}): Promise<OpenOutcome> {
  const file = new File([opts.blob], opts.filename, {
    type: "application/epub+zip",
  });
  const payload = { files: [file], title: opts.title, text: opts.title };
  const canShareFiles =
    typeof navigator.canShare === "function" && navigator.canShare(payload);

  if (canShareFiles) {
    try {
      await navigator.share(payload);
      return "shared";
    } catch (err) {
      if (err instanceof DOMException && err.name === "AbortError") {
        return "cancelled";
      }
    }
  }

  if (isAndroid() && opts.viewUrl) {
    window.location.href = androidViewIntent(opts.viewUrl, opts.readerId);
    return "intent";
  }

  downloadBlob(opts.blob, opts.filename);
  return "downloaded";
}
