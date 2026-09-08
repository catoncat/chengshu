#!/usr/bin/env node
import JSZip from "jszip";

const BASE = process.argv[2] || "http://127.0.0.1:8080";
const SAMPLE = process.argv[3] || "https://0nl.onl/";

async function main() {
  const errors = [];
  const log = (ok, msg) => {
    console.log(`${ok ? "ok" : "FAIL"}  ${msg}`);
    if (!ok) errors.push(msg);
  };

  const exportUrl = `${BASE}/export?format=epub&url=${encodeURIComponent(SAMPLE)}`;
  const res = await fetch(exportUrl, { headers: { "cache-control": "no-cache" } });
  const buf = Buffer.from(await res.arrayBuffer());
  log(res.ok, `GET /export → ${res.status}`);
  log((res.headers.get("content-type") || "").includes("application/epub+zip"), "content-type epub");
  const xtitle = decodeURIComponent(res.headers.get("x-title") || "");
  log(Boolean(xtitle) && xtitle.toLowerCase() !== "body", `X-Title=${xtitle || "?"}`);
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
  if (zip.file("OEBPS/toc.ncx")) {
    const ncx = await zip.file("OEBPS/toc.ncx").async("string");
    log(!/<text>\s*body\s*<\/text>/i.test(ncx), "ncx labels are not body");
    log(/<text>[^<]+<\/text>/.test(ncx), "ncx has a text label");
  }
  if (zip.file("OEBPS/chapter.xhtml")) {
    const chapter = await zip.file("OEBPS/chapter.xhtml").async("string");
    log(/<h1>[^<]+<\/h1>/.test(chapter), "chapter has h1 title");
    log(!/<body>\s*<p/.test(chapter) || /<body title="/.test(chapter), "body carries a title");
  }

  if (errors.length) {
    console.error(`\n${errors.length} failed`);
    process.exit(1);
  }
  console.log("\nall e2e checks passed");
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
