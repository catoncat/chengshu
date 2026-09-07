import { Readability } from "@mozilla/readability";
import { parseHTML } from "linkedom";
import JSZip from "jszip";
import { marked } from "marked";
import { assertPublicHttpUrl, isPublicHttpUrl } from "./ssrf";
import { sanitizeFilename } from "@/lib/utils";

const UA =
  "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36";

const MAX_IMAGES = 12;
const MAX_IMAGE_BYTES = 700_000;
const MAX_HTML_BYTES = 2_500_000;

export type ConvertRequest = {
  url?: string;
  text?: string;
  title?: string;
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
  const url = input.url?.trim();
  const text = input.text?.trim();
  const extracted = url
    ? await extractFromUrl(url)
    : extractFromText(text ?? "", input.title);
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

async function extractFromUrl(rawUrl: string): Promise<Extracted> {
  const parsed = assertPublicHttpUrl(rawUrl);
  const sourceUrl = parsed.href;
  const html = await fetchHtml(sourceUrl);
  const article = readabilityExtract(html, sourceUrl);
  const textLen = article?.content.replace(/<[^>]+>/g, "").length ?? 0;
  if (article && textLen >= 180) return article;

  const fromJina = await jinaExtract(sourceUrl);
  if (fromJina && (fromJina.content.replace(/<[^>]+>/g, "").length > textLen)) {
    return fromJina;
  }
  if (article) return article;
  throw new Error("这个页面拦了抓取，或正文太短");
}

function extractFromText(text: string, title?: string): Extracted {
  if (!text) throw new Error("没有可转换的内容");
  const paragraphs = text
    .split(/\n{2,}/)
    .map((p) => p.trim())
    .filter(Boolean)
    .map((p) => `<p>${escapeXml(p).replaceAll("\n", "<br />")}</p>`)
    .join("");
  const heading = title?.trim() || text.slice(0, 32) || "摘录";
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
  const res = await fetch(url, {
    redirect: "follow",
    signal: AbortSignal.timeout(16000),
    headers: {
      "User-Agent": UA,
      Accept: "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8",
      "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.6",
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
    const title = (parsed.title || document.title || hostName(url)).trim();
    return {
      title: title || "未命名",
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
    const title = titleMatch?.[1]?.trim() || hostName(url);
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

function toXhtml(html: string, images: Map<string, EmbeddedImage>): string {
  const window = parseHTML(`<div id="c">${html}</div>`);
  const root = window.document.getElementById("c");
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

async function buildEpub(input: {
  title: string;
  byline: string;
  siteName: string;
  excerpt: string;
  sourceUrl: string;
  xhtml: string;
  images: EmbeddedImage[];
}): Promise<Uint8Array> {
  const zip = new JSZip();
  const id = crypto.randomUUID();
  const now = new Date().toISOString().replace(/\.\d{3}Z$/, "Z");
  const lang = /[\u4e00-\u9fff]/.test(input.title + input.xhtml) ? "zh" : "en";
  const cover = input.images[0];

  zip.file("mimetype", "application/epub+zip", { compression: "STORE" });
  zip.file(
    "META-INF/container.xml",
    `<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>`,
  );

  const manifestItems = [
    `<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>`,
    `<item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>`,
    `<item id="css" href="style.css" media-type="text/css"/>`,
    ...input.images.map(
      (img) =>
        `<item id="${img.id}" href="${img.href}" media-type="${img.mediaType}"${
          cover && img.id === cover.id ? ' properties="cover-image"' : ""
        }/>`,
    ),
  ];

  zip.file(
    "OEBPS/content.opf",
    `<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" unique-identifier="bookid" version="3.0" xml:lang="${lang}">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="bookid">urn:uuid:${id}</dc:identifier>
    <dc:title>${escapeXml(input.title)}</dc:title>
    <dc:language>${lang}</dc:language>
    ${input.byline ? `<dc:creator>${escapeXml(input.byline)}</dc:creator>` : ""}
    ${input.siteName ? `<dc:publisher>${escapeXml(input.siteName)}</dc:publisher>` : ""}
    ${input.sourceUrl ? `<dc:source>${escapeXml(input.sourceUrl)}</dc:source>` : ""}
    ${input.excerpt ? `<dc:description>${escapeXml(input.excerpt)}</dc:description>` : ""}
    <meta property="dcterms:modified">${now}</meta>
  </metadata>
  <manifest>
    ${manifestItems.join("\n    ")}
  </manifest>
  <spine>
    <itemref idref="chapter"/>
  </spine>
</package>`,
  );

  zip.file(
    "OEBPS/nav.xhtml",
    `<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="${lang}">
  <head><title>${escapeXml(input.title)}</title></head>
  <body>
    <nav epub:type="toc">
      <ol><li><a href="chapter.xhtml">${escapeXml(input.title)}</a></li></ol>
    </nav>
  </body>
</html>`,
  );

  const metaBits = [
    input.byline,
    input.siteName,
    input.sourceUrl ? `<a href="${escapeXml(input.sourceUrl)}">${escapeXml(hostName(input.sourceUrl) || input.sourceUrl)}</a>` : "",
  ].filter(Boolean);

  zip.file(
    "OEBPS/chapter.xhtml",
    `<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="${lang}">
  <head>
    <title>${escapeXml(input.title)}</title>
    <link rel="stylesheet" href="style.css" type="text/css"/>
  </head>
  <body>
    <h1>${escapeXml(input.title)}</h1>
    ${metaBits.length ? `<p class="meta">${metaBits.join(" · ")}</p>` : ""}
    ${input.xhtml}
  </body>
</html>`,
  );

  zip.file(
    "OEBPS/style.css",
    `body{font-family:"Songti SC","Noto Serif CJK SC","Source Han Serif SC",Georgia,serif;line-height:1.75;font-size:1em;margin:0;padding:0}
h1{font-size:1.55em;line-height:1.3;margin:0 0 .8em;text-indent:0}
h2,h3{line-height:1.35;margin:1.2em 0 .5em;text-indent:0}
p{margin:.75em 0;text-indent:2em}
p.meta{text-indent:0;font-size:.9em;opacity:.72}
img{max-width:100%;height:auto;display:block;margin:1em auto}
blockquote{margin:1em 0;padding-left:1em;border-left:3px solid #c8c1b4;opacity:.92}
a{color:inherit}
pre,code{font-family:ui-monospace,monospace;font-size:.92em}
ul,ol{padding-left:1.4em}
li{margin:.25em 0}`,
  );

  for (const img of input.images) {
    zip.file(`OEBPS/${img.href}`, img.data);
  }

  const out = await zip.generateAsync({
    type: "uint8array",
    compression: "DEFLATE",
    compressionOptions: { level: 6 },
  });
  return out;
}
