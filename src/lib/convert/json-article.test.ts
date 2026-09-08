import assert from "node:assert/strict";
import { test } from "node:test";
import { articleFromUnknown, isThinHtml, jsonCandidateUrls } from "./json-article.ts";

test("SPA paper URL maps to /api/papers/{id}", () => {
  const urls = jsonCandidateUrls(
    "https://refract.aniketh.tech/paper/sample-attention-is-all-you-need",
  );
  assert.ok(urls.includes("https://refract.aniketh.tech/api/papers/sample-attention-is-all-you-need"));
  assert.ok(urls.every((u) => u.startsWith("https://refract.aniketh.tech/")));
});

test("Next.js loading shell counts as thin HTML", () => {
  const html = `<!doctype html><html><body>
    <div id="__next"><p>Loading refracted workspace...</p></div>
    <script>self.__next_f=self.__next_f||[]</script>
  </body></html>`;
  assert.equal(isThinHtml(html), true);
  assert.equal(isThinHtml(`<article><p>${"paragraph ".repeat(80)}</p></article>`), false);
});

test("paper JSON becomes titled HTML, never a spinner", () => {
  const draft = articleFromUnknown(
    {
      paper: {
        title: "Attention Is All You Need",
        authors: ["Ashish Vaswani", "Noam Shazeer"],
        abstract: "We propose the Transformer, based solely on attention.",
        venue: "NeurIPS 2017",
        sections: [
          {
            title: "Introduction",
            content: [
              { type: "paragraph", text: "Recurrent neural networks are sequential." },
              { type: "paragraph", text: "Attention connects encoder and decoder." },
            ],
          },
        ],
        figures: [{ caption: "The Transformer", imagePath: "/transformer-arch.png" }],
      },
    },
    "https://refract.aniketh.tech/paper/sample-attention-is-all-you-need",
  );
  assert.ok(draft);
  assert.equal(draft.title, "Attention Is All You Need");
  assert.match(draft.byline, /Vaswani/);
  assert.match(draft.content, /<h2>Abstract<\/h2>/);
  assert.match(draft.content, /<h2>Introduction<\/h2>/);
  assert.match(draft.content, /Transformer/);
  assert.match(draft.content, /https:\/\/refract\.aniketh\.tech\/transformer-arch\.png/);
  assert.doesNotMatch(draft.content, /Loading/);
  assert.notEqual(draft.title.toLowerCase(), "body");
});
