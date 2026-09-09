import fontkit from "@pdf-lib/fontkit";
import { PDFDocument, rgb, type PDFFont, type PDFPage } from "pdf-lib";
import { BOOK_FONT } from "./book-font.ts";
import { wrapText } from "./break-line.ts";
import { blockText, extractBlocks } from "./article-html.ts";

export type PdfImage = {
  href: string;
  mediaType: string;
  data: Uint8Array;
};

type Block = { kind: string; text: string; src?: string };

const PAGE = { width: 419.53, height: 595.28 };
const MARGIN = { top: 50, bottom: 48, left: 44, right: 44 };
const INK = rgb(23 / 255, 20 / 255, 18 / 255);
const MUTED = rgb(107 / 255, 100 / 255, 92 / 255);
const RULE = rgb(200 / 255, 193 / 255, 180 / 255);

function blocksFromHtml(html: string): Block[] {
  return extractBlocks(html).map((b) => {
    if (b.kind === "img") return { kind: "img", text: "", src: b.src };
    if (b.kind === "hr") return { kind: "hr", text: "" };
    return { kind: b.kind, text: blockText(b) };
  });
}

function usableTitle(value: string, fallback: string) {
  const t = (value || "").replace(/\s+/g, " ").trim();
  if (!t || /^body$/i.test(t)) return fallback;
  return t;
}

function wrap(text: string, font: PDFFont, size: number, maxWidth: number): string[] {
  const safe = [...text]
    .map((ch) => {
      try {
        font.widthOfTextAtSize(ch, size);
        return ch;
      } catch {
        return ch === " " ? " " : "";
      }
    })
    .join("");
  return wrapText(safe, maxWidth, (s) => font.widthOfTextAtSize(s, size));
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
  const pdf = await PDFDocument.create();
  pdf.registerFontkit(fontkit);
  const font = await pdf.embedFont(BOOK_FONT, { subset: true });
  pdf.setTitle(title);
  pdf.setAuthor(input.byline || "");
  pdf.setSubject(input.excerpt || input.sourceUrl || "");
  pdf.setCreator("成书");
  pdf.setProducer("成书");

  const contentWidth = PAGE.width - MARGIN.left - MARGIN.right;
  let page = pdf.addPage([PAGE.width, PAGE.height]);
  let y = PAGE.height - MARGIN.top;

  const newPage = () => {
    page = pdf.addPage([PAGE.width, PAGE.height]);
    y = PAGE.height - MARGIN.top;
  };

  const ensure = (need: number) => {
    if (y - need < MARGIN.bottom) newPage();
  };

  const drawLines = (
    lines: string[],
    size: number,
    color = INK,
    indent = 0,
    lineGap = 3.5,
    paraGap = 8,
  ) => {
    const height = size * 1.75;
    for (const line of lines) {
      ensure(height + lineGap);
      page.drawText(line || " ", {
        x: MARGIN.left + indent,
        y: y - size,
        size,
        font,
        color,
      });
      y -= height + lineGap;
    }
    y -= paraGap - lineGap;
  };

  drawLines(wrap(title, font, 16, contentWidth), 16, INK, 0, 4, 6);
  const meta = [input.byline, input.siteName].filter(Boolean).join(" · ");
  if (meta) drawLines(wrap(meta, font, 9, contentWidth), 9, MUTED, 0, 2, 4);
  if (input.sourceUrl) {
    drawLines(wrap(input.sourceUrl, font, 8, contentWidth), 8, rgb(138 / 255, 59 / 255, 18 / 255), 0, 2, 12);
  }

  const images = new Map(input.images.map((img) => [img.href, img]));

  for (const block of blocksFromHtml(input.html)) {
    if (block.kind === "hr") {
      ensure(12);
      y -= 6;
      page.drawLine({
        start: { x: MARGIN.left, y },
        end: { x: MARGIN.left + contentWidth, y },
        thickness: 0.6,
        color: RULE,
      });
      y -= 10;
      continue;
    }
    if (block.kind === "img") {
      const hit =
        images.get(block.src || "") ||
        [...images.values()].find((img) => (block.src || "").endsWith(img.href));
      if (hit) {
        try {
          const jpg = hit.mediaType.includes("jpeg") || hit.mediaType.includes("jpg");
          const png = hit.mediaType.includes("png");
          if (!jpg && !png) continue;
          const embedded = jpg ? await pdf.embedJpg(hit.data) : await pdf.embedPng(hit.data);
          const dims = embedded.scaleToFit(contentWidth, 280);
          ensure(dims.height + 16);
          y -= dims.height;
          page.drawImage(embedded, {
            x: MARGIN.left + (contentWidth - dims.width) / 2,
            y,
            width: dims.width,
            height: dims.height,
          });
          y -= 12;
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
    const size = heading ? (block.kind === "h1" ? 14 : block.kind === "h2" ? 12.5 : 11.5) : pre ? 9 : 10.5;
    if (heading) y -= 8;
    const indent = !heading && !pre && !quote && !li ? 21 : 0;
    const prefix = li ? "• " : "";
    drawLines(
      wrap(prefix + block.text, font, size, contentWidth - indent - (quote ? 16 : 0)),
      size,
      quote ? rgb(74 / 255, 69 / 255, 63 / 255) : INK,
      indent + (quote ? 12 : 0),
      heading ? 2 : 3.5,
      8,
    );
  }

  const pages = pdf.getPages();
  pages.forEach((p: PDFPage, i) => {
    const label = String(i + 1);
    const w = font.widthOfTextAtSize(label, 8);
    p.drawText(label, {
      x: (PAGE.width - w) / 2,
      y: 22,
      size: 8,
      font,
      color: MUTED,
    });
  });

  return pdf.save();
}
