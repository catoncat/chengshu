import assert from "node:assert/strict";
import { test } from "node:test";
import JSZip from "jszip";
import { buildEpub } from "./epub-pack.ts";
import { bookTitle, sanitizeFilename } from "../utils.ts";

async function pack(title: string, siteName = "0nl.onl") {
  return buildEpub({
    title,
    byline: "",
    siteName,
    excerpt: "test",
    sourceUrl: "https://0nl.onl/",
    xhtml: "<p>hello</p>",
    images: [],
  });
}

test("EPUB 2 + NCX uses the article title, never body", async () => {
  const zip = await JSZip.loadAsync(await pack("成书"));
  assert.ok(zip.file("OEBPS/toc.ncx"), "toc.ncx required for WeChat Reading");
  assert.equal(zip.file("OEBPS/nav.xhtml"), null);
  const opf = await zip.file("OEBPS/content.opf")!.async("string");
  assert.match(opf, /version="2.0"/);
  assert.match(opf, /<dc:title>成书<\/dc:title>/);
  assert.doesNotMatch(opf, /<dc:title>\s*body\s*<\/dc:title>/i);
  const ncx = await zip.file("OEBPS/toc.ncx")!.async("string");
  assert.match(ncx, /<text>成书<\/text>/);
  assert.doesNotMatch(ncx, /<text>\s*body\s*<\/text>/i);
  const chapter = await zip.file("OEBPS/chapter.xhtml")!.async("string");
  assert.match(chapter, /<title>成书<\/title>/);
  assert.match(chapter, /<body title="成书">/);
  assert.match(chapter, /<h1>成书<\/h1>/);
});

test("EPUB never falls back to the HTML tag name body", async () => {
  const zip = await JSZip.loadAsync(await pack("body", "成书"));
  const opf = await zip.file("OEBPS/content.opf")!.async("string");
  assert.match(opf, /<dc:title>成书<\/dc:title>/);
  assert.doesNotMatch(opf, /<dc:title>\s*body\s*<\/dc:title>/i);
  const ncx = await zip.file("OEBPS/toc.ncx")!.async("string");
  assert.doesNotMatch(ncx, /<text>\s*body\s*<\/text>/i);
});

test("filename helpers never emit body", () => {
  assert.equal(bookTitle("body", "成书"), "成书");
  assert.equal(bookTitle("", "成书"), "成书");
  assert.equal(bookTitle("  成书  ", "x"), "成书");
  assert.equal(sanitizeFilename("body"), "article");
  assert.equal(sanitizeFilename("成书"), "成书");
});
