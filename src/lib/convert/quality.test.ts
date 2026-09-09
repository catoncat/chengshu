import assert from "node:assert/strict";
import { test } from "node:test";
import JSZip from "jszip";
import { PDFDocument } from "pdf-lib";
import { blockify, blocksToMarkdown, extractBlocks, paragraphCount } from "./article-html.ts";
import { buildEpub, splitChapters } from "./epub-pack.ts";
import { buildPdf } from "./pdf-pack.ts";
import { SAMPLE_BR_ESSAY, SAMPLE_HTML, SAMPLE_TITLE } from "./sample-article.ts";

test("sample article EPUB has chapters, paragraphs, and is not one blob", async () => {
  const xhtml = blockify(SAMPLE_HTML);
  assert.ok((xhtml.match(/<p>/g) || []).length >= 4, xhtml.slice(0, 400));
  assert.match(xhtml, /<h2>/);
  assert.match(xhtml, /<pre>/);
  const chapters = splitChapters(xhtml, SAMPLE_TITLE);
  assert.ok(chapters.length >= 2, `chapters=${chapters.length}`);
  const epub = await buildEpub({
    title: SAMPLE_TITLE,
    byline: "",
    siteName: "成书",
    excerpt: "",
    sourceUrl: "https://0nl.onl/example",
    xhtml,
    images: [],
  });
  const zip = await JSZip.loadAsync(epub);
  const ncx = await zip.file("OEBPS/toc.ncx")!.async("string");
  assert.match(ncx, /为什么要本地保存/);
  assert.ok((ncx.match(/<navPoint/g) || []).length >= 2);
  const files = Object.keys(zip.files).filter((n) => n.startsWith("OEBPS/chapter"));
  assert.ok(files.length >= 2, files.join(","));
  const chapter = await zip.file(files[0]!)!.async("string");
  assert.match(chapter, /<h1>/);
});

test("sample article PDF is a real document with more than a URL", async () => {
  const bytes = await buildPdf({
    title: SAMPLE_TITLE,
    byline: "",
    siteName: "成书",
    excerpt: "",
    sourceUrl: "https://0nl.onl/example",
    html: SAMPLE_HTML,
    images: [],
  });
  const buf = Buffer.from(bytes);
  assert.equal(buf.subarray(0, 5).toString(), "%PDF-");
  const doc = await PDFDocument.load(bytes);
  assert.ok((doc.getPageCount() ?? 0) >= 1);
  assert.equal(doc.getTitle(), SAMPLE_TITLE);
  assert.ok(bytes.byteLength > 3000);
});

test("br-separated essay does not collapse to one EPUB paragraph", async () => {
  const xhtml = blockify(SAMPLE_BR_ESSAY);
  assert.ok((xhtml.match(/<p>/g) || []).length >= 3, xhtml);
  const epub = await buildEpub({
    title: "Great Work",
    byline: "",
    siteName: "",
    excerpt: "",
    sourceUrl: "https://example.org/essay",
    xhtml,
    images: [],
  });
  const zip = await JSZip.loadAsync(epub);
  const chapter = await zip.file("OEBPS/chapter.xhtml")!.async("string");
  assert.ok((chapter.match(/<p>/g) || []).length >= 3, chapter.slice(0, 500));
});

test("markdown from blocks keeps headings and code", () => {
  const md = blocksToMarkdown(extractBlocks(SAMPLE_HTML));
  assert.match(md, /## 为什么要本地保存/);
  assert.match(md, /```/);
  assert.ok(paragraphCount(SAMPLE_HTML) >= 4);
});
