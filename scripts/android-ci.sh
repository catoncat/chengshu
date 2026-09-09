#!/usr/bin/env bash
# Product Android gate. Failure must be visible; tee must not swallow the exit code.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
mkdir -p "${RUNNER_TEMP:-/tmp}/chengshu-ci"
logdir="${RUNNER_TEMP:-/tmp}/chengshu-ci"
bash scripts/test-archive.sh 2>&1 | tee "$logdir/archive-proof.log"
node --test scripts/chengshu-share.test.mjs scripts/publish-manifest.test.mjs 2>&1 | tee "$logdir/share-proof.log"
gradle -p android testDebugUnitTest assembleDebug assemblePreview 2>&1 | tee "$logdir/android-build.log"
