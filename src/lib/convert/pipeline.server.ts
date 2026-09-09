import { Readability } from "@mozilla/readability";
import { parseHTML } from "linkedom";
import { marked } from "marked";
import { assertPublicHttpUrl, isPublicHttpUrl, fetchPublic } from "./ssrf";
import { sanitizeFilename, bookTitle } from "@/lib/utils";
import { buildEpub } from "./epub-pack";
import { articleFromUnknown, isThinHtml, jsonCandidateUrls } from "./json-article";
import { Defuddle } from "defuddle/node";
import { blockify } from "./article-html";

const UA =
  "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36";

const MAX_IMAGES = 12;
const MAX_IMAGE_BYTES = 700_000;
const MAX_HTML_BYTES = 2_500_000;

export type ConvertRequest = {
  url?: string;
  text?: string;
  title?: string;
  html?: string;
  byline?: string;
};

export type ConvertResult = {
  title: string;
  byline: string;
  siteName: string;
  excerpt: string;
  sourceUrl: string;
  filename: string;
  imageCount: number;
  charCount: number;
  epubBase64: string;
  html: string;
  size: number;
};

export const EXPORT_FORMATS = ["epub", "pdf", "html", "md", "txt"] as const;
export type ExportFormat = (typeof EXPORT_FORMATS)[number];

export type ConvertedFile = {
  bytes: Buffer;
  filename: string;
  mime: string;
  title: string;
};

const MIME: Record<ExportFormat, string> = {
  epub: "application/epub+zip",
  pdf: "application/pdf",
  html: "text/html; charset=utf-8",
  md: "text/markdown; charset=utf-8",
  txt: "text/plain; charset=utf-8",
};

export function parseExportFormat(raw: string | null | undefined): ExportFormat {
  if (raw === "html" || raw === "md" || raw === "txt" || raw === "epub" || raw === "pdf") return raw;
  return "epub";
}

type Extracted = {
  title: string;
  byline: string;
  siteName: string;
  excerpt: string;
  content: string;
  sourceUrl: string;
};

type EmbeddedImage = {
  id: string;
  href: string;
  mediaType: string;
  data: Uint8Array;
};

export async function convertToEpub(input: ConvertRequest): Promise<ConvertResult> {
  const extracted = await extract(input);
  if (!extracted.content.replace(/<[^>]+>/g, "").trim()) {
    throw new Error("没提取到正文，换一篇或把全文贴进来");
  }

  const images = await embedImages(extracted.content, extracted.sourceUrl);
  const xhtml = toXhtml(extracted.content, images.rewritten);
  const epub = await buildEpub({
    ...extracted,
    xhtml,
    images: images.files,
  });
  const filename = `${sanitizeFilename(extracted.title)}.epub`;
  const epubBase64 = Buffer.from(epub).toString("base64");
  return {
    title: extracted.title,
    byline: extracted.byline,
    siteName: extracted.siteName,
    excerpt: extracted.excerpt,
    sourceUrl: extracted.sourceUrl,
    filename,
    imageCount: images.files.length,
    charCount: extracted.content.replace(/<[^>]+>/g, "").length,
    epubBase64,
    html: toShareHtml(extracted),
    size: epub.byteLength,
  };
}

export async function convertToFile(
  input: ConvertRequest,
  format: ExportFormat,
): Promise<ConvertedFile> {
  if (format === "epub") {
    const result = await convertToEpub(input);
    return {
      bytes: Buffer.from(result.epubBase64, "base64"),
      filename: result.filename,
      mime: MIME.epub,
      title: result.title,
    };
  }

  const extracted = await extract(input);
  const plain = extracted.content.replace(/<[^>]+>/g, "").trim();
  if (!plain) throw new Error("没提取到正文，换一篇或把全文贴进来");

  const stem = sanitizeFilename(extracted.title);
  if (format === "html") {
    const html = toShareHtml(extracted);
    return {
      bytes: Buffer.from(html, "utf8"),
      filename: `${stem}.html`,
      mime: MIME.html,
      title: extracted.title,
    };
  }
  if (format === "pdf") {
    const images = await embedImages(extracted.content, extracted.sourceUrl);
    let html = extracted.content;
    for (const [src, img] of images.rewritten) html = html.split(src).join(img.href);
    const { buildPdf } = await import("./pdf-pack");
    const pdf = await buildPdf({
      title: extracted.title,
      byline: extracted.byline,
      siteName: extracted.siteName,
      excerpt: extracted.excerpt,
      sourceUrl: extracted.sourceUrl,
      html,
      images: images.files,
    });
    return {
      bytes: Buffer.from(pdf),
      filename: `${stem}.pdf`,
      mime: MIME.pdf,
      title: extracted.title,
    };
  }
  if (format === "md") {
    const md = toMarkdown(extracted);
    return {
      bytes: Buffer.from(md, "utf8"),
      filename: `${stem}.md`,
      mime: MIME.md,
      title: extracted.title,
    };
  }
  const txt = toPlainText(extracted);
  return {
    bytes: Buffer.from(txt, "utf8"),
    filename: `${stem}.txt`,
    mime: MIME.txt,
    title: extracted.title,
  };
}

async function extract(input: ConvertRequest): Promise<Extracted> {
  const html = input.html?.trim();
  if (html) {
    let sourceUrl = "";
    if (input.url?.trim()) sourceUrl = assertPublicHttpUrl(input.url.trim()).href;
    return {
      title: bookTitle(input.title, hostName(sourceUrl) || "未命名"),
      byline: (input.byline ?? "").trim(),
      siteName: hostName(sourceUrl),
      excerpt: html.replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim().slice(0, 220),
      content: html,
      sourceUrl,
    };
  }
  const url = input.url?.trim();
  if (url) return extractFromUrl(url);
  return extractFromText(input.text?.trim() ?? "", input.title);
}

async function extractFromUrl(rawUrl: string): Promise<Extracted> {
  const parsed = assertPublicHttpUrl(rawUrl);
  const sourceUrl = parsed.href;
  const html = await fetchHtml(sourceUrl);
  const thin = isThinHtml(html);
  const defuddled = await defuddleExtract(html, sourceUrl);
  const readable = readabilityExtract(html, sourceUrl);
  let best = longerExtract(defuddled, readable);
  if (thin || textLen(best) < 180) {
    const fromJson = await jsonApiExtract(sourceUrl);
    best = longerExtract(best, fromJson);
  }
  if (textLen(best) >= 180) return best!;

  const fromJina = await jinaExtract(sourceUrl);
  best = longerExtract(best, fromJina);
  if (best && textLen(best) >= 80) return best;
  throw new Error("这个页面拦了抓取，或正文太短");
}

function textLen(extracted: Extracted | null | undefined): number {
  if (!extracted?.content) return 0;
  return extracted.content.replace(/<[^>]+>/g, "").replace(/\s+/g, " ").trim().length;
}

function longerExtract(a: Extracted | null | undefined, b: Extracted | null | undefined): Extracted | null {
  if (!a) return b ?? null;
  if (!b) return a;
  return textLen(b) > textLen(a) ? b : a;
}

function extractFromText(text: string, title?: string): Extracted {
  if (!text) throw new Error("没有可转换的内容");
  const paragraphs = text
    .split(/\n{2,}/)
    .map((p) => p.trim())
    .filter(Boolean)
    .map((p) => `<p>${escapeXml(p).replaceAll("\n", "<br />")}</p>`)
    .join("");
  const heading = bookTitle(title, text.slice(0, 32) || "摘录");
  return {
    title: heading,
    byline: "",
    siteName: "",
    excerpt: text.slice(0, 140),
    content: paragraphs,
    sourceUrl: "",
  };
}

async function fetchHtml(url: string): Promise<string> {
  const res = await fetchPublic(url, {
    signal: AbortSignal.timeout(16000),
    maxBytes: MAX_HTML_BYTES,
    headers: {
      "User-Agent": UA,
      Accept: "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8",
      "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.6",
      "Cache-Control": "no-cache",
      Pragma: "no-cache",
    },
  });
  if (!res.ok) throw new Error(`抓取失败（${res.status}）`);
  const buf = Buffer.from(await res.arrayBuffer());
  if (buf.byteLength > MAX_HTML_BYTES) throw new Error("页面太大，换一篇短一点的");
  const headerCharset = charsetFromHeader(res.headers.get("content-type"));
  let html = buf.toString("utf8");
  const metaCharset = charsetFromMeta(html);
  const charset = (headerCharset || metaCharset || "utf-8").toLowerCase();
  if (charset !== "utf-8" && charset !== "utf8" && charset !== "iso-8859-1") {
    html = buf.toString("utf8");
  }
  return html;
}

function charsetFromHeader(ct: string | null): string | null {
  const match = ct?.match(/charset=([^;]+)/i);
  return match?.[1]?.trim() ?? null;
}

function charsetFromMeta(html: string): string | null {
  const match =
    html.match(/charset=["']?([\w-]+)/i) ||
    html.match(/charset=([\w-]+)/i);
  return match?.[1] ?? null;
}

async function defuddleExtract(html: string, url: string): Promise<Extracted | null> {
  try {
    const withBase = injectBase(html, url);
    const { document } = parseHTML(withBase);
    const parsed = await Defuddle(document as unknown as Document, url, { useAsync: false });
    if (!parsed?.content) return null;
    const title = bookTitle(parsed.title, hostName(url) || "未命名");
    return {
      title,
      byline: (parsed.author ?? "").trim(),
      siteName: (parsed.site || hostName(url)).trim(),
      excerpt: (parsed.description || "").trim().slice(0, 220),
      content: parsed.content,
      sourceUrl: url,
    };
  } catch {
    return null;
  }
}

async function jsonApiExtract(pageUrl: string): Promise<Extracted | null> {
  const candidates = jsonCandidateUrls(pageUrl);
  for (const href of candidates) {
    try {
      if (!isPublicHttpUrl(href)) continue;
      const res = await fetchPublic(href, {
        signal: AbortSignal.timeout(8000),
        maxBytes: MAX_HTML_BYTES,
        headers: {
          "User-Agent": UA,
          Accept: "application/json,text/json;q=0.9,*/*;q=0.1",
        },
      });
      if (!res.ok) continue;
      const buf = Buffer.from(await res.arrayBuffer());
      if (buf.byteLength < 40 || buf.byteLength > MAX_HTML_BYTES) continue;
      const json = JSON.parse(buf.toString("utf8")) as unknown;
      const draft = articleFromUnknown(json, pageUrl);
      if (!draft || draft.content.replace(/<[^>]+>/g, "").trim().length < 80) continue;
      return { ...draft, sourceUrl: pageUrl };
    } catch {
      /* try next candidate */
    }
  }
  return null;
}

function readabilityExtract(html: string, url: string): Extracted | null {
  try {
    const withBase = injectBase(html, url);
    const window = parseHTML(withBase);
    const document = window.document;
    const parsed = new Readability(document as unknown as Document, {
      charThreshold: 80,
      nbTopCandidates: 8,
    }).parse();
    if (!parsed?.content) return null;
    const title = bookTitle(parsed.title || document.title, hostName(url) || "未命名");
    return {
      title,
      byline: (parsed.byline ?? "").trim(),
      siteName: (parsed.siteName ?? hostName(url)).trim(),
      excerpt: (parsed.excerpt ?? "").trim().slice(0, 220),
      content: parsed.content,
      sourceUrl: url,
    };
  } catch {
    return null;
  }
}

async function jinaExtract(url: string): Promise<Extracted | null> {
  try {
    const res = await fetch(`https://r.jina.ai/${url}`, {
      signal: AbortSignal.timeout(18000),
      headers: {
        Accept: "text/plain",
        "User-Agent": UA,
        "X-Return-Format": "markdown",
      },
    });
    if (!res.ok) return null;
    const markdown = (await res.text()).trim();
    if (markdown.length < 80) return null;
    const titleMatch = markdown.match(/^#\s+(.+)$/m);
    const title = bookTitle(titleMatch?.[1], hostName(url) || "未命名");
    const html = await marked.parse(markdown, { gfm: true, breaks: true });
    return {
      title,
      byline: "",
      siteName: hostName(url),
      excerpt: markdown.replace(/^#.+\n/, "").slice(0, 220),
      content: html,
      sourceUrl: url,
    };
  } catch {
    return null;
  }
}

function injectBase(html: string, url: string): string {
  const tag = `<base href="${escapeXml(url)}">`;
  if (/<head[^>]*>/i.test(html)) {
    return html.replace(/<head[^>]*>/i, (open) => `${open}${tag}`);
  }
  return `<!doctype html><html><head>${tag}</head><body>${html}</body></html>`;
}

function hostName(url: string): string {
  try {
    return new URL(url).hostname.replace(/^www\./, "");
  } catch {
    return "";
  }
}

async function embedImages(
  html: string,
  pageUrl: string,
): Promise<{ rewritten: Map<string, EmbeddedImage>; files: EmbeddedImage[] }> {
  const srcs = collectImageSrcs(html).slice(0, MAX_IMAGES);
  const files: EmbeddedImage[] = [];
  const rewritten = new Map<string, EmbeddedImage>();
  const jobs = srcs.map(async (src, index) => {
    const abs = resolveUrl(src, pageUrl);
    if (!abs || !isPublicHttpUrl(abs)) return;
    try {
      const res = await fetch(abs, {
        redirect: "follow",
        signal: AbortSignal.timeout(8000),
        headers: {
          "User-Agent": UA,
          Accept: "image/avif,image/webp,image/*,*/*;q=0.8",
          Referer: pageUrl || abs,
        },
      });
      if (!res.ok) return;
      const buf = new Uint8Array(await res.arrayBuffer());
      if (buf.byteLength < 80 || buf.byteLength > MAX_IMAGE_BYTES) return;
      const mediaType = sniffImageType(res.headers.get("content-type"), buf, abs);
      if (!mediaType) return;
      const ext = extFromType(mediaType);
      const id = `img-${index + 1}`;
      const image: EmbeddedImage = {
        id,
        href: `images/${id}.${ext}`,
        mediaType,
        data: buf,
      };
      rewritten.set(src, image);
      rewritten.set(abs, image);
      files.push(image);
    } catch {
      /* skip broken images */
    }
  });
  await Promise.all(jobs);
  return { rewritten, files };
}

function collectImageSrcs(html: string): string[] {
  const found: string[] = [];
  const re = /<img\b[^>]*\bsrc=["']([^"']+)["'][^>]*>/gi;
  let match: RegExpExecArray | null;
  while ((match = re.exec(html))) {
    const src = match[1];
    if (src && !src.startsWith("data:") && !found.includes(src)) found.push(src);
  }
  return found;
}

function resolveUrl(src: string, base: string): string | null {
  try {
    return new URL(src, base || undefined).href;
  } catch {
    return null;
  }
}

function sniffImageType(
  header: string | null,
  buf: Uint8Array,
  url: string,
): string | null {
  const ct = (header ?? "").split(";")[0]?.trim().toLowerCase() ?? "";
  if (ct.startsWith("image/") && ct !== "image/svg+xml") return ct;
  if (buf[0] === 0xff && buf[1] === 0xd8) return "image/jpeg";
  if (buf[0] === 0x89 && buf[1] === 0x50) return "image/png";
  if (buf[0] === 0x47 && buf[1] === 0x49) return "image/gif";
  if (buf[0] === 0x52 && buf[1] === 0x49) return "image/webp";
  const lower = url.toLowerCase();
  if (lower.endsWith(".png")) return "image/png";
  if (lower.endsWith(".webp")) return "image/webp";
  if (lower.endsWith(".gif")) return "image/gif";
  if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
  return null;
}

function extFromType(type: string): string {
  if (type.includes("png")) return "png";
  if (type.includes("webp")) return "webp";
  if (type.includes("gif")) return "gif";
  return "jpg";
}

const ALLOWED = new Set([
  "p",
  "h1",
  "h2",
  "h3",
  "h4",
  "h5",
  "h6",
  "div",
  "span",
  "br",
  "hr",
  "em",
  "i",
  "strong",
  "b",
  "blockquote",
  "ul",
  "ol",
  "li",
  "a",
  "img",
  "figure",
  "figcaption",
  "pre",
  "code",
  "table",
  "thead",
  "tbody",
  "tr",
  "th",
  "td",
  "sup",
  "sub",
  "small",
]);

const VOID = new Set(["br", "hr", "img"]);

function toShareHtml(extracted: Extracted): string {
  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1"/>
<title>${escapeXml(extracted.title)}</title>
<style>
body{font:18px/1.7 system-ui,sans-serif;max-width:40rem;margin:1.5rem auto;padding:0 1.25rem;color:#171412;background:#f6f1e8}
h1{font-size:1.6rem;line-height:1.3;margin:0 0 .75rem}
.meta{color:#6b645c;font-size:.9rem;margin-bottom:1.25rem}
img{max-width:100%;height:auto}
a{color:#8a3b12}
</style>
</head>
<body>
<h1>${escapeXml(extracted.title)}</h1>
${extracted.byline || extracted.siteName ? `<p class="meta">${escapeXml([extracted.byline, extracted.siteName].filter(Boolean).join(" · "))}</p>` : ""}
${extracted.content}
</body>
</html>`;
}

function toMarkdown(extracted: Extracted): string {
  const bits = [
    `# ${extracted.title}`,
    extracted.byline || extracted.siteName
      ? `*${[extracted.byline, extracted.siteName].filter(Boolean).join(" · ")}*`
      : "",
    extracted.sourceUrl ? `来源：${extracted.sourceUrl}` : "",
    htmlToMarkdown(extracted.content),
  ].filter(Boolean);
  return `${bits.join("\n\n")}\n`;
}

function toPlainText(extracted: Extracted): string {
  const bits = [
    extracted.title,
    [extracted.byline, extracted.siteName].filter(Boolean).join(" · "),
    extracted.sourceUrl ? `来源：${extracted.sourceUrl}` : "",
    htmlToText(extracted.content),
  ].filter(Boolean);
  return `${bits.join("\n\n")}\n`;
}

function htmlToText(html: string): string {
  return html
    .replace(/<br\s*\/?>/gi, "\n")
    .replace(/<\/p>/gi, "\n\n")
    .replace(/<\/h[1-6]>/gi, "\n\n")
    .replace(/<li[^>]*>/gi, "• ")
    .replace(/<\/li>/gi, "\n")
    .replace(/<[^>]+>/g, "")
    .replace(/&nbsp;/g, " ")
    .replace(/&/g, "&")
    .replace(/</g, "<")
    .replace(/>/g, ">")
    .replace(/"/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/[ \t]+\n/g, "\n")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}

function htmlToMarkdown(html: string): string {
  const window = parseHTML(`<div id="mdroot">${html}</div>`);
  const root = window.document.getElementById("mdroot");
  if (!root) return htmlToText(html);
  return mdWalk(root).replace(/\n{3,}/g, "\n\n").trim();
}

function mdWalk(node: { nodeType?: number; textContent?: string; tagName?: string; childNodes?: ArrayLike<unknown>; getAttribute?: (name: string) => string | null }): string {
  if (node.nodeType === 3) return (node.textContent ?? "").replace(/\s+/g, " ");
  if (node.nodeType !== 1) return "";
  const tag = (node.tagName ?? "").toLowerCase();
  const kids = () =>
    Array.from(node.childNodes ?? [])
      .map((child) => mdWalk(child as typeof node))
      .join("");
  if (tag === "br") return "\n";
  if (tag === "hr") return "\n\n---\n\n";
  if (tag === "strong" || tag === "b") return `**${kids().trim()}**`;
  if (tag === "em" || tag === "i") return `*${kids().trim()}*`;
  if (tag === "code") return `\`${kids().trim()}\``;
  if (tag === "pre") return `\n\n\`\`\`\n${(node.textContent ?? "").trim()}\n\`\`\`\n\n`;
  if (tag === "a") {
    const href = node.getAttribute?.("href") ?? "";
    const label = kids().trim() || href;
    return href ? `[${label}](${href})` : label;
  }
  if (tag === "img") {
    const alt = node.getAttribute?.("alt") ?? "";
    const src = node.getAttribute?.("src") ?? "";
    return src ? `![${alt}](${src})` : "";
  }
  if (tag === "h1") return `\n\n# ${kids().trim()}\n\n`;
  if (tag === "h2") return `\n\n## ${kids().trim()}\n\n`;
  if (tag === "h3") return `\n\n### ${kids().trim()}\n\n`;
  if (tag === "li") return `\n- ${kids().trim()}`;
  if (tag === "blockquote") return `\n\n> ${kids().trim().replace(/\n/g, "\n> ")}\n\n`;
  if (tag === "p" || tag === "div" || tag === "section") return `\n\n${kids().trim()}\n\n`;
  if (tag === "ul" || tag === "ol") return `\n${kids()}\n`;
  return kids();
}

function toXhtml(html: string, images: Map<string, EmbeddedImage>): string {
  const normalized = blockify(html);
  const window = parseHTML(`<!doctype html><html><body>${normalized}</body></html>`);
  const root = window.document.body;
  if (!root) return "<p></p>";
  stripJunk(root);
  return serialize(root, images).trim() || "<p></p>";
}

function stripJunk(root: { querySelectorAll: (sel: string) => ArrayLike<{ remove: () => void }> }) {
  const junk = root.querySelectorAll("script,style,iframe,object,form,button,input,noscript,svg");
  for (const node of Array.from(junk)) node.remove();
}

function serialize(node: unknown, images: Map<string, EmbeddedImage>): string {
  const n = node as {
    nodeType: number;
    textContent?: string | null;
    tagName?: string;
    attributes?: ArrayLike<{ name: string; value: string }>;
    childNodes?: ArrayLike<unknown>;
    getAttribute?: (name: string) => string | null;
  };
  if (n.nodeType === 3) return escapeXml(n.textContent ?? "");
  if (n.nodeType !== 1) return "";
  const tag = (n.tagName ?? "").toLowerCase();
  if (!ALLOWED.has(tag)) {
    return Array.from(n.childNodes ?? []).map((c) => serialize(c, images)).join("");
  }
  const attrs: string[] = [];
  if (tag === "a") {
    const href = n.getAttribute?.("href");
    if (href && /^https?:/i.test(href)) attrs.push(`href="${escapeXml(href)}"`);
  }
  if (tag === "img") {
    const src = n.getAttribute?.("src") ?? "";
    const mapped = images.get(src) ?? images.get(tryAbs(src));
    if (!mapped) return "";
    const alt = n.getAttribute?.("alt") ?? "";
    attrs.push(`src="${mapped.href}"`);
    attrs.push(`alt="${escapeXml(alt)}"`);
  }
  const inner = Array.from(n.childNodes ?? []).map((c) => serialize(c, images)).join("");
  if (VOID.has(tag)) return `<${tag}${attrString(attrs)} />`;
  if (tag === "div" || tag === "span") return inner;
  return `<${tag}${attrString(attrs)}>${inner}</${tag}>`;
}

function tryAbs(src: string): string {
  try {
    return new URL(src).href;
  } catch {
    return src;
  }
}

function attrString(attrs: string[]): string {
  return attrs.length ? ` ${attrs.join(" ")}` : "";
}

function escapeXml(value: string): string {
  return value
    .replaceAll("\u0026", "\u0026amp;")
    .replaceAll("\u003c", "\u0026lt;")
    .replaceAll("\u003e", "\u0026gt;")
    .replaceAll("\u0022", "\u0026quot;");
}
