import { bookTitle } from "../utils.ts";

export type ArticleDraft = {
  title: string;
  byline: string;
  siteName: string;
  excerpt: string;
  content: string;
};

const WRAPPERS = ["paper", "article", "post", "data", "item", "result", "page", "payload", "doc"];

export function jsonCandidateUrls(pageUrl: string): string[] {
  let parsed: URL;
  try {
    parsed = new URL(pageUrl);
  } catch {
    return [];
  }
  const path = parsed.pathname.replace(/\/+$/, "") || "/";
  const segs = path.split("/").filter(Boolean);
  if (segs.length === 0) return [];
  const id = segs[segs.length - 1];
  const parent = segs.length >= 2 ? segs[segs.length - 2] : "";
  const paths: string[] = [];
  if (parent) {
    const plural = parent.endsWith("s") ? parent : `${parent}s`;
    paths.push(`/api/${plural}/${id}`);
    if (plural !== parent) paths.push(`/api/${parent}/${id}`);
  }
  paths.push(`/api${path}`);
  paths.push(`${path}.json`);
  const unique: string[] = [];
  for (const p of paths) {
    try {
      const href = new URL(p, parsed.origin).href;
      if (new URL(href).origin !== parsed.origin) continue;
      if (!unique.includes(href)) unique.push(href);
    } catch {
      /* skip */
    }
  }
  return unique.slice(0, 4);
}

export function isThinHtml(html: string): boolean {
  const text = html
    .replace(/<script[\s\S]*?<\/script>/gi, " ")
    .replace(/<style[\s\S]*?<\/style>/gi, " ")
    .replace(/<[^>]+>/g, " ")
    .replace(/\s+/g, " ")
    .trim();
  if (/loading refracted|enable javascript|you need to enable js|loading[\w\s]{0,24}workspace/i.test(text)) {
    return true;
  }
  const spaShell = /__next_f|__NEXT_DATA__|id="__next"|data-reactroot|ng-version|id="root"|id="app"/i.test(html);
  if (spaShell && text.length < 800) return true;
  return text.length < 280;
}

export function articleFromUnknown(data: unknown, sourceUrl: string): ArticleDraft | null {
  const obj = unwrap(data);
  if (!obj) return null;
  const title = str(obj.title) || str(obj.name) || str(obj.headline);
  const html = materializeHtml(obj, sourceUrl);
  const plain = html.replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim();
  if (plain.length < 80) return null;
  const byline = authors(obj.authors ?? obj.author ?? obj.byline);
  const site = hostName(sourceUrl);
  const excerpt = (str(obj.abstract) || str(obj.description) || str(obj.excerpt) || plain).slice(0, 220);
  return {
    title: bookTitle(title, site || "未命名"),
    byline,
    siteName: str(obj.venue) || str(obj.siteName) || site,
    excerpt,
    content: html,
  };
}

function unwrap(data: unknown): Record<string, unknown> | null {
  if (Array.isArray(data)) {
    const first = data.find((item) => item && typeof item === "object");
    return first ? unwrap(first) : null;
  }
  if (!data || typeof data !== "object") return null;
  const obj = data as Record<string, unknown>;
  for (const key of WRAPPERS) {
    const inner = obj[key];
    if (inner && typeof inner === "object" && !Array.isArray(inner)) {
      const rec = inner as Record<string, unknown>;
      if (rec.title || rec.abstract || rec.sections || rec.content || rec.body || rec.html) {
        return rec;
      }
    }
  }
  return obj;
}

function materializeHtml(obj: Record<string, unknown>, sourceUrl: string): string {
  const parts: string[] = [];
  const abs = str(obj.abstract);
  if (abs) parts.push(`<h2>Abstract</h2><p>${escapeXml(abs)}</p>`);
  const sections = Array.isArray(obj.sections) ? obj.sections : [];
  for (const raw of sections) {
    if (!raw || typeof raw !== "object") continue;
    const section = raw as Record<string, unknown>;
    const heading = str(section.title) || str(section.heading);
    if (heading && heading.toLowerCase() === "abstract" && abs) continue;
    if (heading) parts.push(`<h2>${escapeXml(heading)}</h2>`);
    const glance = str(section.atAGlance);
    if (glance) parts.push(`<p><em>${escapeXml(glance)}</em></p>`);
    parts.push(contentToHtml(section.content ?? section.body ?? section.html ?? section.text, sourceUrl));
  }
  if (sections.length === 0) {
    parts.push(contentToHtml(obj.html ?? obj.content ?? obj.body ?? obj.markdown ?? obj.text, sourceUrl));
  }
  const figures = Array.isArray(obj.figures) ? obj.figures : [];
  for (const raw of figures) {
    if (!raw || typeof raw !== "object") continue;
    const fig = raw as Record<string, unknown>;
    const src = absUrl(str(fig.imagePath) || str(fig.src) || str(fig.url), sourceUrl);
    const cap = str(fig.caption) || str(fig.title);
    if (!src) continue;
    parts.push(
      `<figure><img src="${escapeXml(src)}" alt="${escapeXml(cap)}" />${
        cap ? `<figcaption>${escapeXml(cap)}</figcaption>` : ""
      }</figure>`,
    );
  }
  return parts.filter(Boolean).join("\n");
}

function contentToHtml(content: unknown, sourceUrl: string): string {
  if (content == null) return "";
  if (typeof content === "string") {
    if (/<[a-z][\s\S]*>/i.test(content)) return content;
    return content
      .split(/\n{2,}/)
      .map((p) => p.trim())
      .filter(Boolean)
      .map((p) => `<p>${escapeXml(p)}</p>`)
      .join("");
  }
  if (Array.isArray(content)) return content.map((item) => contentToHtml(item, sourceUrl)).join("");
  if (typeof content !== "object") return "";
  const obj = content as Record<string, unknown>;
  const type = str(obj.type).toLowerCase();
  const text = str(obj.text) || str(obj.html) || str(obj.content);
  const heading = str(obj.title) || str(obj.heading);
  if (type === "heading" && heading) return `<h3>${escapeXml(heading)}</h3>`;
  if (type === "image") {
    const src = absUrl(str(obj.src) || str(obj.url) || str(obj.imagePath), sourceUrl);
    if (!src) return "";
    return `<p><img src="${escapeXml(src)}" alt="${escapeXml(str(obj.alt) || heading)}" /></p>`;
  }
  if ((type === "code" || type === "codeblock" || type === "pre") && text) {
    return `<pre><code>${escapeXml(text)}</code></pre>`;
  }
  if ((type === "quote" || type === "blockquote") && text) {
    return `<blockquote><p>${escapeXml(text)}</p></blockquote>`;
  }
  if (text) return `<p>${escapeXml(text)}</p>`;
  if (Array.isArray(obj.items)) {
    return `<ul>${obj.items.map((item) => `<li>${escapeXml(str(item))}</li>`).join("")}</ul>`;
  }
  return "";
}

function authors(value: unknown): string {
  if (typeof value === "string") return value.trim();
  if (Array.isArray(value)) {
    return value
      .map((item) => {
        if (typeof item === "string") return item;
        if (item && typeof item === "object") {
          const rec = item as Record<string, unknown>;
          return str(rec.name) || str(rec.author);
        }
        return "";
      })
      .filter(Boolean)
      .join(", ");
  }
  if (value && typeof value === "object") return str((value as Record<string, unknown>).name);
  return "";
}

function str(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function absUrl(src: string, base: string): string {
  if (!src) return "";
  try {
    return new URL(src, base).href;
  } catch {
    return src;
  }
}

function hostName(url: string): string {
  try {
    return new URL(url).hostname.replace(/^www\./, "");
  } catch {
    return "";
  }
}

function escapeXml(value: string) {
  return value
    .replaceAll("\u0026", "\u0026amp;")
    .replaceAll("\u003c", "\u0026lt;")
    .replaceAll("\u003e", "\u0026gt;")
    .replaceAll("\u0022", "\u0026quot;");
}
