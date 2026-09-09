import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { decidePublish, fileDigest, readGradleVersion } from "./publish-manifest.mjs";

const next = {
  versionCode: 13,
  versionName: "1.12",
  apk: "https://0nl.onl/chengshu.apk",
  sourceCommit: "aaa",
  sha256: "abc",
  size: 10,
  channel: "stable",
};

test("older completed run cannot overwrite a newer latest", () => {
  const existing = { ...next, versionCode: 14, sourceCommit: "bbb" };
  const decided = decidePublish(existing, next);
  assert.equal(decided.action, "skip-older");
  assert.equal(decided.manifest.versionCode, 14);
});

test("same source commit and hash reuses the published identity", () => {
  const decided = decidePublish(next, { ...next });
  assert.equal(decided.action, "reuse");
});

test("failed or missing latest is replaced by a higher code", () => {
  const decided = decidePublish({ versionCode: 12, sha256: "old" }, next);
  assert.equal(decided.action, "publish");
  assert.equal(decided.manifest.versionCode, 13);
});

test("gradle version parser requires both fields", () => {
  const v = readGradleVersion('versionCode = 13\nversionName = "1.12"\n');
  assert.deepEqual(v, { versionCode: 13, versionName: "1.12" });
  assert.throws(() => readGradleVersion("versionName = \"1.12\""));
});

test("CLI writes manifest only on publish and keeps exit 0 on skip", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "chengshu-pub-"));
  const apk = path.join(dir, "app.apk");
  const gradle = path.join(dir, "build.gradle.kts");
  const out = path.join(dir, "app.json");
  const copy = path.join(dir, "chengshu.apk");
  fs.writeFileSync(apk, "apk-bytes");
  fs.writeFileSync(gradle, 'versionCode = 13\nversionName = "1.12"\n');
  const run = (extra) =>
    spawnSync(process.execPath, ["scripts/publish-manifest.mjs", "--apk", apk, "--version-file", gradle, "--out", out, "--copy", copy, "--commit", "abc", ...extra], {
      encoding: "utf8",
      cwd: path.resolve("."),
    });
  const first = run([]);
  assert.equal(first.status, 0, first.stderr);
  assert.match(first.stdout, /"action":"publish"/);
  assert.equal(JSON.parse(fs.readFileSync(out, "utf8")).versionCode, 13);
  assert.equal(fs.readFileSync(copy, "utf8"), "apk-bytes");
  const digest = fileDigest(fs.readFileSync(apk));
  fs.writeFileSync(
    out,
    JSON.stringify({ versionCode: 14, sourceCommit: "zzz", sha256: digest, apk: "https://0nl.onl/chengshu.apk" }),
  );
  const skipped = run([]);
  assert.equal(skipped.status, 0, skipped.stderr);
  assert.match(skipped.stdout, /"action":"skip-older"/);
  assert.equal(JSON.parse(fs.readFileSync(out, "utf8")).versionCode, 14);
});
