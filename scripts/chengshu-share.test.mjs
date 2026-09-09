import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";

const activity = fs.readFileSync(
  "android/app/src/main/java/onl/nl0/chengshu/ShareActivity.java",
  "utf8",
);
const flow = fs.readFileSync(
  "android/app/src/main/java/onl/nl0/chengshu/ShareFlow.java",
  "utf8",
);
const manifest = fs.readFileSync("android/app/src/main/AndroidManifest.xml", "utf8");
const library = fs.readFileSync(
  "android/app/src/main/java/onl/nl0/chengshu/Library.java",
  "utf8",
);

test("share handoff never calls finishAndRemoveTask", () => {
  assert.doesNotMatch(activity, /finishAndRemoveTask/);
  assert.match(flow, /static boolean removeTaskAfterOpen\(\) \{\s*return false;/);
});

test("fresh ACTION_SEND is not dropped on recreate", () => {
  assert.doesNotMatch(activity, /savedInstanceState != null \|\| fromRecents/);
  assert.match(activity, /ShareFlow\.shouldConvertShare/);
});

test("chooser keeps the activity alive so the picker is not killed", () => {
  assert.match(activity, /ShareFlow\.finishActivityAfterOpen\(chooser\)/);
  assert.match(activity, /Intent\.createChooser/);
  assert.match(activity, /picker\.addFlags\(Intent\.FLAG_GRANT_READ_URI_PERMISSION\)/);
  assert.match(
    flow,
    /static boolean finishActivityAfterOpen\(boolean openedChooser\) \{[\s\S]*?return false;/,
  );
});

test("shared files are never stored as body.ext", () => {
  assert.doesNotMatch(library, /new File\(dir, "body"/);
  assert.match(library, /fileStem/);
  assert.match(activity, /titledCopy/);
});

test("recents relaunch is ignored only via LAUNCHED_FROM_HISTORY", () => {
  assert.match(flow, /FLAG_LAUNCHED_FROM_HISTORY = 0x00100000/);
  assert.match(flow, /FLAG_NEW_TASK = 0x10000000/);
  assert.doesNotMatch(manifest, /excludeFromRecents/);
});

test("share is one entry then a format picker, not four share targets", () => {
  assert.match(activity, /showShareConfirm\(pageUrl/);
  assert.match(activity, /startShareConvert/);
  assert.match(flow, /static boolean autoConvertOnShare\(boolean formatAsk\) \{\s*return !formatAsk;/);
  const aliases = (manifest.match(/activity-alias/g) || []).length;
  assert.equal(aliases, 0);
});

test("dest discovery probes a content FileProvider URI like the real open", () => {
  const apps = fs.readFileSync("android/app/src/main/java/onl/nl0/chengshu/Apps.java", "utf8");
  assert.match(apps, /FileProvider\.getUriForFile/);
  assert.match(apps, /viewProbe/);
  assert.match(apps, /FLAG_GRANT_READ_URI_PERMISSION/);
  assert.match(apps, /org\.koreader\.launcher/);
});

test("format settings include ask-every-time", () => {
  assert.match(activity, /labels\[0\] = "每次询问"/);
  assert.match(activity, /formatIsAsk/);
  assert.match(activity, /migrated_format_ask/);
});

test("android offers PDF next to EPUB", () => {
  const format = fs.readFileSync(
    "android/app/src/main/java/onl/nl0/chengshu/Format.java",
    "utf8",
  );
  assert.match(format, /static final Format PDF/);
  assert.match(format, /application\/pdf/);
  assert.match(manifest, /application\/pdf/);
});

test("share extracts in a WebView with Defuddle then POSTs HTML to pack", () => {
  const extractor = fs.readFileSync(
    "android/app/src/main/java/onl/nl0/chengshu/PageExtractor.java",
    "utf8",
  );
  assert.match(extractor, /setJavaScriptEnabled\(true\)/);
  assert.match(extractor, /defuddle\.js/);
  assert.match(extractor, /new C\(document/);
  assert.match(activity, /PageExtractor\.extract/);
  assert.match(activity, /postPack/);
  assert.match(activity, /"html"/);
  assert.equal(fs.existsSync("android/app/src/main/assets/defuddle.js"), true);
});


test("server export accepts already-extracted HTML", () => {
  const exportTs = fs.readFileSync("src/routes/export.ts", "utf8");
  const pipe = fs.readFileSync("src/lib/convert/pipeline.server.ts", "utf8");
  assert.match(exportTs, /POST:/);
  assert.match(exportTs, /str\("html"\)/);
  assert.match(pipe, /input\.html\?\.trim\(\)/);
  assert.match(pipe, /async function extract\(/);
});

test("PDF pack parses article HTML as a full document so body is not dropped", () => {
  const pack = fs.readFileSync("src/lib/convert/pdf-pack.ts", "utf8");
  assert.match(pack, /extractBlocks/);
  const exportTs = fs.readFileSync("src/routes/export.ts", "utf8");
  assert.match(exportTs, /book\$\{ext/);
  assert.doesNotMatch(exportTs, /book\.epub"/);
});

test("e2e corpus covers SPA, wiki, and br-separated essays", () => {
  const e2e = fs.readFileSync("scripts/e2e-chengshu.mjs", "utf8");
  assert.match(e2e, /paulgraham\.com\/greatwork/);
  assert.match(e2e, /zh\.wikipedia\.org\/wiki\/EPUB/);
  assert.match(e2e, /refract\.aniketh\.tech/);
  assert.match(e2e, /minP/);
  assert.match(e2e, /minPdfPages/);
  const pipe = fs.readFileSync("src/lib/convert/pipeline.server.ts", "utf8");
  assert.match(pipe, /blockify\(/);
});


