#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
src=android/app/src/main/java/onl/nl0/chengshu
javac --release 17 -d "$work/classes" "$src/LocalArchive.java" "$src/PendingShares.java" "$src/Failures.java" scripts/java/ArchiveProof.java
java -cp "$work/classes" onl.nl0.chengshu.ArchiveProof "$work/store"
