import { parseHTML } from "linkedom";

export type ArticleBlock =
  | {
      kind: "p" | "h1" | "h2" | "h3" | "h4" | "h5" | "h6" | "blockquote" | "li" | "figcaption";
      html: string;
    }
  | { kind: "pre"; text: string }
  | { kind: "hr" }
  | { kind: "img"; src: string; alt: string };

type Ctx = { buf: string; br: number };

const SKIP = new Set([
  "script",
  "style",
  "iframe",
  "object",
  "form",
  "button",
  "input",
  "noscript",
  "svg",
  "nav",
  "footer",
  "aside",
]);

const INLINE = new Set([
  "a",
  "em",
  "i",
  "strong",
  "b",
  "span",
  "code",
  "sup",
  "sub",
  "small",
  "u",
  "font",
  "abbr",
  "cite",
  "q",
  "mark",
  "time",
]);

export function extractBlocks(html: string): ArticleBlock[] {
  const { document } = parseHTML("<!doctype html><html><body></body></html>");
  document.body.innerHTML = html || "";
  const out: ArticleBlock[] = [];
  const ctx: Ctx = { buf: "", br: 0 };
  visit(document.body as unknown as Dom, out, ctx);
  flush(out, ctx);
  return out.filter((b) => (b.kind === "hr" || b.kind === "img" ? true : plainLen(b) > 0));
}

export function blockify(html: string): string {
  const blocks = extractBlocks(html);
  const parts: string[] = [];
  let list: string[] = [];
  const flushList = () => {
    if (!list.length) return;
    parts.push(`<ul>${list.join("")}</ul>`);
    list = [];
  };
  for (const b of blocks) {
    if (b.kind === "li") {
      list.push(`<li>${b.html}</li>`);
      continue;
    }
    flushList();
    parts.push(blockToXhtml(b));
  }
  flushList();
  return parts.join("\n") || "<p></p>";
}

export function blockText(block: ArticleBlock): string {
  if (block.kind === "hr") return "";
  if (block.kind === "img") return "";
  if (block.kind === "pre") return block.text;
  return strip(block.html);
}

function blockToXhtml(b: ArticleBlock): string {
  if (b.kind === "hr") return "<hr />";
  if (b.kind === "img") {
    return `<p><img src="${escapeXml(b.src)}" alt="${escapeXml(b.alt)}" /></p>`;
  }
  if (b.kind === "pre") return `<pre><code>${escapeXml(b.text)}</code></pre>`;
  return `<${b.kind}>${b.html}</${b.kind}>`;
}

type Dom = {
  nodeType: number;
  nodeName: string;
  textContent?: string | null;
  childNodes: ArrayLike<Dom>;
  getAttribute?: (name: string) => string | null;
};

function visit(node: Dom, out: ArticleBlock[], ctx: Ctx) {
  if (node.nodeType === 3) {
    const raw = node.textContent || "";
    if (!raw.trim()) {
      if (ctx.br === 0 && ctx.buf) ctx.buf += " ";
      return;
    }
    if (ctx.br >= 2) flush(out, ctx);
    ctx.br = 0;
    ctx.buf += escapeXml(raw);
    return;
  }
  if (node.nodeType !== 1) return;
  const tag = (node.nodeName || "").toLowerCase();
  if (SKIP.has(tag)) return;
  if (tag === "br") {
    ctx.br += 1;
    if (ctx.br >= 2) flush(out, ctx);
    return;
  }
  if (tag === "hr") {
    flush(out, ctx);
    out.push({ kind: "hr" });
    return;
  }
  if (tag === "img") {
    flush(out, ctx);
    const src = node.getAttribute?.("src") || "";
    if (src) out.push({ kind: "img", src, alt: node.getAttribute?.("alt") || "" });
    return;
  }
  if (/^h[1-6]$/.test(tag)) {
    flush(out, ctx);
    const html = inlineHtml(node);
    if (plain(html)) out.push({ kind: tag as "h1" | "h2" | "h3" | "h4" | "h5" | "h6", html });
    return;
  }
  if (tag === "pre") {
    flush(out, ctx);
    out.push({ kind: "pre", text: (node.textContent || "").replace(/\s+$/g, "") });
    return;
  }
  if (tag === "blockquote") {
    flush(out, ctx);
    const inner: ArticleBlock[] = [];
    const sub: Ctx = { buf: "", br: 0 };
    for (const child of Array.from(node.childNodes)) visit(child, inner, sub);
    flush(inner, sub);
    if (!inner.length) {
      const html = inlineHtml(node);
      if (plain(html)) out.push({ kind: "blockquote", html });
    } else {
      for (const b of inner) {
        if (b.kind === "p") out.push({ kind: "blockquote", html: b.html });
        else out.push(b);
      }
    }
    return;
  }
  if (tag === "li") {
    flush(out, ctx);
    const html = inlineHtml(node);
    if (plain(html)) out.push({ kind: "li", html });
    return;
  }
  if (tag === "p" || tag === "figcaption") {
    flush(out, ctx);
    const html = inlineHtml(node);
    for (const part of splitDoubleBr(html)) {
      if (plain(part)) out.push({ kind: tag, html: part });
    }
    return;
  }
  if (INLINE.has(tag)) {
    if (ctx.br >= 2) flush(out, ctx);
    ctx.br = 0;
    ctx.buf += inlineTag(tag, node);
    return;
  }
  const startOut = out.length;
  const startBuf = ctx.buf;
  for (const child of Array.from(node.childNodes)) visit(child, out, ctx);
  if (isContainer(tag) && out.length === startOut && ctx.buf !== startBuf) flush(out, ctx);
}

function isContainer(tag: string) {
  return tag === "div" || tag === "section" || tag === "article" || tag === "main" || tag === "td" || tag === "dd";
}

function inlineTag(tag: string, node: Dom): string {
  const inner = inlineHtml(node);
  if (tag === "a") {
    const href = node.getAttribute?.("href") || "";
    if (href && /^https?:/i.test(href)) return `<a href="${escapeXml(href)}">${inner}</a>`;
    return inner;
  }
  if (tag === "strong" || tag === "b") return `<strong>${inner}</strong>`;
  if (tag === "em" || tag === "i") return `<em>${inner}</em>`;
  if (tag === "code") return `<code>${inner}</code>`;
  if (tag === "sup" || tag === "sub") return `<${tag}>${inner}</${tag}>`;
  return inner;
}

function inlineHtml(node: Dom): string {
  let s = "";
  for (const child of Array.from(node.childNodes)) {
    if (child.nodeType === 3) {
      s += escapeXml(child.textContent || "");
      continue;
    }
    if (child.nodeType !== 1) continue;
    const tag = (child.nodeName || "").toLowerCase();
    if (tag === "br") {
      s += "<br />";
      continue;
    }
    if (tag === "img") continue;
    if (INLINE.has(tag) || !SKIP.has(tag)) s += inlineTag(tag, child);
  }
  return s;
}

function flush(out: ArticleBlock[], ctx: Ctx) {
  const html = tidy(ctx.buf);
  ctx.buf = "";
  ctx.br = 0;
  if (!plain(html)) return;
  for (const part of splitDoubleBr(html)) {
    if (plain(part)) out.push({ kind: "p", html: part });
  }
}

function splitDoubleBr(html: string): string[] {
  return html.split(/<br\s*\/?>\s*(?:<br\s*\/?>\s*)+/i).map(tidy).filter(Boolean);
}

function tidy(html: string): string {
  return html
    .replace(/^(?:<br\s*\/?>|\s)+/gi, "")
    .replace(/(?:<br\s*\/?>|\s)+$/gi, "")
    .replace(/\s+/g, " ")
    .trim();
}

function plain(html: string): boolean {
  return strip(html).length > 0;
}

function plainLen(b: ArticleBlock): number {
  return blockText(b).replace(/\s+/g, " ").trim().length;
}

function strip(html: string): string {
  return html
    .replace(/<br\s*\/?>/gi, " ")
    .replace(/<[^>]+>/g, "")
    .replace(/\u0026nbsp;/gi, " ")
    .replace(/\u0026amp;/gi, "\u0026")
    .replace(/\u0026lt;/gi, "\u003c")
    .replace(/\u0026gt;/gi, "\u003e")
    .replace(/\u0026quot;/gi, "\u0022")
    .trim();
}

function escapeXml(value: string) {
  return value
    .replaceAll("\u0026", "\u0026amp;")
    .replaceAll("\u003c", "\u0026lt;")
    .replaceAll("\u003e", "\u0026gt;")
    .replaceAll("\u0022", "\u0026quot;");
}
