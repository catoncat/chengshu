#!/usr/bin/env node
import JSZip from "jszip";
import { PDFDocument } from "pdf-lib";

const BASE = process.argv[2] || "http://127.0.0.1:8080";

const CORPUS = [
  {
    name: "成书",
    url: "https://0nl.onl/",
    title: "成书",
    minP: 4,
    minPdfPages: 2,
    mustInclude: ["微信读书"],
  },
  {
    name: "中文维基 EPUB",
    url: "https://zh.wikipedia.org/wiki/EPUB",
    title: "EPUB",
    minP: 12,
    minPdfPages: 6,
    mustInclude: ["电子"],
  },
  {
    name: "英文维基 EPUB",
    url: "https://en.wikipedia.org/wiki/EPUB",
    title: "EPUB",
    minP: 20,
    minPdfPages: 8,
    mustInclude: ["publication"],
  },
  {
    name: "Refract SPA 论文",
    url: "https://refract.aniketh.tech/paper/sample-attention-is-all-you-need",
    title: "Attention Is All You Need",
    minP: 8,
    minPdfPages: 4,
    mustInclude: ["Transformer", "Vaswani"],
    mustNotInclude: ["Loading refracted workspace"],
  },
  {
    name: "Paul Graham br-essay",
    url: "https://paulgraham.com/greatwork.html",
    title: "How to Do Great Work",
    minP: 20,
    minPdfPages: 8,
    mustInclude: ["ambitious"],
  },
];

async function main() {
  const errors = [];
  const log = (ok, msg) => {
    console.log(`${ok ? "ok" : "FAIL"}  ${msg}`);
    if (!ok) errors.push(msg);
  };

  for (const site of CORPUS) {
    console.log(`\n# ${site.name}`);
    await checkEpub(BASE, site, log);
    await checkPdf(BASE, site, log);
    await checkMarkdown(BASE, site, log);
  }
  await checkPackedHtml(BASE, log);

  if (errors.length) {
    console.error(`\n${errors.length} failed`);
    process.exit(1);
  }
  console.log("\nall e2e checks passed");
}

async function checkEpub(base, site, log) {
  const res = await fetch(
    `${base}/export?format=epub&url=${encodeURIComponent(site.url)}`,
    { headers: { "cache-control": "no-cache" } },
  );
  const buf = Buffer.from(await res.arrayBuffer());
  log(res.ok, `${site.name} epub ${res.status}`);
  if (!res.ok) return;
  const xtitle = decodeURIComponent(res.headers.get("x-title") || "");
  log(xtitle.toLowerCase() !== "body" && xtitle.includes(site.title), `${site.name} epub title=${xtitle || "?"}`);
  log(buf.subarray(0, 2).toString() === "PK", `${site.name} epub zip`);
  const zip = await JSZip.loadAsync(buf);
  log(Boolean(zip.file("OEBPS/toc.ncx")), `${site.name} ncx`);
  const chapter = await zip.file("OEBPS/chapter.xhtml")?.async("string");
  if (!chapter) {
    log(false, `${site.name} missing chapter`);
    return;
  }
  const pCount = (chapter.match(/<p(?:\s|>)/g) || []).length;
  log(pCount >= site.minP, `${site.name} epub paragraphs ${pCount} >= ${site.minP}`);
  const paras = [...chapter.matchAll(/<p[^>]*>([\s\S]*?)<\/p>/g)].map((m) =>
    m[1].replace(/<[^>]+>/g, ""),
  );
  const longest = paras.reduce((n, p) => Math.max(n, p.length), 0);
  log(longest < 8000, `${site.name} longest paragraph ${longest}`);
  for (const needle of site.mustInclude || []) {
    log(chapter.includes(needle), `${site.name} epub has ${needle}`);
  }
}

async function checkPdf(base, site, log) {
  const res = await fetch(
    `${base}/export?format=pdf&url=${encodeURIComponent(site.url)}`,
    { headers: { "cache-control": "no-cache" } },
  );
  const buf = Buffer.from(await res.arrayBuffer());
  log(res.ok, `${site.name} pdf ${res.status}`);
  if (!res.ok) return;
  log(buf.subarray(0, 5).toString() === "%PDF-", `${site.name} pdf magic`);
  const cd = res.headers.get("content-disposition") || "";
  log(!/filename="[^"]*\.epub"/i.test(cd), `${site.name} pdf not named .epub`);
  try {
    const doc = await PDFDocument.load(buf);
    const pages = doc.getPageCount();
    log(pages >= site.minPdfPages, `${site.name} pdf pages ${pages} >= ${site.minPdfPages}`);
    const title = doc.getTitle() || "";
    log(title.toLowerCase() !== "body" && title.includes(site.title), `${site.name} pdf doc title=${title || "?"}`);
  } catch (err) {
    log(false, `${site.name} pdf parse ${err instanceof Error ? err.message : err}`);
  }
}

async function checkMarkdown(base, site, log) {
  const res = await fetch(
    `${base}/export?format=md&url=${encodeURIComponent(site.url)}`,
    { headers: { "cache-control": "no-cache" } },
  );
  const text = await res.text();
  log(res.ok, `${site.name} md ${res.status}`);
  if (!res.ok) return;
  const paras = text.split(/\n{2,}/).filter((p) => p.trim());
  log(paras.length >= Math.min(site.minP, 6), `${site.name} md blocks ${paras.length}`);
  for (const needle of site.mustInclude || []) {
    log(text.includes(needle), `${site.name} md has ${needle}`);
  }
  for (const needle of site.mustNotInclude || []) {
    log(!text.includes(needle), `${site.name} md excludes ${needle}`);
  }
}

async function checkPackedHtml(base, log) {
  const html =
    "<p>这是客户端已经抽好的正文，服务端只负责打包成 Markdown，不必再去抓页面。</p><p>第二段还在这里。</p>";
  const res = await fetch(`${base}/export?format=md`, {
    method: "POST",
    headers: { "content-type": "application/json", "cache-control": "no-cache" },
    body: JSON.stringify({ title: "成书", html, url: "https://0nl.onl/" }),
  });
  const text = await res.text();
  log(res.ok, `POST packed html ${res.status}`);
  log(text.includes("已经抽好"), "packed body kept");
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
