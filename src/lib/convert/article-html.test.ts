import assert from "node:assert/strict";
import { test } from "node:test";
import { blockify, extractBlocks } from "./article-html.ts";

test("double <br> essays become many paragraphs", () => {
  const html = `<div>July 2023 <br> <br> If you collected lists of techniques.<br> <br> Partly my goal was to create a guide.<br> <br> The first step is to decide what to work on.</div>`;
  const xhtml = blockify(html);
  const ps = [...xhtml.matchAll(/<p>/g)].length;
  assert.ok(ps >= 4, `got ${ps} paragraphs: ${xhtml}`);
  assert.match(xhtml, /July 2023/);
  assert.match(xhtml, /first step/);
});

test("nested divs without <p> still split", () => {
  const html = `<div><div>第一段中文。</div><div>第二段还在。</div><div>第三段结尾。</div></div>`;
  const xhtml = blockify(html);
  assert.equal([...xhtml.matchAll(/<p>/g)].length, 3);
});

test("real <p> tags are kept", () => {
  const html = `<article><p>甲。</p><p>乙。</p><h2>节</h2><p>丙。</p></article>`;
  const xhtml = blockify(html);
  assert.match(xhtml, /<h2>节<\/h2>/);
  assert.equal([...xhtml.matchAll(/<p>/g)].length, 3);
});

test("linkedom-style fragment does not collapse to one block", () => {
  const blocks = extractBlocks("<p>一</p><p>二</p><p>三</p>");
  assert.equal(blocks.filter((b) => b.kind === "p").length, 3);
});
