import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import test from "node:test";

test("android-ci.sh keeps pipefail so a failing command is not hidden by tee", () => {
  const src = fs.readFileSync("scripts/android-ci.sh", "utf8");
  assert.match(src, /set -euo pipefail/);
  assert.match(src, /\| tee /);
  const probe = spawnSync(
    "bash",
    ["-lc", "set -euo pipefail; false | tee /dev/null; echo survived"],
    { encoding: "utf8" },
  );
  assert.notEqual(probe.status, 0);
  assert.doesNotMatch(probe.stdout, /survived/);
});

test("npm test only runs files that exist in this repository", () => {
  const pkg = JSON.parse(fs.readFileSync("package.json", "utf8"));
  assert.doesNotMatch(pkg.scripts.test, /app-data\.test\.ts/);
  assert.doesNotMatch(pkg.scripts.test, /gate-identity\.test\.ts/);
  assert.match(pkg.scripts["check:auth"], /skipped/);
  assert.equal(fs.existsSync("scripts/check-auth-invariant.mjs"), false);
});
