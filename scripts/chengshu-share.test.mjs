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
