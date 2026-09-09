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
  assert.match(activity, /showShareConfirm\(job\.url/);
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

test("share extracts with Defuddle, locally compiles EPUB, and POSTs PDF", () => {
  const extractor = fs.readFileSync(
    "android/app/src/main/java/onl/nl0/chengshu/PageExtractor.java",
    "utf8",
  );
  const coordinator = fs.readFileSync(
    "android/app/src/main/java/onl/nl0/chengshu/ConversionCoordinator.java",
    "utf8",
  );
  assert.match(extractor, /setJavaScriptEnabled\(true\)/);
  assert.match(extractor, /defuddle\.js/);
  assert.match(extractor, /new C\(document/);
  assert.match(activity, /PageExtractor\.extract/);
  assert.match(activity, /coordinator\.enqueueSavedSnapshot/);
  assert.match(activity, /coordinator\.runInline/);
  assert.match(coordinator, /LocalEpub\.build/);
  assert.match(coordinator, /LocalPack\.build/);
  assert.match(activity, /postPack/);
  assert.doesNotMatch(activity, /getExport|SHARE_FRESH_MS/);
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



test("durable capture precedes consuming the share Intent", () => {
  const capture = activity.indexOf("PendingShares.Job job = inbox.capture");
  assert.ok(capture >= 0 && capture < activity.indexOf("clearShareIntent(); // Intent consumed"));
  assert.match(activity, /library\.saveSnapshot/);
  assert.match(activity, /inbox\.complete\(job\); \/\/ Acknowledgement/);
  assert.doesNotMatch(library, /items\.size\(\) > 200/);
});

test("empty image src is not turned into the article URL", () => {
  const epub = fs.readFileSync(
    "android/app/src/main/java/onl/nl0/chengshu/LocalEpub.java",
    "utf8",
  );
  assert.match(epub, /static String firstImageSource/);
  assert.match(epub, /static String resolvedImageUrl/);
  assert.match(epub, /static String pickSrcset/);
  assert.match(epub, /static boolean sameDocument/);
  assert.doesNotMatch(epub, /image\.absUrl\("src"\)/);
  assert.match(fs.readFileSync("android/app/build.gradle.kts", "utf8"), /versionCode = 19/);
});

test("txt markdown and html pack on device; pdf still posts extracted html", () => {
  const coordinator = fs.readFileSync(
    "android/app/src/main/java/onl/nl0/chengshu/ConversionCoordinator.java",
    "utf8",
  );
  assert.match(coordinator, /LocalPack\.build/);
  assert.match(activity, /Format\.PDF/);
  assert.match(activity, /postPack/);
  assert.match(activity, /runExample/);
  assert.match(activity, /exportBackup/);
  assert.match(activity, /importBackup/);
  assert.match(activity, /pickBackup/);
  assert.match(activity, /ChengshuNotify\.show/);
  assert.match(activity, /!resumed \|\| isDestroyed/);
  assert.match(activity, /attentionHeader/);
  assert.match(activity, /saveItemToFolder/);
  assert.match(activity, /ACTION_CREATE_DOCUMENT/);
  assert.match(activity, /setContentDescription\("更多操作"\)/);
  assert.match(activity, /setMinimumHeight\(dp\(64\)\)/);
  assert.doesNotMatch(activity, /bar\.setMinHeight/);
  assert.match(activity, /imageCache::load/);
  assert.match(activity, /Failures\.message/);
  assert.match(activity, /QualityReport\.evaluate/);
  assert.match(activity, /openOriginal/);
  assert.match(activity, /job\.error/);
  assert.match(fs.readFileSync("android/app/src/main/java/onl/nl0/chengshu/LocalEpub.java", "utf8"), /addAttributes\("ol", "start"/);
  assert.match(fs.readFileSync("android/app/src/main/java/onl/nl0/chengshu/LocalEpub.java", "utf8"), /promotePictureSources/);
  assert.match(fs.readFileSync("android/app/src/main/java/onl/nl0/chengshu/Failures.java", "utf8"), /NETWORK_UNAVAILABLE/);
  assert.match(fs.readFileSync("android/app/src/main/java/onl/nl0/chengshu/PendingShares.java", "utf8"), /fail\(Job job, String code\)/);
  assert.match(fs.readFileSync("scripts/test-archive.sh", "utf8"), /Failures\.java/);
  assert.match(fs.readFileSync("android/app/src/main/res/layout/activity_share.xml", "utf8"), /tryExample/);
  assert.match(fs.readFileSync("android/app/src/main/res/layout/activity_share.xml", "utf8"), /rowBackup/);
  assert.match(fs.readFileSync("android/app/src/main/res/layout/activity_share.xml", "utf8"), /rowRestore/);
  assert.match(fs.readFileSync("android/app/src/main/res/layout/activity_share.xml", "utf8"), /attentionHeader/);
  assert.match(fs.readFileSync("android/app/src/main/res/layout/activity_share.xml", "utf8"), /openOriginal/);
  assert.match(fs.readFileSync("android/app/src/main/AndroidManifest.xml", "utf8"), /POST_NOTIFICATIONS/);
  assert.equal(fs.existsSync("android/app/src/main/java/onl/nl0/chengshu/ResultsNotifier.java"), true);
  assert.equal(fs.existsSync("android/app/src/main/java/onl/nl0/chengshu/EpubInspect.java"), true);
  assert.equal(fs.existsSync("android/app/src/main/assets/example-article.html"), true);
  assert.match(fs.readFileSync("android/app/src/main/java/onl/nl0/chengshu/Update.java", "utf8"), /sha256/);
});

