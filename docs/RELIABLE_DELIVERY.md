# Reliable delivery / device EPUB — implementation and handoff

## Product contract

User goal: share a page and get a trustworthy reading result. Not a cloud library,
not a conversion settings dashboard. Preserve saved results before attempting any
reader handoff. Never represent an Intent launch as a confirmed reader import.

This development slice is based on `f47bedabd9ca759605d764c93ecbc245a1e28d7e`.
It is not a production release or a claim of completed reader/device acceptance.

## Implemented

- `PendingShares`: durable capture before consuming the share Intent. Unfinished
  links, selected format and failure state survive recreation. Foreground work is
  serialized per process because the WebView extractor is process-wide. A later
  article does not silently cancel a live extraction. Explicit retry remains
  available from recent items.
- `LocalArchive`: immutable SHA-256-addressed output blobs and per-article manifests.
  Sync file data and atomically rename a complete manifest only after writing blobs.
  File locking serializes read/modify/write across store instances and processes.
  No global index rewrite and no automatic 200-article eviction. Verify integrity
  before handing out a stored blob. Delete tombstones suppress legacy resurrection.
- `Library`: lazy old-index migration, source snapshots, independent format outputs
  and persisted quality warnings. A failed refresh cannot replace the previous
  successful title/output. Normal repeated shares reuse existing files without an
  arbitrary age window. Changing format reuses extracted source; explicit refresh
  re-extracts it. Source-only records are not shown as completed books.
- `LocalEpub`: compile EPUB 2 on device for compatibility with the existing reader
  target, including first uncompressed mimetype entry, OPF, hierarchical NCX,
  heading/footnote anchors, paragraphs, tables/lists/code, title, author and source.
  Strip active content and unsafe URLs. Images are deduplicated with four bounded
  in-flight fetches; limits are 4 MiB/image, 24 MiB total embedded images and a
  30-second image budget. There is no arbitrary 12-image cap. Missing images get
  explicit placeholders, persisted warnings and an opening notice. Interactive,
  vector or mathematical media that cannot be represented gets a notice, not a
  false completeness assertion. Device decoding converts supported non-EPUB raster
  formats to PNG with a 4-megapixel limit and one concurrent decode.
- EPUB does not POST article HTML to the conversion server. Fetching the source
  website and its images still uses the network. Other Android formats and the
  website remain server-generated; the format picker and README say so. Removed
  silent server re-fetch fallback after a failed local extraction or POST.
- Saved result and reader handoff are separate; failed reader launch keeps the file.
  Recent items provide the system ACTION_SEND share sheet as an unrestricted exit.
  Cached handoff names are scoped to output identity, preventing same-title articles
  from overwriting each other's shared cache URI. Only foreground completion may
  auto-open a reader. A remember-format checkbox is explicit and optional; the
  picker scrolls on short screens and large text.
- `preview` APK has its own application ID and storage; it never overwrites an
  installed stable build. Development builds do not publish the website APK.

## Verification commands

```sh
bash scripts/test-archive.sh
node --test scripts/chengshu-share.test.mjs
gradle -p android testDebugUnitTest assembleDebug assemblePreview
```

`ArchiveProof` executes real child processes, including `Runtime.halt(83)` after
writing new blobs but before manifest publication. It also exercises two writer
processes, many store instances/threads, >200 articles, corruption, failed writes,
tombstones, and durable share retry. This is not a mock filesystem test.

JUnit EPUB tests inspect real ZIP/XML outputs, navigation targets, more than twelve
images, image deduplication, missing/oversized images, total byte limits, active
content removal, paragraph structure and reading semantics. Snapshot tests cover
failed refresh, multi-format preservation and source-only pending results.

## Boundaries / next highest-value acceptance

- No WorkManager/foreground-service engine in this slice. Journal persistence does
  not guarantee automatic completion after process death, reboot or battery-policy
  termination. Reopen and explicitly retry. There is no reader import receipt.
- File fsync + atomic rename is tested for process interruption, not physical
  power-loss durability (directory fsync is not currently implemented). Old unused
  content-addressed blobs remain until explicit deletion; no bounded garbage
  collector yet. Large libraries still need indexed/paged presentation.
- Legacy index import is lazy and retains original files until explicit delete.
  A malformed old index is reported rather than overwritten. Migration and reading
  existing installations still need device-level acceptance with real old data.
- WebView uses its own cookie jar, not the browser's logged-in session. Authentication,
  anti-bot pages, paywalls, image Referer/cookie requirements may still prevent
  capture. Do not promise full capture; do not bypass access controls.
- Mathematical notation and interactive content are warned about, not fully
  supported. Metadata/title extraction is heuristic; no claim that every source
  article is complete. Very large image-heavy pages still require device memory
  and low-network acceptance. No latency or extraction-success-rate benchmark yet.
- Real Android/WeRead/KOReader acceptance is required before merging/releasing:
  fresh install; existing-data upgrade; same-title and duplicate shares; three
  consecutive shares; rotate/background/back; kill during extract/pack/save;
  offline reuse; missing images; no reader; picker cancellation; large text.
  Automated structure/build tests do not substitute for these scenarios.

Next product-shaped slice: an application-owned durable worker with foreground
notification and lifecycle tests, then first-run destination-first onboarding.
Do not implement more output formats or widen scope before closing those gates.
