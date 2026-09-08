import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

export function extractSharedUrl(input: {
  url?: string;
  text?: string;
  title?: string;
}): string | null {
  const chunks = [input.url, input.text, input.title];
  for (const chunk of chunks) {
    if (!chunk) continue;
    const trimmed = chunk.trim();
    const first = trimmed.split(/\s+/)[0] ?? "";
    if (/^https?:\/\//i.test(first)) {
      try {
        const parsed = new URL(first);
        if (parsed.protocol === "http:" || parsed.protocol === "https:") {
          return parsed.href;
        }
      } catch {
        /* ignore */
      }
    }
    const match = trimmed.match(/https?:\/\/[^\s<>"'）】\]]+/i);
    if (match) {
      try {
        return new URL(match[0]).href;
      } catch {
        /* ignore */
      }
    }
  }
  return null;
}

export function hostOf(url: string): string {
  try {
    return new URL(url).hostname.replace(/^www\./, "");
  } catch {
    return "";
  }
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export function formatRelative(ts: number): string {
  const delta = Date.now() - ts;
  const min = Math.round(delta / 60000);
  if (min < 1) return "刚刚";
  if (min < 60) return `${min} 分钟前`;
  const hr = Math.round(min / 60);
  if (hr < 24) return `${hr} 小时前`;
  const day = Math.round(hr / 24);
  return `${day} 天前`;
}

export function bookTitle(title: string | null | undefined, fallback = "未命名"): string {
  const t = (title ?? "").replace(/\s+/g, " ").trim();
  if (!t || /^body$/i.test(t)) return fallback;
  return t;
}

export function sanitizeFilename(title: string): string {
  const cleaned = title
    .replace(/[\\/:*?"<>|]/g, "")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, 72);
  if (!cleaned || /^body$/i.test(cleaned)) return "article";
  return cleaned;
}
