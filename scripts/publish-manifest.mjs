import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";

export function decidePublish(existing, next) {
  const have = existing && typeof existing === "object" ? existing : null;
  const haveCode = have ? Number(have.versionCode) || 0 : 0;
  if (have && haveCode > next.versionCode) {
    return { action: "skip-older", manifest: have };
  }
  if (
    have &&
    have.sourceCommit === next.sourceCommit &&
    haveCode === next.versionCode &&
    have.sha256 === next.sha256
  ) {
    return { action: "reuse", manifest: have };
  }
  return { action: "publish", manifest: next };
}

export function readGradleVersion(text) {
  const code = Number((text.match(/versionCode\s*=\s*(\d+)/) || [])[1] || 0);
  const name = (text.match(/versionName\s*=\s*"([^"]+)"/) || [])[1] || "";
  if (!code || !name) throw new Error("build.gradle.kts 缺少 versionCode/versionName");
  return { versionCode: code, versionName: name };
}

export function fileDigest(bytes) {
  return crypto.createHash("sha256").update(bytes).digest("hex");
}

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    if (argv[i].startsWith("--") && i + 1 < argv.length) out[argv[i].slice(2)] = argv[++i];
  }
  return out;
}

function main(argv = process.argv.slice(2)) {
  const args = parseArgs(argv);
  if (!args.apk) return null;
  const bytes = fs.readFileSync(args.apk);
  const gradle = fs.readFileSync(args["version-file"] || "android/app/build.gradle.kts", "utf8");
  const { versionCode, versionName } = readGradleVersion(gradle);
  let existing = null;
  if (args.out && fs.existsSync(args.out)) {
    try {
      existing = JSON.parse(fs.readFileSync(args.out, "utf8"));
    } catch {
      existing = null;
    }
  }
  const next = {
    versionCode,
    versionName,
    apk: args.url || "https://0nl.onl/chengshu.apk",
    sourceCommit: args.commit || "",
    sha256: fileDigest(bytes),
    size: bytes.length,
    channel: args.channel || "stable",
  };
  const decided = decidePublish(existing, next);
  if (decided.action === "publish") {
    fs.mkdirSync(path.dirname(args.out || "public/app.json"), { recursive: true });
    if (args.copy) {
      fs.mkdirSync(path.dirname(args.copy), { recursive: true });
      fs.copyFileSync(args.apk, args.copy);
    }
    if (args.release) {
      fs.mkdirSync(path.dirname(args.release), { recursive: true });
      fs.copyFileSync(args.apk, args.release);
    }
    fs.writeFileSync(args.out, JSON.stringify(decided.manifest, null, 2) + "\n");
  }
  process.stdout.write(JSON.stringify(decided) + "\n");
  return decided;
}

if (process.argv[1] && process.argv[1].endsWith("publish-manifest.mjs") && process.argv.includes("--apk")) {
  main();
}
