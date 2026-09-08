#!/usr/bin/env node
import JSZip from "jszip";

const BASE = process.argv[2] || "http://127.0.0.1:8080";
const SAMPLE = process.argv[3] || "https://0nl.onl/";
const HARD =
  process.argv[4] ||
  "https://refract.aniketh.tech/paper/sample-attention-is-all-you-need";

async function main() {
  const errors = [];
  const log = (ok, msg) => {
    console.log(`${ok ? "ok" : "FAIL"}  ${msg}`);
    if (!ok) errors.push(msg);
  };

  await checkEpub(BASE, SAMPLE, log, { expectTitle: "成书", ncx: true });
  await checkMarkdown(BASE, HARD, log, {
    expectTitle: "Attention Is All You Need",
    mustInclude: ["Transformer", "Vaswani"],
    mustNotInclude: ["Loading refracted workspace"],
  });

  if (errors.length) {
    console.error(`\n${errors.length} failed`);
    process.exit(1);
  }
  console.log("\nall e2e checks passed");
}

async function checkEpub(base, sample, log, { expectTitle, ncx }) {
  const exportUrl = `${base}/export?format=epub&url=${encodeURIComponent(sample)}`;
  const res = await fetch(exportUrl, { headers: { "cache-control": "no-cache" } });
  const buf = Buffer.from(await res.arrayBuffer());
  log(res.ok, `GET /export epub → ${res.status}`);
  log((res.headers.get("content-type") || "").includes("application/epub+zip"), "content-type epub");
  const xtitle = decodeURIComponent(res.headers.get("x-title") || "");
  log(Boolean(xtitle) && xtitle.toLowerCase() !== "body", `X-Title=${xtitle || "?"}`);
  if (expectTitle) log(xtitle.includes(expectTitle), `title contains ${expectTitle}`);
  const cd = res.headers.get("content-disposition") || "";
  log(!/filename\*?=(?:UTF-8'')?"?body\b/i.test(cd), `content-disposition not body`);
  log(buf.subarray(0, 2).toString() === "PK", "epub is a zip");

  const zip = await JSZip.loadAsync(buf);
  log(Boolean(zip.file("OEBPS/toc.ncx")), "toc.ncx (WeChat Reading needs NCX)");
  log(!zip.file("OEBPS/nav.xhtml"), "no EPUB3 nav.xhtml in spine path");
  const opfName = Object.keys(zip.files).find((n) => n.endsWith(".opf"));
  log(Boolean(opfName), `opf ${opfName ?? "missing"}`);
  if (opfName) {
    const opf = await zip.file(opfName).async("string");
    log(opf.includes('version="2.0"'), "EPUB 2.0 package");
    const title = (opf.match(/<dc:title>([^<]*)<\/dc:title>/) || [])[1] || "";
    log(Boolean(title) && title.toLowerCase() !== "body", `dc:title=${title || "?"}`);
  }
  if (ncx && zip.file("OEBPS/toc.ncx")) {
    const ncxXml = await zip.file("OEBPS/toc.ncx").async("string");
    log(!/<text>\s*body\s*<\/text>/i.test(ncxXml), "ncx labels are not body");
    log(/<text>[^<]+<\/text>/.test(ncxXml), "ncx has a text label");
  }
  if (zip.file("OEBPS/chapter.xhtml")) {
    const chapter = await zip.file("OEBPS/chapter.xhtml").async("string");
    log(/<h1>[^<]+<\/h1>/.test(chapter), "chapter has h1 title");
    log(!/<body>\s*<p/.test(chapter) || /<body title="/.test(chapter), "body carries a title");
  }
}

async function checkMarkdown(base, sample, log, { expectTitle, mustInclude, mustNotInclude }) {
  const exportUrl = `${base}/export?format=md&url=${encodeURIComponent(sample)}`;
  const res = await fetch(exportUrl, { headers: { "cache-control": "no-cache" } });
  const text = await res.text();
  const xtitle = decodeURIComponent(res.headers.get("x-title") || "");
  log(res.ok, `GET /export md hard page → ${res.status}`);
  log(xtitle.includes(expectTitle), `X-Title=${xtitle || "?"}`);
  for (const needle of mustInclude) log(text.includes(needle), `md includes ${needle}`);
  for (const needle of mustNotInclude) log(!text.includes(needle), `md excludes ${needle}`);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
