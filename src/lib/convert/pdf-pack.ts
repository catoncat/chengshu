import PDFDocument from "pdfkit";
import { parseHTML } from "linkedom";
import { BOOK_FONT } from "./book-font.ts";

export type PdfImage = {
  href: string;
  mediaType: string;
  data: Uint8Array;
};

type Block = { kind: string; text: string; src?: string };

function textOf(el: { textContent?: string | null }) {
  return (el.textContent || "").replace(/\s+/g, " ").trim();
}

function blocksFromHtml(html: string): Block[] {
  const { document } = parseHTML(`<body>${html}</body>`);
  const out: Block[] = [];

  function visit(node: { nodeType: number; nodeName: string; textContent?: string | null; getAttribute?: (n: string) => string | null; childNodes: ArrayLike<unknown> }) {
    if (node.nodeType === 3) return;
    const tag = (node.nodeName || "").toLowerCase();
    if (tag === "img") {
      out.push({ kind: "img", text: "", src: node.getAttribute?.("src") || "" });
      return;
    }
    if (/^h[1-6]$/.test(tag)) {
      out.push({ kind: tag, text: textOf(node) });
      return;
    }
    if (tag === "p" || tag === "blockquote" || tag === "li" || tag === "figcaption") {
      out.push({ kind: tag, text: textOf(node) });
      return;
    }
    if (tag === "pre") {
      out.push({ kind: "pre", text: (node.textContent || "").replace(/\s+$/g, "") });
      return;
    }
    if (tag === "hr") {
      out.push({ kind: "hr", text: "" });
      return;
    }
    for (const child of Array.from(node.childNodes)) {
      visit(child as typeof node);
    }
  }

  visit(document.body as unknown as Parameters<typeof visit>[0]);
  if (!out.length) {
    const t = textOf(document.body);
    if (t) out.push({ kind: "p", text: t });
  }
  return out.filter((b) => (b.kind === "img" ? Boolean(b.src) : b.kind === "hr" || Boolean(b.text)));
}

function usableTitle(value: string, fallback: string) {
  const t = (value || "").replace(/\s+/g, " ").trim();
  if (!t || /^body$/i.test(t)) return fallback;
  return t;
}

export async function buildPdf(input: {
  title: string;
  byline: string;
  siteName: string;
  excerpt: string;
  sourceUrl: string;
  html: string;
  images: PdfImage[];
}): Promise<Uint8Array> {
  const fallback = usableTitle(input.siteName, "") || "未命名";
  const title = usableTitle(input.title, fallback);
  const doc = new PDFDocument({
    size: "A5",
    bufferPages: true,
    margins: { top: 50, bottom: 48, left: 44, right: 44 },
    info: {
      Title: title,
      Author: input.byline || "",
      Subject: input.excerpt || input.sourceUrl || "",
      Creator: "成书",
    },
  });
  const chunks: Buffer[] = [];
  doc.on("data", (c: Buffer) => chunks.push(c));
  const done = new Promise<Uint8Array>((resolve, reject) => {
    doc.on("end", () => resolve(Buffer.concat(chunks)));
    doc.on("error", reject);
  });

  doc.font(BOOK_FONT);
  const pageWidth = doc.page.width - doc.page.margins.left - doc.page.margins.right;

  doc.on("pageAdded", () => {
    doc.font(BOOK_FONT);
  });

  doc.fontSize(16).fillColor("#171412").text(title, { width: pageWidth, lineGap: 4 });
  const meta = [input.byline, input.siteName].filter(Boolean).join(" · ");
  if (meta) {
    doc.moveDown(0.35);
    doc.fontSize(9).fillColor("#6b645c").text(meta, { width: pageWidth });
  }
  if (input.sourceUrl) {
    doc.moveDown(0.15);
    doc.fontSize(8).fillColor("#8a3b12").text(input.sourceUrl, {
      width: pageWidth,
      link: input.sourceUrl,
    });
  }
  doc.moveDown(0.8);
  doc.fillColor("#171412");

  const images = new Map(input.images.map((img) => [img.href, img]));

  for (const block of blocksFromHtml(input.html)) {
    if (block.kind === "hr") {
      doc.moveDown(0.3);
      const y = doc.y;
      doc
        .moveTo(doc.page.margins.left, y)
        .lineTo(doc.page.margins.left + pageWidth, y)
        .strokeColor("#c8c1b4")
        .lineWidth(0.6)
        .stroke();
      doc.strokeColor("#000").moveDown(0.5);
      continue;
    }
    if (block.kind === "img") {
      const hit =
        images.get(block.src || "") ||
        [...images.values()].find((img) => (block.src || "").endsWith(img.href));
      const ok =
        hit &&
        (hit.mediaType.includes("jpeg") ||
          hit.mediaType.includes("jpg") ||
          hit.mediaType.includes("png"));
      if (ok && hit) {
        try {
          if (doc.y + 80 > doc.page.height - doc.page.margins.bottom) doc.addPage();
          doc.image(Buffer.from(hit.data), { fit: [pageWidth, 280], align: "center" });
          doc.moveDown(0.6);
        } catch {
          /* skip broken image */
        }
      }
      continue;
    }
    if (!block.text) continue;
    const heading = /^h[1-6]$/.test(block.kind);
    const pre = block.kind === "pre";
    const quote = block.kind === "blockquote";
    const li = block.kind === "li";
    const size = heading
      ? block.kind === "h1"
        ? 14
        : block.kind === "h2"
          ? 12.5
          : 11.5
      : pre
        ? 9
        : 10.5;
    if (heading) doc.moveDown(0.55);
    doc.fontSize(size);
    doc.fillColor(quote ? "#4a453f" : "#171412").text((li ? "• " : "") + block.text, {
      width: pageWidth - (quote ? 16 : 0),
      indent: !heading && !pre && !quote && !li ? 21 : 0,
      lineGap: heading ? 2 : 3.5,
      paragraphGap: 8,
      align: "left",
    });
  }

  const { count } = doc.bufferedPageRange();
  for (let i = 0; i < count; i++) {
    doc.switchToPage(i);
    doc.font(BOOK_FONT).fontSize(8).fillColor("#8a857c");
    doc.text(String(i + 1), 0, doc.page.height - 32, {
      width: doc.page.width,
      align: "center",
      lineBreak: false,
    });
  }

  doc.end();
  return done;
}
