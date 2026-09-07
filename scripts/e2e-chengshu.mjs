#!/usr/bin/env node
import JSZip from "jszip";

const BASE = process.argv[2] || "https://0nl.onl";
const SAMPLE = "https://zh.wikipedia.org/wiki/EPUB";

async function main() {
  const errors = [];
  const log = (ok, msg) => {
    console.log(`${ok ? "ok" : "FAIL"}  ${msg}`);
    if (!ok) errors.push(msg);
  };

  const convertRes = await fetch(`${BASE}/api/convert`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ url: SAMPLE }),
  });
  const convert = await convertRes.json();
  log(convertRes.ok, `POST /api/convert → ${convertRes.status}`);
  log(typeof convert.title === "string" && convert.title.length > 0, `title: ${convert.title ?? "?"}`);
  log(typeof convert.epubBase64 === "string" && convert.epubBase64.length > 100, "epubBase64 present");
  log(typeof convert.html === "string" && convert.html.includes("<html"), "html document present");
  log(!String(convert.html || "").includes("/book.epub"), "html is not a book.epub link");

  const bytes = Buffer.from(convert.epubBase64 ?? "", "base64");
  log(bytes.subarray(0, 2).toString() === "PK", "epub is a zip");
  const zip = await JSZip.loadAsync(bytes);
  log(Boolean(zip.file("mimetype")), "mimetype entry");
  const mime = await zip.file("mimetype")?.async("string");
  log(mime === "application/epub+zip", `mimetype=${mime}`);
  log(Boolean(zip.file("META-INF/container.xml")), "container.xml");
  const opf = Object.keys(zip.files).find((n) => n.endsWith(".opf"));
  log(Boolean(opf), `opf ${opf ?? "missing"}`);

  const bookUrl = `${BASE}/book.epub?url=${encodeURIComponent(SAMPLE)}`;
  const epubRes = await fetch(bookUrl);
  const epubBuf = Buffer.from(await epubRes.arrayBuffer());
  log(epubRes.ok, `GET /book.epub → ${epubRes.status}`);
  log((epubRes.headers.get("content-type") || "").includes("application/epub+zip"), "content-type epub");
  log(epubBuf.subarray(0, 2).toString() === "PK", "GET /book.epub is zip");

  const home = await fetch(`${BASE}/`);
  const html = await home.text();
  const asset = html.match(/\/assets\/routes-[^"']+/);
  log(Boolean(asset), "routes asset");
  if (asset) {
    const js = await (await fetch(`${BASE}${asset[0]}`)).text();
    log(!js.includes("navigator.share({title:") && !/share\(\{[^}]*url:\s*[a-zA-Z.]*viewUrl/.test(js), "client does not share book.epub URL");
    log(js.includes("application/epub+zip") || js.includes("text/html"), "client shares a file");
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
