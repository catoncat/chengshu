import assert from "node:assert/strict";
import { test } from "node:test";
import { PDFDocument } from "pdf-lib";
import { buildPdf } from "./pdf-pack.ts";

test("PDF is a real PDF with a title, never body", async () => {
  const bytes = await buildPdf({
    title: "成书",
    byline: "作者",
    siteName: "0nl.onl",
    excerpt: "test",
    sourceUrl: "https://0nl.onl/",
    html: "<p>这是一段用来排进 PDF 的正文。</p><h2>第二节</h2><p>第二段。</p>",
    images: [],
  });
  const buf = Buffer.from(bytes);
  assert.equal(buf.subarray(0, 5).toString(), "%PDF-");
  assert.ok(buf.length > 2000);
  const doc = await PDFDocument.load(bytes);
  assert.equal(doc.getTitle(), "成书");
  assert.notEqual((doc.getTitle() || "").toLowerCase(), "body");
});

test("PDF falls back when title is the HTML tag body", async () => {
  const bytes = await buildPdf({
    title: "body",
    byline: "",
    siteName: "成书",
    excerpt: "",
    sourceUrl: "https://0nl.onl/",
    html: "<p>hello</p>",
    images: [],
  });
  const doc = await PDFDocument.load(bytes);
  assert.equal(doc.getTitle(), "成书");
});
