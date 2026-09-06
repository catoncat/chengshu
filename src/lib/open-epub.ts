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

export async function shareEpub(blob: Blob, filename: string, title: string) {
  const file = new File([blob], filename, { type: "application/epub+zip" });
  const payload = { files: [file], title, text: title };
  if (typeof navigator.canShare === "function" && navigator.canShare(payload)) {
    await navigator.share(payload);
    return true;
  }
  if (typeof navigator.share === "function") {
    try {
      await navigator.share(payload);
      return true;
    } catch (err) {
      if (err instanceof DOMException && err.name === "AbortError") return false;
    }
  }
  downloadBlob(blob, filename);
  return false;
}
