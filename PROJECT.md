# PROJECT.md — Decisions and why

Companion to BUILD.md. Records what was decided and the reasoning, so nobody re-litigates it later.

## Product

- **It is a gallery, not a compressor with a UI.** People open galleries daily; that's where "Freed X GB" belongs, and the default-gallery role eases write permissions. The optimiser is a feature inside it.
- **Pitch:** everything Google Photos does, on the phone, no cloud — and it pays for itself by freeing space.
- **Priority of value:** video first (~85% of freed space), photos second, screenshots third. Nothing else is worth compressing.

## Access

- SAF folder grants by default; All files access is a later opt-in. Play policy risk and privacy story both favour SAF. Chat media folders are grantable; Downloads is not.

## Quality and reversibility

- Video and photo compression is visually lossless, not lossless. Data is discarded. The app must never say otherwise.
- Reversibility is provided by keeping originals: undo bin, offload to SD/USB, or a delete-after-window. Default is offload when external storage exists, otherwise 30-day window.
- Truly lossless paths (PNG repack, JPEG XL recompress) need no quality gate.
- Skip HDR video, Motion Photos, Ultra HDR, RAW in v1 — the metrics aren't calibrated for them and re-encoding destroys embedded data.

## Speed

- The encoder is not the bottleneck; the quality metric is. So: XPSNR for search (10–20× cheaper than VMAF), VMAF only for verification on sampled windows, and a per-camera predictor table that collapses the search to one probe after ~20 files.
- Decode probe windows once and cache. Pipeline encoder/decoder/CPU in parallel.
- Media3 Transformer instead of hand-written MediaCodec plumbing. Milestone 1 is days, not weeks.

## Battery and thermal

- Charging-only, enforced by the OS (WorkManager constraints), not by app logic. Unplug = job dies.
- Wait for battery full before starting. Pause on thermal headroom, not thermal status, so charging speed isn't hurt and the encoder never throttles.
- Report energy per night from a bench-measured table; live BatteryManager readings are unreliable while charging.

## Files in use

- Originals are only read until the final rename; players keep working through the swap. Background codec priority means foreground apps reclaim the hardware; the worker waits and resumes. Edits during an encode are detected by size/mtime and the job is redone.
- Play-to-compress (tee decoded frames into the encoder in our own player) is the one on-battery path, and only on explicit user action.

## Cleanup features

- Duplicates and chat media review are in. Cache cleaning and Downloads are out: apps cannot clear other apps' caches since Android 11, and cleaner apps making that claim have been removed from Play.

## Gallery features (from what makes existing galleries sticky)

- v1: content search, people & pets, text in photos, trash/favourites/locked folder, basic editing incl. video trim, auto-albums, Motion Photo and Ultra HDR playback.
- v1.1: Memories / On this day, Map view.
- Later: on-device object removal, cast, shared albums.
- All indexing runs on-device in the same night pass as encoding; one index powers search, people, auto-albums, duplicates and memories.

## Codec facts that shaped the spec

- MediaMuxer cannot write fragmented MP4; use standard MP4.
- `BITRATE_MODE_CQ` is not universally supported; search on bitrate.
- HDR encode needs Main10/10-bit surfaces and is patchy; skipped in v1.
- Hardware HEVC is less efficient than x265; expect 30–45% on phone H.264, not 50%.
- Replacing a file with a rename to the same path usually preserves the MediaStore row; reset lastModified and rescan.

## Open questions

- Exact XPSNR threshold that maps to VMAF 95 on hardware HEVC — **method established in
  milestone 2** (`shared/native/calibration/`, first point: XPSNR y ≈ 39.8 under x265);
  still needs running on device across real content before a constant goes into the search.
- Whether `MediaTranscodingManager` beats the in-app pipeline on any target device — benchmark in milestone 13.
- Which small face-embedding model to use for people clustering. **Still open, and now
  load-bearing:** `MlKitIndexer` returns normalised landmark geometry in the embedding's
  place, which exercises the whole path but is not a face embedding. `FaceClustering`'s 0.72
  threshold was chosen for the properties of a real embedding and will need re-tuning
  against whichever model is picked.

---

# Decisions made while building (newest last)

Recorded per ARCHITECTURE.md § 16. Each entry is a choice that was not already settled
by BUILD.md, PROJECT.md above, STACK.md or ARCHITECTURE.md, or is a deviation those
documents allow with a note.

## Cross-platform stack

- **Koin instead of Hilt.** ARCHITECTURE.md § 3 allows the swap "if Hilt doesn't fit
  KMP", and it does not: Hilt is Dagger-based, generates JVM bytecode, and has no
  Kotlin/Native backend, so it cannot wire `shared/` on iOS. Koin is a runtime
  container that works on every KMP target. The cost is losing compile-time graph
  validation; the mitigation is that the graph is small and lives in one module per
  platform (`androidApp/di`). STACK.md's Hilt row is superseded — Hilt has been removed
  from the version catalog so it cannot be applied by accident.
- **SQLDelight, not Room.** ARCHITECTURE.md § 4 names SQLDelight and requires one schema
  on every platform; Room's KMP support has no Kotlin/Native iOS target. SQLDelight also
  generates from the `.sq` schema, which keeps ARCHITECTURE.md § 4 and the code in step.
  `verifyMigrations` is on: the database outlives any single release.
- **Compose Multiplatform** added for `shared/core/ui` and every `shared/feature/*`, per
  ARCHITECTURE.md § 11. Android-only Compose remains in `androidApp` for the host
  Activity.
- These three are additions to STACK.md, which is otherwise the only approved library
  list. They are named explicitly by ARCHITECTURE.md and were confirmed before adding;
  STACK.md now carries a "Cross-platform (KMP)" section recording them.

## Module and build shape

- **A `jvm()` target alongside `androidTarget()` on every shared module.**
  ARCHITECTURE.md § 14 asks for "shared JVM unit tests"; without a JVM target those
  would run only through `testDebugUnitTest`, which needs an Android SDK. With it, CI
  gates on `./gradlew sharedTest` in seconds and with no SDK.
- **iOS targets are declared only when building on a Mac.** Kotlin/Native iOS targets
  cannot be configured on Linux, and Linux CI is what runs the shared tests today. iOS
  is v1.5 (§ 1), so nothing is lost yet. It does mean the resolved source-set graph
  differs by host, which has to be revisited when iOS work actually starts.
- **The three build guards are plain-JVM Gradle tasks, not Android Lint rules.** Lint
  rules need `lint-api` from Google Maven and only run inside an Android build; the
  guards need to run first, everywhere, including on the iOS plists. They live in the
  `build-logic` included build with no AGP on the classpath, and have 39 unit and Gradle
  TestKit tests — including tests that a violating build actually fails.
- **The no-network guard has two halves.** The manifest/plist scan proves nothing was
  *declared*; a source scan for networking APIs proves nothing was *written*.
  ARCHITECTURE.md § 6 asks for both ("no URLSession usage lint"). The source rule has an
  empty allow-list.
- **`UndoStore` implementations sit inside the `Replacer` write boundary.**
  ARCHITECTURE.md § 14 says writes happen only in `Replacer`, but the § 7 contract has
  the Replacer *park the original*, which is `UndoStore`'s job. So the guard's allow-list
  is `SafeReplacer{Android,Ios}` plus `UndoBin{Android,Ios}` and nothing else.
- **The guard fails when it finds nothing to scan.** A guard that passes because it was
  pointed at an empty file set is worse than no guard, since it reads as green.

## Milestone 1

- **`VideoEncoderSettings.setEncoderPerformanceParameters(operatingRate, priority)`** is
  how background codec priority reaches MediaCodec through Media3; there is no way to
  push an arbitrary `MediaFormat` key through Transformer. `background = false` is
  reserved for play-to-compress, the one on-battery path.
- **`setEnableFallback(false)`** on the encoder factory. Media3 otherwise falls back to
  another encoder, which on a device with no hardware HEVC encoder means a software one.
  Failing the export is correct: the file is skipped with a reason (BUILD.md § 2.2).
- **The golden clip carries a real AAC track.** `shared/testdata/golden-h264-640x360-3s.mp4`
  is H.264 + AAC, 3 s, because milestone 1's audio-passthrough requirement cannot be
  tested against a silent file.
- **Triage thresholds are expressed per megapixel.** BUILD.md § 5 gives two points
  ("~12 Mbps at 1080p / ~30 Mbps at 4K"); storing bits-per-megapixel interpolates between
  and beyond them instead of special-casing two resolution labels.

## Front end

- **The buyer-gallery React prototype does not ship.** ARCHITECTURE.md § 11 requires
  Compose Multiplatform for every screen, and the prototype's subject (a ceramics shop's
  review gallery) is not this app's. It is parked under `design/buyer-gallery/` as an
  executable motion spec for the gallery shell at milestone 8;
  `design/buyer-gallery/README.md` maps each behaviour onto BUILD.md § 9.

## Catalog verification (30 Aug 2026)

`tools/verify-versions.sh` was run. Result: **60 pinned coordinates — 35 resolved, 0
missing, 25 unchecked, 0 behind latest stable**, plus 6 entries whose version comes from
the Compose BOM.

- Every one of the 25 unchecked entries is on Google Maven, which this environment's
  egress policy still refuses. Confirmed a gateway policy denial (403 on CONNECT,
  recorded by the proxy) rather than a tooling fault, so it was not worked around.
  Those versions remain best-known-good guesses and must be confirmed before the first
  build.
- Everything on Maven Central and the Gradle Plugin Portal was verified at the **exact
  pinned coordinate**, not merely at group level — which is what catches a typo'd
  artifact name. All 35 resolved, and none is behind its latest stable.

Changes made as a result:

- **KSP removed.** It was in the catalog only for Hilt and Room, both of which are gone
  (Koin and SQLDelight replaced them). Nothing applied the plugin.
- **Kotlin 2.3.21 → 2.4.10.** The old pin existed solely because KSP 2.3.11 was the
  newest KSP available; with KSP gone that constraint disappeared. JetBrains states that
  "the latest Compose Multiplatform is always compatible with the latest version of
  Kotlin" and that the Compose compiler plugin ships with Kotlin, so CMP 1.12.0 and
  Kotlin 2.4.10 are deliberately not pinned to each other. The shared sources were
  recompiled under 2.4.10 and the `Triager` tests still pass.
- **`room`, `navigationCompose` and `espresso` version pins removed** — superseded by
  SQLDelight, the JetBrains multiplatform navigation artifact, and nothing respectively.
- **Eight STACK.md libraries had a version pinned but no coordinate** (Telephoto, Lottie,
  Accompanist, LiTr, SimpleStorage, mp4parser, JImageHash, LiteRT). A version with no
  artifact cannot be checked and quietly rots, so each now has a full coordinate,
  annotated with the milestone it lands in.
- **`verify-versions.sh` rewritten to parse the catalog** instead of carrying its own
  list. The hand-maintained list had already drifted within a single commit — it still
  named Hilt and Room and knew nothing of the KMP additions. It now also distinguishes
  *missing* (the repository answered and does not have it — a catalog bug, exit 1) from
  *unchecked* (the repository was unreachable — a network problem, exit 0), because
  conflating the two is what made the first run look like 25 broken entries.

Detekt stays at 1.23.8: it is the newest release, and there is no K2-era line, so it does
not constrain the Kotlin choice in either direction.

## Milestone 8 — gallery shell

- **No Material 3.** `shared/core/ui` is built on Compose foundation. BUILD.md § 9
  specifies the look directly — dark by default, media on near-black, *one typeface, one
  accent colour* — and reaching it through Material would mean overriding its theming,
  dynamic colour and component defaults at every turn. A gallery is bespoke surfaces over
  photographs; there is little for Material to contribute and a lot for it to get in the
  way of. It also removed a dependency whose stable coordinate does not exist at the
  pinned Compose Multiplatform version (`org.jetbrains.compose.material3:material3:1.12.0`
  is not published; only alphas are).
- **The palette is Trim Gallery's own, not the reference's.** The buyer-gallery prototype
  is cream and light-first; BUILD.md § 9 is dark-first on near-black. What was ported is
  the *motion*, which is what the reference was for. The token *structure* is kept
  (page / band / card / text / muted / line / accent) so the two read as relatives.
  Near-black rather than pure black, because #000000 swallows a photo's own dark tones
  and erases the boundary between chrome and image on OLED.
- **The theme does not follow the system setting.** A gallery that did would show the
  user's photos on a different ground each morning. Dark is the default and a setting
  flips it.
- **Motion lives in plain Kotlin, composables consume it.** `MotionSpec`, `HeroGeometry`,
  `TilePhase` and `TrimPalette` have no Compose types, so the timings, the transition
  arithmetic and the colour contrasts are unit tested on the JVM (39 tests) and the same
  numbers drive Android and iOS instead of being retyped per platform.
- **The hero transition is hand-rolled, not `SharedTransitionLayout`.** A single 0..1
  progress drives one interpolated rectangle, so the image travels as one shape rather
  than four independently animated edges, and an overshoot easing past 1 stays coherent.
  It also avoids depending on an experimental API at a version that could not be
  compiled here. Revisit once the build runs.
- **Tile artwork is a slot.** `GalleryScreen` takes `artwork: @Composable (MediaItem) ->
  Unit`, so `shared/feature/gallery` depends on no image loader and no platform decoder.
  The Android host wires the real thumbnail pipeline; a preview or test passes a flat
  colour.
- **The per-tile breathing phase needed a real hash.** The first implementation, in both
  the React reference and the port, was `hash * 31 + char`, which gives sequential ids
  hashes about 31 apart — so `% 4600` mapped neighbouring tiles to phases 31 ms apart out
  of 4600. Distinct on paper, identical to the eye, and the entire point of the offset is
  that neighbours must not pulse together. Now FNV-1a plus a murmur3 finalizer, in both
  codebases. Caught by a test asserting the phases *spread*, not merely that they differ.

## Milestone 8 — the rest of the gallery shell

- **`MediaItem` gains `favourite` and `locked`; `Album` and `AlbumMember` tables added.**
  A deviation from the ARCHITECTURE.md § 4 schema, recorded here as § 3 requires.
  Favourites and the locked folder are both screens BUILD.md § 9 asks for, and both need
  to be readable without a join: locked items are excluded from *every* other view — grid,
  albums, search, people — so every query filters on it. A column rather than an album
  membership because a favourite is a property of the item, survives album deletion, and
  is what every gallery the user has already used does.
- **Auto-albums are rules, not stored membership.** `AutoAlbums` classifies from labels,
  filename, folder and camera facing. An album is then always consistent with what the
  indexer last saw, costs nothing to keep in step, and cannot rot when an item is
  re-indexed after an edit. Two rules worth naming: filename beats label for screenshots
  (a screenshot *of* a photograph is labelled as whatever it depicts), and a screenshot of
  a receipt is filed as a screenshot only — filing it in Documents as well would make
  Documents useless.
- **Selfies need the front camera, not a label.** Labels alone put every portrait of
  another person in there.
- **Grid densities are 3 / 5 / 9 columns** for day / month / year, with a 1.35× deadband
  on the pinch and never more than one level per gesture. Without the deadband the grid
  flickers while the fingers are still moving; skipping a level loses the user's place.
- **Undated items get their own section rather than being hidden.** A photo copied from a
  backup often has no EXIF date; showing it under "No date" is better than losing it.
- **The fast-scroll bar spaces its labels by item position, not by section.** A holiday
  week can hold more photos than a quiet year, so taking every Nth section would bunch
  every label into the top of the track.
- **Sizes use decimal units (MB = 10⁶).** Using 2²⁰ would make the app's "165 MB"
  disagree with the file manager's for the same file, and the user would rightly trust
  the file manager. Saved percentages round **down**, and both `optimisedLine` and
  `freedLine` return null rather than a string when nothing was actually saved — the app
  must never dress up a no-op as an achievement.
- **Trash countdowns round up.** An entry with eleven hours left says "1 day", not
  "0 days": the user should never see a zero next to something they can still save.
- **The locked folder is a state machine, and backgrounding always re-locks.** The
  session is two minutes. A long session would defeat the feature the first time the user
  handed the phone over to show someone a photo. A cancelled prompt is not a failure and
  must not render as an error.

## Milestone 2 — the metrics

- **The `fraunhoferhhi/xpsnr` repository has no standalone C.** STACK.md and the
  `ndk-build` skill both said to build it; both were wrong and are now corrected. The
  repo holds only an FFmpeg filter (`libavfilter/vf_xpsnr.c`) and its README says the
  maintained copy lives in FFmpeg. So `shared/native/src/xpsnr_score.c` is an
  **extraction**: upstream's arithmetic verbatim, with the AVFilter plumbing, context and
  allocation replaced. Pulling FFmpeg into the app to reach one function was not a trade
  worth making.
- **XPSNR is scored on luma only.** The search needs a monotone proxy for coding quality,
  luma dominates that, and this is the metric run thousands of times a night — scoring
  chroma would cost roughly half as much again for a number the search never reads. It
  also makes the value directly comparable to FFmpeg's per-component "XPSNR y", which is
  what it is verified against.
- **XPSNR's licence grants no patent rights.** A Fraunhofer BSD-3 variant: commercial use
  is permitted, but it explicitly disclaims patent non-infringement. STACK.md approved the
  library, so this is recorded rather than re-litigated — but it is a product decision,
  not only an engineering one, and someone should make it deliberately before release.
- **`vmaf_score` runs libvmaf single-threaded.** ARCHITECTURE.md § 8 already gives metric
  work its own pool sized to cores-2, so parallelism belongs to the caller across windows.
  A second thread pool underneath would oversubscribe the exact cores the encoder is
  competing for.
- **libvmaf needs `xxd` at configure time** to embed the models, and fails *silently*
  without it — the library builds, then every `vmaf_model_load` fails at runtime. Embedded
  models are the point: shipping `vmaf_v0.6.1` as an asset would mean reading it from disk
  on a device that should be doing nothing but encoding.
- **libvmaf bundles libsvm, which is C++**, so the CMake project declares CXX even though
  all of our own code is C. Without it the link fails on `__gxx_personality_v0`.
- **A copy per plane, for now.** `YuvWindow` carries `ByteArray` because
  `shared/engine-api` cannot name `java.nio.ByteBuffer`, so the Android scorer copies each
  plane into a reusable direct buffer. Reuse makes that one copy rather than a copy plus a
  multi-megabyte allocation per probe. Milestone 3's `YuvSource` should decode straight
  into those buffers; the ABI already takes strides so nothing else has to change.

### First calibration data point

PROJECT.md's own open question — the XPSNR value that corresponds to VMAF 95 — now has a
harness (`shared/native/calibration/`) and a first answer: **VMAF 95 ≈ XPSNR y 39.8** on
the golden clip under x265.

That number is not the one to ship. It comes from software x265 rather than a phone's
hardware HEVC encoder (which PROJECT.md already records as materially less efficient), from
one clip, at 640×360 rather than the 1080p BUILD.md verifies at. It is evidence the method
works. The real threshold needs the milestone 1 encoder on device across resolutions and
content, fitted per bucket — the same key the predictor table already uses.

## Milestone 3 — probe, search and the predictor

Entirely shared Kotlin (ARCHITECTURE.md § 15 gives this milestone no platform work), so
all of it is unit tested against fakes.

- **The search runs on bitrate throughout, never CQ.** BUILD.md § 5 allows
  `BITRATE_MODE_CQ` where the encoder advertises it, but that is a choice for the final
  encode: a predictor table holding a mixture of CQ levels and bitrates would not be
  comparable, and CQ is not universally supported anyway.
- **Probe windows are scored as a mean, not a minimum.** A single hard window would
  otherwise set the bitrate for the whole file. The verifier is the right place to catch a
  file that holds up badly in one part — it looks at three separate windows and can reject.
- **Convergence stops at 12% of the bracket, not 8%.** A confident prediction hands the
  search a bracket about 36% wide; converging tighter spends a third probe to win roughly
  4% of bitrate. That is a bad trade when the metric is the bottleneck, and it broke
  BUILD.md's "1–2 probes with prediction" — caught by a test asserting the probe count.
- **An unconfident prediction moves the starting point but never narrows the bounds.**
  Even a handful of samples beats the midpoint of a wide range, but narrowing on thin
  evidence would trap every later file in the same family behind one early wrong guess.
- **`Predictor.learn` is a running mean, and the arithmetic lives in Kotlin.** The SQL is
  now a plain upsert taking both values. Doing the averaging in SQL would put it somewhere
  it cannot be unit tested, and the original last-write-wins would have let one unusually
  busy clip replace what twenty files agreed on.
- **`Predictor.setting` is INTEGER, not the TEXT in ARCHITECTURE.md § 4.** It holds a
  bitrate that gets averaged; that is arithmetic, not a label.
- **Bucket edges split families, and that is accepted.** Two clips from the same camera
  minutes apart can straddle an edge — 11.8 and 12.2 Mbps are different families. Every
  bucketing has edges, and nothing in the container reports the camera *mode* that would
  avoid them. The effect splits a prediction rather than corrupting it: each half still
  converges, it just takes longer to become confident. There is a test that documents this
  rather than pretending otherwise.
- **Missing camera model or codec becomes an explicit "unknown" family.** Lumping
  metadata-less files in with a real camera's would poison a prediction that is otherwise
  reliable.
- **The scoring width is forced even.** Chroma planes are half-width in 4:2:0, and an odd
  width leaves the last column without a sample.

## Milestone 4 — verify, safe replace, undo

- **`ReplaceSequence` is shared, not written twice.** ARCHITECTURE.md § 15 assigns
  `SafeReplacerAndroid` and `SafeReplacerIos` to the platforms, but the *ordering* in § 7
  is the part that must never drift, so it lives in `shared/core/pipeline/replace` and both
  platforms delegate to it. That is what turns § 14's "Replacer plan/rollback with fake
  storage" into a JVM unit test.
- **Two new pipeline-internal ports, `ReplaceOps` and `UndoJournal`,** for the § 7 steps
  ARCHITECTURE.md § 5 has no interface for. Metadata copying, parking and reading all reuse
  the § 5 interfaces unchanged.
- **`UndoJournal` is separate from `UndoStore`.** § 7 parks the original in the middle of
  the sequence but writes the row at the *end*. Two collaborators make that ordering
  something the code states rather than something a comment claims.
- **`ReplacePlan` gains `mediaId`.** An addition to the § 5 sketch: the contract ends by
  writing an `UndoEntry`, and an undo row that cannot name the item it came from is an
  original nobody can restore.
- **`LibraryStorage` gains `discard(TempFile)`,** `YuvSource` gains a `TempFile` overload,
  and `OutputProbe` is new. Verification has to read the encoded output back, and it is a
  temp file with deliberately no `MediaRef` — shared code must not be able to name a place
  in the user's library. A night that verifies a thousand files and deletes no rejects
  fills the disk by morning, hence `discard`.
- **The size gate is not hoisted above the VMAF pass.** It is cheaper, but skip reasons are
  shown to the user (BUILD.md § 9), and a file that is both larger and visibly worse should
  be reported as the quality failure it is. Nothing is wasted: `NotSmaller` is terminal, so
  the ladder never re-encodes because of it.
- **The verifier takes the worst window, not the mean.** `ProbeAndSearch` uses the mean
  because one hard window should not set the bitrate for a whole file; the verifier is the
  place that must catch exactly that file, so it takes the minimum.
- **A step up is 15%.** `SettingSearch` stops bisecting within 12% of the bracket top, so
  anything smaller lands inside the noise the search already declared indistinguishable.
- **`SYSTEM_TRASH` maps to the app's own bin on Android.** `MediaStore.createTrashRequest`
  needs a user confirmation dialog per call, which a night pass cannot show.
- **Parking into the bin uses `OffloadMove` too.** The bin is on internal storage and the
  granted tree is not, so it is a cross-volume move like the SD card, and gets the same
  copy → verify → remove order.
- **Restore stages the original back before removing what holds its identity.** The
  optimised file is the disposable one — it can always be made again — so it is deleted
  only once the original is confirmed back in the tree.
- **The sweep marks a row `EXPIRED` only after the bytes are gone,** so a failed delete is
  retried next sweep rather than recorded as done. It marks rather than deletes, so Restore
  can say *"the original was removed on <date>"* instead of having nothing to say.
- **The build guard was strengthened, because writing the implementation broke it.** The
  write rule matched the literal receiver `contentResolver`; `SafeReplacerAndroid` holds
  `private val resolver: ContentResolver` and would have walked past. It now matches any
  receiver. A fourth rule bans opening a user's file with a write mode, and matches raw
  source because the mode is a string literal the scanner otherwise blanks.

## Applied from PRD, USER_JOURNEY, DESIGN_SYSTEM, SCHEMA, MONETIZATION, LAUNCH

- **Ids are TEXT UUIDv7** (SCHEMA.md). ARCHITECTURE.md § 4 named the columns but not their
  types, so this is new information rather than a conflict. `core.model.Uuid7` takes its
  clock and randomness as parameters so the bit layout is asserted rather than assumed, and
  must be confined to one thread — ARCHITECTURE.md § 8 puts every database write on IO.
- **`favourite` and `locked` move into `MediaFlags`,** and `locked` becomes `hidden`.
  SCHEMA.md's `flags` bitmask blesses both fields — they were previously noted here as
  deviations — and names them. `MediaItem.favourite`/`.hidden` stay as derived properties
  so call sites read the same.
- **Two partial indexes were added beyond SCHEMA.md.** It indexes `flags` whole, which
  serves equality on the mask but not the bitwise predicates every gallery query actually
  uses; `media_item_visible` and `media_item_favourite` are what carry the grid.
- **`sha256` is a BLOB in the database and a hex `String` in the model.** 32 bytes against
  64 characters over 100k rows, but a `ByteArray` field would silently break data-class
  equality — the hazard `FaceEmbedding` already had to override around.
- **Standing albums get named string ids** (`standing:favourites`) rather than reserved
  negative numbers, now that ids are TEXT.
- **`Predictor.confident` now requires low spread as well as 20 samples.** SCHEMA.md's
  `setting_var` made this possible; the mean alone cannot distinguish a predictable family
  from one whose files merely average out, and a wrong narrow bracket costs the whole probe
  budget.
- **DESIGN_SYSTEM.md's light accent pair fails its own contrast rule.** White on `#16A37B`
  is 3.2:1; the same document requires 4.5:1 for text, and a button label is text. The
  accent is kept exactly as specified — it is the brand colour — and only `accent-on`
  changes, to the dark ink already used on dark's mint, giving 5.3:1.
- **The hero transition is now a spring with radius 4 → 0**, per DESIGN_SYSTEM.md,
  superseding the buyer-gallery prototype's 420/340 ms Béziers. Those durations survive
  only as the reduce-motion fallback and as the Macrobenchmark window.
- **Free-tier accounting is derived, not counted.** `bytesFreedSince` sums `run_session`
  rows rather than keeping a counter, so the cap cannot drift from what actually happened.
- **A first file larger than the whole monthly allowance is still optimised.** Otherwise a
  user whose first video would save 4 GB never gets to use the free tier at all — a worse
  first run than going over the cap once.
- **Retention is clamped to the tier, never rejected,** so a lapsed Pro user gets 7 days
  rather than an error. Existing undo rows keep the expiry they were created with:
  shortening it retroactively would delete originals the user was promised.

## Milestone 5 — scheduling

- **A stop is never masked by a pause.** ARCHITECTURE.md § 9's order decides which reason
  the user is told among conditions of equal severity; severity decides behaviour. The
  chain therefore collects pauses and runs to the end, returning a stop the moment one is
  found. Found by a failing test: the first version reported "paused because you're using
  the phone" for a device that was in use *and unplugged*.
- **`PauseReason.FREE_TIER_CAP` was added to the § 5 enum.** MONETIZATION.md needs "your
  month's 3 GB are spent, Pro removes the limit" to be distinguishable from "tonight's 60
  minutes are up, it resumes tomorrow". Collapsing them would either nag a user who is not
  capped or fail to offer Pro to one who is.
- **`Guards` gained `thermalPauses`.** SCHEMA.md gives `run_session` the column and
  USER_JOURNEY.md § 14 shows it as "Paused for heat 3× last night"; reading it from
  whatever did the pausing is the only way it cannot drift from the behaviour.
- **`AndroidGuards` merges what ARCHITECTURE.md § 3 lists as four classes**
  (`ThermalGuardAndroid`, `ForegroundGuard`, `ChargingGuard`, `AlarmGuardAndroid`). They
  read from one snapshot on purpose: polling the battery between the charging check and the
  battery-full check is how you get "stopped because unplugged" on a phone that is plugged
  in. The *decisions* they used to own are in `GuardChain`, which is platform-free and
  tested.
- **Storage is a pause that escalates to a stop.** ARCHITECTURE.md § 13 says pause, and it
  is right — a completed replace, an offload or the undo sweep genuinely frees space
  mid-run. But a phone that is simply full never clears it, so after six consecutive checks
  the night is called off rather than polling until morning.
- **A stood-down pass hands its window back after 30 minutes.** Holding a WorkManager
  window while paused stops the OS scheduling anything else and burns the battery this app
  exists to protect.
- **The night pass runs as a foreground service.** Not a choice: WorkManager stops an
  ordinary worker after ten minutes and BUILD.md § 6 budgets sixty. USER_JOURNEY.md § 3
  wants no UI at night, so the required notification is `IMPORTANCE_MIN` — silent, no
  badge, no heads-up — and exists to answer "what is using my phone at 3am".
  `FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING` on API 35+, `DATA_SYNC` below, because Android
  15 applies a six-hour daily budget to `dataSync` that would cut long nights short.
- **"Battery full" is 98%.** Many phones report 99 for a long time on a topped-up battery,
  and a pass waiting for 100 would never run.
- **`WorkManager` uses `ExistingPeriodicWorkPolicy.KEEP`.** Replacing the request resets the
  period, so a settings change at 23:59 would push tonight's window past the morning.
- **Free space is measured on the scratch volume, not the granted folder's.** The temp file
  is what needs the room — the encode writes there before anything is committed — so on a
  phone with an SD card, measuring the library's volume answers the wrong question.
- **`TrimRepository` implements several ports in one class.** `UndoJournal`,
  `OriginalLocator`, `NightFacts`, and the night queue all read and write the same tables
  inside the same transactions; separate classes would either share a connection through a
  fourth object or disagree about what the queue currently is.
- **Every SQLDelight query is read through an explicit column mapper**, not the generated
  row type, so a schema change that drops or reorders a column fails to compile in the
  repository instead of silently mapping the wrong value.
- **`NightRun.Queue.next()` claims its row.** The candidate is moved to `PROCESSING` in the
  same transaction it is selected in, so a second window opening while the first is still
  running cannot pick up the same file.
- **The monthly cap resets on the user's calendar month**, resolved through their own time
  zone: a UTC boundary would reset someone in Auckland thirteen hours early.
- **`shared/core/data` depends on `shared/core/pipeline`, not the reverse.** The repository
  implements the pipeline's ports so the orchestration never has to know a database exists.
- **`shared/core/pipeline` now exposes its dependencies with `api`.** `GuardChain`,
  `NightFacts` and `NightRun` name `Settings`, `Tier`, `MediaItem` and the engine
  interfaces in their public signatures; with `implementation` no consumer could compile
  against them — the same defect already fixed once in `shared/feature/*`.

## Milestone 6 — triage and the skip list

- **`MediaItem.optimisedAt` is a supplement to SCHEMA.md.** The primary defence against
  optimising our own output twice is that the pipeline writes the new size and mtime back
  after a replace, so the diff sees no change. But a provider that rounds a timestamp would
  defeat that silently, and the failure is generational quality loss on a photograph the
  user cannot get back. The column makes the rule a property of the row.
- **`ContainerReader` is a new § 5 interface, separate from `LibraryStorage.scan`.** A scan
  is one cursor query over thousands of rows; a header read per file would turn a second
  into a minute on a hundred-thousand-item library. The pass scans cheaply, diffs, and reads
  headers only for what changed.
- **`LibraryDiff.merge` takes the container facts from the scan and identity from the row.**
  The scan is the truth about the file; the database is the truth about our bookkeeping. The
  first version kept the stored codec and bitrate and a test caught it.
- **`favourite` and `hidden` survive a merge; every other flag comes from the scan.** They
  share the SCHEMA.md bitmask with the container flags but are the user's decisions, not
  properties of the bytes.
- **Removal is scoped to the grants actually scanned.** A row whose folder was not looked at
  cannot be proved absent, and reporting it removed would delete its index, labels and faces
  the first time an SD card was out.
- **A row with a live undo entry is not deleted when its file disappears.**
  `undo_entry.media_id` cascades, and that row is what points at the original in the bin.
- **Triage re-runs only for files that changed.** Re-triaging the whole library would give
  the same answer for everything that did not move, at a cost that grows with the library.
- **`MIN_WORTHWHILE_SAVING_BYTES` is 5 MB, and photos are exempt.** A probe cycle plus a
  full encode costs real battery and heat; a jpegli pass costs milliseconds.
- **The `udta` writer tag feeds the predictor key, not a triage rule.** BUILD.md asks for it
  in the triage paragraph immediately before "Predict", and that is what it is for: a file
  with no camera model but a known encoder is its own family. Building a *skip* rule on a
  tag nothing currently writes would have been dead code.
- **"Try again" is offered only for failures and cloud-only files.** Everything else is a
  property of the file or the phone and would give the same answer forever;
  `COULD_NOT_REACH_QUALITY` is permanent by BUILD.md § 5 and the search is deterministic.

## Milestone 7 — photos

- **jpegli has its own repository.** `google/jpegli`, not `lib/jpegli` inside libjxl —
  upstream split it and the directory no longer exists at libjxl's head. STACK.md's table
  was already right; its layout diagram and the `ndk-build` skill were not, and both were
  corrected along with `.gitmodules`.
- **SSIMULACRA 2 is a tool source, not library code.** `tools/ssimulacra2.cc` is compiled
  directly into `libtrim_native`; the `ssimulacra2` binary is gated behind
  `JPEGXL_ENABLE_DEVTOOLS` and the function is in no `.a`.
- **`ssim2_score` feeds images through an in-memory PPM and `jxl::SetFromBytes`.** Building
  a `jxl::ImageBundle` by hand would avoid a copy but means colour setup against internal
  APIs that move between releases — and the number has to equal the upstream binary's or
  the calibration table is measuring two different things. A PPM header plus a memcpy is a
  rounding error beside the metric.
- **4:4:4 progressive, set explicitly.** Left to itself the encoder produced 4:2:0
  baseline: SSIMULACRA 2 fell 93.6 → 67.0 *and the file grew*. The gate sits at 85–90,
  which is where full chroma is what it is asking for.
- **`jpegli_set_input_format` / `_output_format` are called although the defaults are
  already 8-bit.** `JPEGLI_TYPE_FLOAT` is the enum's zero value, so anything that zeroes
  the struct silently reads float samples out of a byte buffer.
- **oxipng is a crates.io dependency, not a submodule**, because upstream ships a library
  crate and there is no C to build. Built with `default-features = false`: rayon would
  compete with the encoder for cores, and zopfli costs minutes for a few per cent. The FFI
  entry point is wrapped in `catch_unwind` — oxipng panics on some malformed input and a
  panic across an FFI boundary is undefined behaviour.
- **oxipng runs at preset 2 and strips nothing.** Higher presets spend their time on
  Zopfli-class searching for the last couple of per cent, which is a poor trade against the
  videos waiting behind them; and metadata is BUILD.md § 2.4's promise, not oxipng's to
  discard.
- **SSIMULACRA 2 targets: 90 Standard, 85 Compact.** Upstream's own scale calls 90
  "visually lossless" and 85 "excellent quality", which is what turns BUILD.md § 5's range
  and § 9's two settings into one behaviour.
- **A transparent image is skipped rather than flattened.** The gate cannot catch this
  failure: it would compare a flattened output against a flattened reference and report a
  perfect match. Checked in the step, on the decoded alpha channel, rather than by adding a
  column to the model.
- **A PNG denser than 1 byte per pixel is treated as a photograph.** Screenshots land at
  0.2–0.6 B/px and photographs at 2–3; erring high merely repacks losslessly, erring low
  runs a lossy encoder over text.
- **HEIC is the one platform-specific path.** A HEIC still is an HEVC frame, so `HeifWriter`
  keeps it on the hardware encoder; assembling a `MediaCodec` by hand would have put codec
  creation outside `MediaCodecFactory` and failed the build guard, correctly.
- **The build guard now strips comments with a character scan.** One regex over the whole
  file threw `StackOverflowError` on a 2,000-line generated Kotlin file in a submodule;
  Java's regex engine recurses while backtracking, so the failure scaled with file size.

## Milestone 9 — the index

- **Everything that decides anything is shared.** ARCHITECTURE.md § 6 says the perceptual
  hash is a shared Kotlin implementation; the same argument covers face clustering,
  duplicate grouping and search ranking. Two devices deciding differently would take a
  user's library apart the moment it moved between them.
- **The hash uses the 64 coefficients after DC, in zig-zag order.** Thresholding DC against
  the median of its neighbours sets that bit for every image ever hashed, so the obvious
  8×8-block construction has sixty-three working bits and claims sixty-four.
- **The hash ignores aspect ratio**, so `DuplicateFinder` compares shape separately at a 2%
  tolerance. Without that, a panorama and a portrait crop of one scene are offered as the
  same picture — the failure that destroys trust in the whole screen.
- **Near-duplicate distance is 10 of 64.** Below about six it misses burst frames where the
  subject moved; above about twelve it joins different photographs taken in the same place.
- **Face clustering is tuned to under-merge, at cosine 0.72.** Splitting one person in two
  is a nuisance the user fixes with a tap; merging two people puts one person's photographs
  under another's name and cannot be undone without opening every picture. The splits come
  back as merge suggestions.
- **A poor-quality face cannot seed a cluster but can join one.** An embedding halfway
  between everybody absorbs strangers if it defines a cluster.
- **Clusters below three faces are not shown as people.** A single sighting is more often a
  passer-by than someone the user would name, and they are kept rather than discarded so a
  later sighting can turn two singletons into a person.
- **The privacy switch is honoured by not computing.** When face clustering is off, no
  embedding is made — not made and discarded, not made and hidden.
- **A year is searched as a year and as a word.** "2019" could be a date or a race bib, and
  a search box that guessed wrong would return nothing with no way to say what was meant.
  Years outside 1990–2100 are only words.
- **People and places are supplied, never inferred.** "Mum" is a person only because the
  user named a cluster that; inferring from capitalisation would put every proper noun in
  the people facet.
- **Recency may contribute at most a fifth of a search score.** Enough to separate equal
  matches, never enough to lift a weak recent match above a strong old one.
- **"Not opened" is never inferred for chat media.** It is the strongest reason to suggest
  deleting something, and inferring it from a missing thumbnail or an access time the
  filesystem may not keep would offer up photographs the user looks at often.
- **Chat folders are matched on path.** There is nothing in a JPEG that says it arrived over
  WhatsApp.
- **`IndexStep`'s failure list is a parameter, not a field.** It was a field on an object DI
  makes a singleton, so one bad file's failures followed every file indexed after it for the
  rest of the night. Caught by its own test.
- **ML Kit's bundled models, not the Play-services ones.** The downloadable variants fetch
  over the network and this app has no INTERNET permission; a model that cannot download is
  a feature that silently never works.
- **Face embeddings are stored as float32, not the float16 SCHEMA.md's size estimate
  assumes.** Halving the precision of the number people-clustering depends on is a decision
  to take with measurements, not with a schema comment.
- **`MlKitIndexer` returns landmark geometry where the real embedding will go.**
  ARCHITECTURE.md § 6 puts the embedding on a LiteRT model that has not been chosen (it is
  still an open question below). Returning normalised landmark geometry means the boxes, the
  quality signal and every piece of plumbing are exercised and correct before the model
  arrives — but it is a placeholder, and clustering quality will change when it is replaced.

## Milestone 10 — Space, history, Compress now, play-to-compress, settings

- **Compress now is one file, one tap, and nothing that can be applied to a queue.**
  BUILD.md rule 1 forbids encoding on battery except on an explicit tap; a bulk "compress
  everything now" would be the night pass on battery with a different label, so `decide`
  takes a single item and returns nothing that generalises.
- **A user's tap overrides "not worth it" but not "this would lose data".**
  `ALREADY_EFFICIENT`, `TOO_SMALL` and `WOULD_NOT_SHRINK` are triage's judgement about
  whether a night's battery is well spent; HDR, Motion Photo, Ultra HDR, Live Photo and RAW
  are facts about the file. The first set warns, the second refuses — to Pro users too.
- **A file this app already optimised cannot be optimised again, at any tier.** The
  generational-loss guard behind `MediaItem.optimisedAt`. Without it, Compress now is a
  way around that protection five times a day.
- **Item facts are checked before the paywall.** A user whose file cannot be optimised is
  told that, not shown a Pro offer for a button that would still do nothing.
- **Compress now replaces nothing by itself.** The night pass replaces because the user
  asked it to once, in Settings. This ends on Share / Replace original / Keep both
  (USER_JOURNEY.md § 6), and `Finish.writesToLibrary` marks the two that go through
  `Replacer` — sharing hands out the app-private temp and never touches the library.
- **Neither number on the Compress now sheet is invented.** The expected saving comes from
  triage or the predictor and the expected time from a measured encode speed; both are
  nullable and null until something has measured them. There is no default real-time
  multiple because there is no honest one — the same phone encodes 4K HEVC and 1080p H.264
  at speeds that differ by more than the estimate is worth.
- **The daily Compress now count and the monthly GB cap are separate limits.**
  MONETIZATION.md's table says "Background optimisation — 3 GB freed per month" and
  "Compress now — 5 per day". `bytesFreedSince` sums `run_session`, and a Compress now job
  has none, so the two never touch. Counted from `started_at`, not completions: the battery
  is spent whether or not the user lets it finish.
- **Play-to-compress delivers every frame or nothing.** The decision logic is shared and
  tested, not platform code, because *when to give up on a tap* is the difference between a
  smaller video and a video with a hole in it — and iOS must behave identically. Nine break
  conditions are named; all but a decoder error requeue the file for the night.
- **The frame-gap tolerance errs towards giving up**, at four frame intervals with a 100 ms
  floor and a wider 500 ms allowance when the container states no frame rate. A false
  abandon costs one wasted encode; a missed gap is a silently shortened video. Assuming
  30 fps when the rate is unknown would wave seven dropped frames through on a 240 fps
  slow-motion clip.
- **A pause holds the encoder for two minutes, not indefinitely.** Hardware encoder
  sessions are device-wide and scarce, and a user who pauses to answer the door and then
  puts the phone down would otherwise deny one to the camera.
- **Settings are sanitised in both directions.** On the way in so an unhonourable value is
  never persisted; on the way out so a lapsed Pro user is clamped on the next read rather
  than when something remembers to re-save. `AndroidGuards`' `runCatching { LocalTime.parse
  }.getOrNull()` is now unreachable rather than load-bearing.
- **BUILD.md and MONETIZATION.md disagree about undo retention, and both are kept.**
  BUILD.md § 6 gives the "Free space" folder mode a default of 30 days; MONETIZATION.md
  gives the free tier 7. The setting's default is 30 and a free user's copy of it is 7.
  **The consequence is a UI requirement:** every screen that shows a retention period must
  show the sanitised value, not the stored one. Promising a free user 30 days of originals
  and deleting them at 7 would be the worst bug this app could ship.
- **Only three settings changes are explained to the user** — Compact, turning face
  clustering off, and shortening retention. A screen that explains every toggle teaches the
  user to dismiss explanations, and then the one about quality goes unread too.
- **Changing the quality target or AV1 invalidates triage; changing the photo format does
  not.** The first two change *whether* there is a saving; the third changes only what the
  output is, and re-triaging a hundred thousand photos to discover that is a night spent on
  nothing.
- **History shows only succeeded jobs, and names four restore states rather than one.**
  A failure belongs on the Skipped screen with its reason. `FromExternal` is separate from
  `FromBin` because offering a one-tap restore for a file on a card in a drawer is a promise
  the app cannot keep, and `Expired` carries the date so the sheet can say when the original
  went.
- **The energy estimate declines to show a battery percentage below 1%.** "0%" and "0.4%"
  are the same claim made with different confidence, and the Space screen's whole value is
  that its numbers can be believed.
- **`currentTier()` is a function, not a value, in the DI graph.** The tier changes while
  the app is running — at the moment a purchase completes — and a captured copy would leave
  a paying user on free-tier settings until the next launch.

## Milestone 11 — the editor

- **The editor's first job is to avoid the encoder.** A rotate or flip is an orientation
  tag (EXIF, HEIF and MP4 all carry one); a trim starting on a keyframe is a container cut;
  an edit undone back to nothing is nothing. Re-encoding a clip this app already optimised
  is a second generation of loss on the first, and two are visible — so every path that
  avoids an encode is taken.
- **Only a trim's start has to land on a keyframe.** Frames at the head of a cut that begins
  mid-group reference an I-frame that is no longer in the file; the end may fall anywhere,
  because truncating the last group loses only frames the user asked to lose.
- **Keyframe snapping only ever moves the start earlier.** Snapping forward drops footage
  the user chose to keep; snapping back keeps a fraction of a second they chose to lose,
  which nobody notices. Under 120 ms it is silent, under 2 s it is offered, beyond that a
  re-encode is simply the better answer.
- **A container that lists no keyframes forces a re-encode.** Assuming frame zero is a
  keyframe would be right for most files and would produce an undecodable one for the rest.
- **`Orientation` is one closed set of eight, not a rotation plus a flip flag.** Mirroring
  reverses the sense of a rotation, so with two independent fields "rotate right, then
  mirror, then rotate right" has whichever answer the call site computed. As a group it has
  an answer, and associativity, inverses and the conjugation are asserted rather than
  believed.
- **The straighten fit is exact, not a fudge factor.** A centred W×H frame rotated by θ fits
  inside a w×h picture exactly when its bounding box does, giving two bounds on W of which
  the smaller wins. Tested both ways: the crop always fits, and a crop 0.1% larger does not
  — otherwise the editor zooms in further than it must.
- **An aspect-locked drag that overflows shrinks about its centre; a free one clamps
  edge-by-edge.** Clamping a locked crop per edge would crop one side to the picture's
  boundary and silently drop the lock the user chose.
- **All eight sliders share the range −1..+1 with 0 as neutral.** It makes "is this edit
  doing anything?" one check, makes a filter a vector that a strength can scale, and makes
  reset the same code everywhere. Each renderer maps the range onto its own units.
- **A filter is a set of slider positions, not a LUT.** Filters and sliders then combine
  instead of fighting; strength is exact arithmetic rather than a second interpolation path;
  nothing ships a cube file. The cost — a filter can do nothing the sliders cannot, so no
  split toning or film curves — is accepted deliberately, and a v2 wanting real looks should
  add LUTs on purpose rather than discover it needs them.
- **Slider positions are quantised to a millionth.** Binary floating point has no exact 0.1,
  so a filter at 0.3 plus a slider at −0.1 lands on 0.19999999999999998: two equal edits
  compare unequal, and an adjustment the user cancelled by hand can fail `isNeutral` and
  write a file for an edit that does nothing. Deciding the resolution is the fix; inheriting
  whichever one the arithmetic produces is not.
- **The adjustment pipeline order is fixed in shared code.** Tone before colour, because
  saturating first and then lifting exposure amplifies the saturation into clipping; black
  point last within tone, because it is defined against the histogram the earlier sliders
  produced. Fixed once so the Compose preview and the full-size render cannot disagree — a
  preview that does not match the saved file is worse than no preview.
- **Two of the optimiser's four verification gates do not apply to an edit.** Not "must be
  smaller", because a crop re-encoded may be larger and the user asked for it; not VMAF ≥ 95,
  because there is no reference and the output is meant to differ. Openable, expected
  duration and the size/mtime snapshot all still apply — dropping those alongside the
  quality gate is one sentence from replacing a file with a truncated one.
- **A re-encoded save-over sets `optimisedAt`; a lossless one does not.** The column means
  *when this app last replaced this file*, which is literally what happened, and it stops the
  night pass adding a second generation. No `Job` row is written and no saving is claimed,
  because none was measured. A rotate or keyframe cut moves the original bytes, so marking it
  would cost the user the saving the night pass would have found.
- **A rotation invalidates the hash and nothing else.** The same faces, words and labels,
  because every detector works in the upright frame — but the perceptual hash is built on a
  grid of pixels that a turn permutes, so a rotated photograph would otherwise stop matching
  its own duplicates. Every other edit invalidates all of it: a crop can remove a face
  outright, a trim the frames a label came from.
- **A trim on a clip of unknown duration is an edit, not a non-edit.** Without the length
  there is no way to know whether the range covers the whole clip, and reading "I don't know"
  as identity discards the user's trim silently, on exactly the files whose metadata is
  already unreliable. Found by a test.
- **`Replacer.saveCopy` closes the milestone-10 open question.** "Save (new copy)" and "Keep
  both" write a new file into a granted folder, and ARCHITECTURE.md § 14 allows one writer.
  It is on `Replacer`, implemented inside `SafeReplacerAndroid.kt`, rather than given its own
  path — an allow-list that grows to fit the code has stopped being a guard. It takes none of
  the replace machinery: nothing parked, nothing snapshotted, no undo row, because nothing is
  at risk. Its one obligation is to leave nothing behind on failure.
- **Editing is free at both tiers**, per MONETIZATION.md's first table row, and an edit is
  never counted against the Compress now daily limit or the monthly GB cap. An edit is not an
  optimisation.

## Milestone 12 — the AV1 path

- **`CodecCaps` is per encoder, not per device.** It carried one set of limits read from the
  HEVC encoder and applied them to both, so a 4K60 clip passed a capability check against
  the wrong encoder — most phones with an AV1 encoder top out below their HEVC ceiling,
  commonly 4K30 against 4K60. The failure would have surfaced at encode time, after the
  whole probe and search.
- **`getSupportedPerformancePoints()` is represented, and silence is not permission.**
  BUILD.md § 10 requires respecting the advertised throughput. An encoder that lists no
  points is treated as having said nothing and falls back to its width/height/rate bounds;
  reading an empty list as "no limit" is how a night spends four hours on one clip. Points
  are compared by area, so a portrait clip matches a landscape point — it is the same
  number of macroblocks turned on its side — and frame rates get half a frame of slack,
  because containers report 29.97 as 30 often enough that exact comparison would refuse the
  commonest rates there are.
- **`Predictor.Key` includes the output codec** (a supplement to BUILD.md § 5's key list and
  to SCHEMA.md's `predictor` table, both recorded here). AV1 reaches the same quality at
  roughly two thirds of HEVC's bitrate, so one family would average the two and predict a
  number wrong for both — and worse than no prediction, because a confident entry narrows
  the bracket around it and the search would spend its whole probe budget escaping. A user
  who turns AV1 on keeps their HEVC history; it simply does not answer AV1's questions.
- **An AV1 source is only ever re-encoded to AV1, and skipped otherwise.** Triage counts AV1
  above ~8 Mbps as a candidate, and taking such a file to HEVC would usually make it larger
  at the same quality: a night's battery spent to lose the user space. Checked before every
  other rule, because "HEVC is available" is not a consolation.
- **AV1 is demoted when this device measures it too slow**, below 1× real time, and only
  after five samples. BUILD.md § 6 caps the night in minutes, so a slow encoder means a
  bigger saving per file and a smaller one per night. One slow file is a file, not a fact
  about the encoder — demoting AV1 for the life of a phone on one thermally-throttled clip
  would be a bug nobody could see. An encoder that has never been measured is given the
  work, because that is how the measurement gets made.
- **The AV1 entitlement is re-checked in `CodecChoice`, not trusted from `SettingsPolicy`.**
  Sanitising already clears `allowAv1` for a free tier. A codec choice that silently
  depended on that having happened first is one refactor from encoding a free user's library
  into a format they did not buy.
- **The Settings explanation names AV1's cost.** *"Older phones, TVs and cars may not be
  able to play them."* The file plays on the phone that made it, so the cost only appears
  when it is shared — which is exactly the kind of cost a toggle must not hide.
- **The fallback search bracket lives in `CodecLadder`, per codec.** It was the caller's to
  invent, which was survivable with one output codec. A bracket built for HEVC opens an AV1
  search a third too high and converges downwards for every probe it has.
- **The bracket comes off the source bitrate, not the resolution.** The source bitrate is
  the one number that already accounts for how busy the footage is: a static talking head
  and a handheld shot of a forest are the same 4K30 and want very different answers.
- **The queue's estimate and the search's opening bid are one number.** `expectedFactor` and
  `fallbackBounds().startBps` come from the same constant, so "About 19 GB more possible"
  and the bitrate the search actually opens at cannot drift apart.
- **The XPSNR thresholds are the measured ones, not invented constants.** From milestone 2's
  sweep (`shared/native/calibration/`): VMAF 95 interpolates to XPSNR y 39.8, and VMAF
  90.035 was measured directly at 36.0. That README is explicit they are provisional —
  software x265, one clip, 640×360 — but a measured provisional number beats an invented
  one, and the verifier is what makes a wrong threshold cost a re-encode rather than
  quality.

## Milestone 13 — the field test

- **`files_indexed` and `duplicates_found` were missing from `run_session`.** BUILD.md § 14
  lists both on the per-night metrics (a supplement to SCHEMA.md's table, recorded here).
  Indexing runs in the same pass as the optimisation and keeps running after the free cap is
  reached, so a night with `files_done = 0` may have done exactly what the user was
  promised — and with only `filesDone` to go on it looked like a night that did nothing.
- **GB per hour is measured against time worked, not wall clock.** A night plugged in for
  eight hours that worked for forty minutes freed its gigabytes in forty minutes; the rest
  was the guards doing their job. Wall clock would make a well-behaved build look slow and
  reward one that ignored the thermal gate.
- **The saving is a median, and declined files contribute nothing rather than zero.** One
  drone clip that compresses to a tenth carries a mean on its own; and counting skips as
  zero-saving would report a device as saving less the more carefully it declined to touch
  things.
- **Video and photo savings are reported apart.** LAUNCH.md's gate is about video, and a
  library of screenshots must not answer for it.
- **The restore rate counts only entries that reached a decision.** One still in the bin has
  not been declined, it has not been looked at, and counting it as a success would flatter
  the number for the whole retention period.
- **The alpha gate is judged on the worst device, not the average.** The point of testing on
  three is to find the one that behaves differently, and PRD.md names the low-end chip as
  the risk. Pooling lets two good phones carry a bad one.
- **A criterion with no data fails.** Not "n/a": the field test exists to produce the
  evidence, and a build that shipped because nobody measured the restore rate is the failure
  the gate is for. Reporting distinguishes the two cases — a criterion failing for missing
  evidence says so and names how many devices reported, rather than blaming a partial number
  and sending someone to fix a build that was never wrong.
- **"Zero thermal complaints" is measured as stand-downs per night, at most two.** A
  complaint is a person and cannot be counted from a log. One pause a night is the thermal
  gate working; three is the pass fighting the phone.
- **The diagnostics file is built from an explicit list of permitted fields**, not by
  serialising existing rows. It is the only file this app produces that is meant to leave
  the device, and building it field by field is what makes the redaction survive somebody
  adding a column later. Excluded: filenames, paths and SAF URIs; locations; every timestamp
  but the export's own date; content hashes, exact and perceptual; everything the index
  produced; and row ids, since a UUIDv7 embeds the millisecond it was minted and a list of
  them is a timeline. An error is a flag, never its message, because messages quote paths.
- **The redaction is tested by sentinel**, not by inspection: the report is built from an
  item whose every string is a distinctive token and the test asserts none appear in the
  output. That is what catches the next field added carelessly.
- **The export lives in its own cache subdirectory.** The cache root also holds the
  encoder's temp files, which are copies of the user's originals mid-optimisation; a
  `FileProvider` pointed at the root would make a granted URI for one of those reachable.
- **`ThresholdFit` refuses rather than extrapolates.** A target past the ends of a sweep, or
  a sweep that is not monotone, returns a reason instead of a number. Real sweeps rise —
  both metrics measure the same thing badly and well — so one that does not is a broken
  measurement, and fitting through it bakes noise into a threshold governing the whole
  library.
- **The shipped threshold rounds up.** A tenth of a decibel too high costs a sliver of space
  per file; a tenth too low costs quality on an app whose claim is that the difference is
  invisible. The rounding goes where being wrong is cheap.
- **The fitting reproduces milestone 2's published number.** Given that table it returns
  39.8 for VMAF 95, which is what `shared/native/calibration/README.md` reports — so the
  code that will produce the next threshold is checked against the one that exists.

## Milestone 14 — Memories and the map (v1.1)

- **There are no place names anywhere in this feature.** PRD.md R8 forbids the `INTERNET`
  permission for the life of the product, so there is no geocoder and no gazetteer. A trip
  memory says "5 days away · 1200 km away", never "Barcelona", because that would be a guess
  dressed as a fact. Where a name is wanted the only honest source is the user typing one.
- **The basemap is an MBTiles pack the user supplies** (decided with the user rather than
  assumed, because the alternative was a library not in STACK.md). An `.mbtiles` file is an
  ordinary SQLite database, so it needs no map library and nothing new in the approved stack.
  The cost — the map is empty until a pack is added — is stated in the UI rather than hidden,
  which is what `MapTiles.available` is for.
- **A pack that cannot be used is refused with a reason.** A vector pack is a valid MBTiles
  file this app cannot draw; saying so beats a map that stays silently empty after the user
  went and found a file.
- **MBTiles rows are TMS, slippy tiles are XYZ, and the flip lives in shared code with a
  test.** Getting it wrong does not blank the map — it renders upside down by hemisphere,
  which is the sort of bug that survives a demo.
- **Ground distance and tile scale use different Earth radii on purpose.** Haversine wants
  the mean radius; Web Mercator is *defined* on the WGS84 equatorial one. Using the mean for
  tile scale puts the scale bar a tenth of a percent out and in disagreement with the tiles
  it is drawn over. Caught by a test against the standard 156543.03 m/px figure.
- **The centroid is averaged as unit vectors, not as numbers.** Averaging longitudes puts
  the centre of two photographs either side of the date line in the middle of Africa.
- **Latitudes are clamped to the Mercator limit.** Photographs are taken past 85° — research
  stations, flights over the pole — and a projection running to infinity there is a crash
  reachable from a user's own library.
- **Clustering is greedy and seeded by recency, and cluster centres never move.** Stability
  matters more than tidiness: the same library at the same zoom must give the same pins, or
  panning away and back rearranges the map under the user's finger. Fixed centres also avoid
  the single-linkage failure where a chain of photographs a hundred metres apart drags one
  cluster across a city.
- **Pins merge by screen pixels, not by metres.** A degree of longitude is a different
  distance in Iceland and in Kenya, and distance-based merging leaves one map looking half as
  busy as the other.
- **Home needs a dominant place, not merely the largest one.** A share test alone is cleared
  by five places holding a fifth each, and the largest wins by rounding. Home must also be
  twice the runner-up. Someone who splits their life between two cities has no home by this
  rule and is offered no trips — right, because measuring "away" against one of two homes
  calls half their ordinary life a holiday.
- **A trip ends after three days without photographs, not two.** Chosen by which mistake the
  user sees: one holiday split into three memories looks broken; two trips three days apart
  merging reads as one longer trip.
- **Home is the most sensitive thing the app derives, and it never leaves.** Derived from the
  coordinates already on the device, excluded from the diagnostics export along with every
  other location, and unable to become an address because there is no geocoder.
- **A memory's exclusions are a value passed in, applied before anything is grouped or
  ranked.** This is the one feature where being wrong is worse than being absent — every
  gallery that has shipped Memories has hurt someone with it. Mutes for a person, a date, a
  place with a radius, and a dismissal that sticks. There is no path through the selection
  that can route around them.
- **The locked folder is excluded structurally, not by mute.** Hidden items are out of every
  other view and a memory is a view.
- **Face clustering off means no person memories at all**, not computed and filtered — the
  same rule `IndexStep` already follows: the way to be sure something never leaves is not to
  make it.
- **A person the user has not named gets no memory.** There is no source for a name but them,
  and "Person 3" as a memory title is worse than no memory.
- **Near-duplicates are dropped from a memory but favourites never are.** Eleven frames of
  the same plate of food is what makes a memory feel automatic; a user who marked two similar
  frames meant both. The threshold is tighter than the duplicate finder's, because a memory
  dropping a merely-similar picture costs nothing where a delete suggestion does.
- **Over the length limit a memory samples across its span rather than truncating.** A
  memory of one morning of a week-long trip is a memory of the wrong thing.

## Milestone 15 — the iOS port

- **The shared layer's portability is enforced, not assumed.** It has only ever been compiled
  for the JVM, so a platform import in `commonMain` would pass every test until someone built
  for Kotlin/Native. The audit came back clean; the guard is what keeps it that way through a
  year of changes.
- **`androidx` is not one thing.** `androidx.compose.*` is Compose Multiplatform and compiles
  for Native; `androidx.work`, `androidx.datastore` and `androidx.media3` do not. The first
  version of the rule banned the lot and flagged 196 correct lines in `shared/core/ui` — a
  guard nobody can satisfy gets switched off, and then it guards nothing.
- **Guard allow-lists compare file names without the extension.** They were written when every
  implementation was Kotlin and say `SafeReplacerIos.kt`; the component is Swift, because
  PhotoKit lives there. The boundary is about the component, not the compiler.
- **The guards had never seen any Swift.** The `VTCompressionSessionCreate` and PhotoKit
  patterns existed from milestone 4 but the harness globbed only `.kt`, so they had never run
  against anything. Fixed the moment there was Swift for them to run against.
- **The thermal mapping is shared, not per platform.** iOS gives four states, Android a
  continuous headroom. A second gate for iOS would drift from the first, and a user would be
  told "paused for heat" on one phone and not the other at the same temperature. One gate,
  one hysteresis, converted at the edge.
- **On iOS the hysteresis does nothing, and that is written down.** There is no state between
  0.5 and 0.7, so an oscillating OS signal is a pause per oscillation. `ThermalState.HELD_FAIR`
  is the alternative — fair mapped between the thresholds, so only nominal resumes — named and
  tested so switching after a field test is one line. It trades the letter of
  ARCHITECTURE.md § 6 ("run at fair") for the behaviour the two thresholds exist to produce.
- **An unrecognised thermal state reads as nominal.** A value a future OS adds must not stop
  the night pass on every phone that has it; the gate still pauses on any reading it does
  understand.
- **iOS's replace is add-then-delete in one change block.** Two blocks means an instant with
  neither file, on the user's only copy. PhotoKit's `performChanges` is atomic for the whole
  block, which is what lets the § 7 contract hold where there is no rename to be atomic on.
- **Album membership is read before the change block and re-applied inside it.** A new asset
  belongs to no album, so without this the photograph stays in the library and silently leaves
  every album the user filed it in — and nothing surfaces it.
- **iOS's rollback is weaker than Android's, and the difference is real.** `uncommit` can
  remove the replacement but PhotoKit offers no programmatic restore from Recently Deleted, so
  the unwind recovers the identity and the user recovers the original from the Photos app. It
  is the one place the two platforms genuinely differ on safety, and the undo row names the
  system bin so History can say so.
- **`NightTask` re-submits before it runs.** iOS grants one window per submission; a night
  that forgot to ask for the next one is an app that optimises once and never again, with no
  way for the user to tell why.
- **`earliestBeginDate` is ten minutes out, not tonight at a fixed hour.** iOS treats it as
  the earliest it will *consider* running and picks the moment itself — it already knows when
  the phone is put down. Asking for 2 a.m. would not make it run at 2 a.m.; it would only stop
  it running at midnight when the phone was already charging and idle.
- **VideoToolbox is asked to *require* hardware, not to prefer it.** Only
  `RequireHardwareAcceleratedVideoEncoder` fails rather than falling back to software, which
  is what BUILD.md rule 2 needs.
- **`EncoderCaps.performancePoints` is empty on iOS.** VideoToolbox has no equivalent, and
  empty means "no information" — falling back to the dimension bounds — rather than "no
  limit". Whether iOS needs a throughput bound at all is a field-test question.
- **The iOS database file goes in Application Support, not Documents.** Documents is exposed
  to the Files app, and a user browsing their own files should not find the index of their
  photo library sitting there.

## Hardening pass — what CI found, and two guards made real

### Errors a real build surfaced (task 1, ongoing)

Recorded by class, as each one is fixed:

- **AGP configuration: `abiFilters` and an `abi` split both set.** `androidApp` expressed
  "arm64-v8a only" twice, in two ways AGP refuses to accept together, and it refuses during
  *configuration* — so it failed every job in the workflow, including the guards and shared
  tests, which never touch Android. This is the class of error that no amount of local
  reasoning finds: this environment cannot resolve the Android plugin at all, so the build
  had never been configured anywhere.

- **An eager task lookup inside `androidComponents.onVariants`.** The merged-manifest guard
  wired itself onto the variant's assemble task with `tasks.named("assemble$capitalised")`.
  `onVariants` runs while AGP is still *building the variant model*, before it has
  registered the lifecycle tasks that model produces, so the lookup threw "Task with name
  'assembleDebug' not found in project ':androidApp'". Configuration-time again, and
  therefore all four jobs again — Gradle configures every project on every invocation, so
  the iOS job on a Mac died inside `androidApp`. Fixed with
  `tasks.matching { it.name == … }.configureEach`, which binds when the task is realised —
  before its own dependency graph is walked, and without realising anything that would not
  have run anyway.

  Two configuration-time faults in a row is the shape of the problem: nothing in this
  repository had ever been *configured*, so the errors found first are not the code's, they
  are the build's, and each one hides every error behind it.

- **`core/data` had never been compiled at all, and did not compile.** Every override of a
  `Unit`-returning port — `UndoJournal.forget`, `NightRun.Checkpoint.save`,
  `TriageStep.Sink.insert/update/recordVerdict`, `IndexStep.Sink.hashes/indexed` — was
  written as an expression body over a SQLDelight call, so its inferred return type was
  `QueryResult<Long>` and none of them actually overrode anything. Eight errors in one
  file. The module needs SQLDelight's generated interface to compile and the local
  harness had only ever included `MediaFlagsBits.kt` from it.

  Found by building a harness that applies the real SQLDelight Gradle plugin from Maven
  Central against the real `.sq` files — the plugin, unlike AGP and Compose, is not behind
  Google Maven. `shared/core/data` commonMain and jvmMain now compile here, against the
  generated schema, which also makes the migration tests of task 6(f) writable without CI.

- **Two files had a syntax error and nobody knew.** `TrimTheme.kt` carried a stray closing
  brace, and `MainActivity.kt` and `GalleryTile.kt` each had `` `shared/feature/*` `` inside
  a KDoc — Kotlin block comments *nest*, so the `/*` opened a comment that the KDoc's own
  `*/` closed, leaving the rest of the file inside a comment that never ended. Neither file
  had ever been through a compiler: `core/ui` and every `shared/feature` module need Compose
  Multiplatform, which cannot resolve here. Found by ktlint's parser, which needs no
  dependencies at all, and is now the cheapest syntax check available in this environment.

- **`sharedTest` and `iosCompile` asked for tasks in projects that are not modules.**
  `include(":shared:core:model")` creates a project for every intermediate path segment, so
  `:shared`, `:shared:core` and `:shared:feature` exist with no build file and no Kotlin
  plugin. Filtering on the path prefix asked for `:shared:core:jvmTest`, which does not
  exist, and the build failed before running a single test. Filter on `buildFile.exists()`.

- **Detekt was analysing nothing, and ktlint was formatting to a style nobody chose.**
  Every `:shared:*:detekt` task reported NO-SOURCE: detekt's default source set is
  `src/main/kotlin` and `src/test/kotlin`, which no Kotlin Multiplatform module has. Static
  analysis had been configured since milestone 2 and had never looked at anything but the
  Android app. Pointed at the real source sets it found 357 issues; the ones that were
  defects are fixed in the same change (below), the rest are baselined with a written
  rationale in `config/detekt/baseline.xml`. Separately there was no `.editorconfig` at all,
  so ktlint used its own default — `ktlint_official`, its most opinionated style, and 3,185
  violations of hand-formatting that reads perfectly well. The style is now pinned to
  `intellij_idea` (the Kotlin coding conventions) on purpose rather than by accident.

- **Defects the static analysis found once it could see the code.**
  - `SettingSearch.bisect` and `bisectUpward` both took a `bounds` parameter and neither
    read it — the bracket arrived a second time as `lowBps`/`highBps`. Dead weight in the
    search that milestone 3 exists for.
  - `GalleryTile` took an `index` it never used; the caller applies the staggered-arrival
    modifier itself.
  - `ProbeAndSearchTest` carried a `FakeProbeEncoder` nothing constructed.
  - `NightWorker` caught every exception from a whole night's work and returned
    `Result.retry()` without recording anything anywhere. A night can now fail and leave a
    log line; that it cannot yet leave a *row* is a new open question below.

- **`androidApp`'s engine package did not match its directory.** Twenty files declared
  `package app.trimgallery.engine.android` from `.../engine/`. Harmless to the compiler,
  noise to every tool that resolves a type by path, and now correct.

- **The `bounds` a compiler could only see across a module boundary.** `TriageStep` built a
  `GeoPoint` inside `if (latitude != null && longitude != null)`. Kotlin will not smart-cast
  a public API property declared in *another* module, so that is a compile error in the real
  build and not in a harness that compiles everything as one module. Fixed by binding to
  locals — and the harness gap is now closed: `scratchpad/modcheck` is a five-project Gradle
  build mirroring the real module graph (model → engine-api → domain → pipeline → data),
  which reproduces this class of error here instead of in CI.

- **The network-permission guard failed every module that has no manifest.** It treated an
  empty scan as a misconfiguration, on the reasoning that a check which passes because it
  looked at no files is worse than no check. Sound rule, wrong scope: only `androidApp` has a
  hand-written `AndroidManifest.xml` — every other module gets one synthesised by AGP — so
  the guard was reporting the repository's ordinary shape as a fault, and it took until CI
  ran the guards to find out. `requireManifests` now says where a manifest must exist: the
  application module's own sources (matched by plugin id, since AGP is deliberately off
  build-logic's classpath) and the merged manifest of every variant. Both halves have a
  test, and the report says SKIPPED rather than OK when it scanned nothing.

- **The gallery keyed on a `Long` id the model has never had.** `GalleryScreen` took
  `processingIds: Set<Long>` and kept tile rectangles in a `Map<Long, Rect>`, while
  `MediaItem.id` is a `String` (SCHEMA.md). Three type errors in one file, in a module that
  had never been compiled because Compose Multiplatform cannot resolve here — `androidx.
  annotation`, `androidx.collection` and `androidx.lifecycle` are on Google Maven and
  `org.jetbrains.compose` alone is on Maven Central, so there is no partial route either.
  This is the class of error that only the Android and iOS jobs can find, and it is why they
  exist.

- **ktlint linted SQLDelight's generated code.** The plugin adds its generated interface to
  the module's `commonMain` Kotlin source set, so `ktlintCommonMainSourceSetCheck` failed on
  files nobody wrote and nobody can fix — the first time CI ran code generation before
  linting. Excluded everything under a `build` directory.

- **The source-boundary guard failed every module with no sources.** The same shape as the
  manifest guard above: seven of the eight `shared/feature` modules are still empty shells,
  and `include(":shared:core:model")` creates a container project for every path segment.
  `requireSources` is now set from whether the project has a `src` directory at all, which
  is the honest form of the rule — sources on disk and none found means the wiring broke;
  no sources at all means there is nothing here to guard.

- **The APK asked for INTERNET, and the guard caught it.** `verifyNoInternetPermissionMerged`
  failed on all three variants the first time it ran — which is exactly the case that task
  exists for, and the one a scan of the app's own manifest cannot see. Libraries declare what
  they might need: Coil can fetch an image over HTTP, WorkManager can constrain a job on
  network state, and the manifest merger unions those into the shipped APK. Neither
  capability is used here. Four `tools:node="remove"` lines now delete them from the merged
  result, and `ManifestPermissionScanner` learned to read a removal as a removal — without
  that it would have failed on the very lines written to satisfy it. `replace` and `merge`
  are still violations: they keep the permission.

  Worth being plain about: without this, the app's own screen would have said "no network
  access" while its manifest asked for the internet. BUILD.md rule 8 is a claim made to
  users, and until this run nothing had ever checked the artifact that claim is about.

- **`IosDatabase` put `journalMode` on the wrong object.** It belongs to
  `DatabaseConfiguration`, not to `DatabaseConfiguration.Extended`. Written from
  documentation and never compiled, because Kotlin/Native needs a Mac. The same file also
  *claimed* the database lives in Application Support and never set `basePath`, so sqliter
  would have put the index of the user's photo library under Documents — visible in the Files
  app to anyone who enables file sharing. Both fixed together.

- **`checkDebugAarMetadata` is a version wall, and the honest fix is to pin the libraries
  down, not to jump the whole toolchain.** It refused 29 artifacts, then 25. Two separate
  requirements, both declared inside the published `.aar`:

  | | declares | this build has |
  |---|---|---|
  | `androidx.compose.*:1.12.0` | `minAgpVersion=9.1.0`, `minCompileSdk=37` | AGP 8.13.0, compileSdk 36 |
  | `io.coil-kt.coil3:*:3.6.0` | `minCompileSdk=37` | compileSdk 36 |

  Three fixes, each checked against the artifact rather than guessed. The androidx
  `compose-bom` came out of `androidApp` — it was the only module mixing the BOM with the
  Compose Multiplatform plugin, which put two Compose versions in one build. Compose
  Multiplatform went to 1.11.1, which maps to androidx.compose **1.11.2**, artifact by
  artifact, read out of the Gradle module metadata on Maven Central. And coil went to 3.5.0,
  whose `.aar` declares `minCompileSdk=36` where 3.6.0 declares 37 — downloaded and unzipped
  to check, because a changelog would not have said.

  The first two were not enough on their own. `org.jetbrains.androidx.lifecycle:2.10.0` maps
  to `androidx.lifecycle:2.10.0`, which drags the whole androidx.compose line back up to
  1.12.0 from behind Compose Multiplatform's back — stepping Compose down while leaving its
  lifecycle companion up changes nothing. Compose Multiplatform 1.11.1 depends on
  `org.jetbrains.androidx.lifecycle:2.9.6` itself, whose Android variant maps to
  `androidx.lifecycle:2.9.4` — the version already in the catalogue. Set to 2.9.6, so the
  two lines agree rather than one silently overriding the other.

- **The first explanation for that wall was wrong, and is recorded here because it was
  acted on.** It read: Compose 1.12 needs AGP 9.1, AGP 9 needs Gradle 9, detekt has no
  Gradle 9 release, therefore upgrading means dropping static analysis. The last step is
  false. detekt 1.23.8 and ktlint-gradle 14.2.0 were both run against Gradle 9.7.1 in this
  environment and both work: detekt analysed and reported findings, ktlint linted and
  reported violations. "No release advertised for Gradle 9" is not "does not run on
  Gradle 9", and the difference is one throwaway project and forty seconds.

  The real reason to hold at Compose 1.11.1 is smaller and duller: taking 1.12 means AGP 9 +
  Gradle 9 + compileSdk 37 in one step, and every piece of that lives on Google Maven, which
  this environment's egress policy refuses (403 at the gateway — `dl.google.com`,
  `maven.google.com` and every mirror tried). It cannot be checked here at all, only in CI,
  four minutes at a time. That is its own change, not a line item in a pass whose job is to
  get the existing code compiling.

- **"Both jobs must be required" is a repository setting, not a file in this repository.**
  Required status checks live in branch protection / a ruleset on `main`, which no API
  available to this session can write. The workflow does everything the code side can: it
  runs on `push` and `pull_request`, and each of the five jobs fails the run on its own.
  Someone with admin on the repository has to add **Build guards**, **Shared JVM tests**,
  **iOS cross-compile**, **Android build + lint** and **Android APK + native** to the
  required checks for `main`. Until that is done a red run does not block a merge, and this
  half of hardening task 1 is not finished.

- **The concurrency key was wrong and every commit built twice.** The group was
  `${{ github.head_ref || github.ref }}`. On a push `head_ref` is empty and `ref` is
  `refs/heads/x`; on the pull request `head_ref` is `x` — different strings, different
  groups, so the push run and the pull-request run for the same commit never cancelled each
  other. `ref_name` is the bare branch name on a push, which is what the second half was
  meant to be. Two macOS runners per commit down to one.

- **The same cross-module smart cast, in the one source set nothing here can compile.**
  `DataStoreSettings.writeInto` narrowed `stopByTime` in an `else` branch. It is a public
  API property of `Settings`, which lives in `core.model`, and Kotlin will not narrow a
  property across a module boundary — the other module could add a custom getter without
  this one recompiling. Identical to `TriageStep`'s latitude/longitude, found a run later
  because `shared/core/data/src/androidMain` needs DataStore from Google Maven, so no local
  harness reaches it. Both are now bound to a local first. A grep over every source set no
  harness compiles found no third instance.

- **The native build's prerequisites had never been satisfied by anything.**
  `shared/native/CMakeLists.txt` shells out to meson (libvmaf builds with it), ninja and
  cargo (oxipng is Rust), each behind `find_program(... REQUIRED)`, and
  `configureCMakeDebug[arm64-v8a]` failed on the first of them. The runner has cargo but
  not the `aarch64-linux-android` target; the Android SDK ships a ninja beside its CMake
  but not on `PATH`, where `find_program` looks. All three are installed in the APK job
  now. Not a workaround: they are the CMake file's documented inputs, and this was the
  first time anything had been asked to provide them.

### The first time `androidApp` was ever compiled

Five faults, in four files that had been written against documentation and never seen a
compiler. Recorded by class, because the class is the lesson:

- **An interface member nobody implemented.** `SafStorage` did not implement
  `LibraryStorage.writeTemp`. It was added for the photo path — jpegli and libjxl return a
  buffer across the C ABI, not a file — and the Android side was never brought along.
  Nothing caught it because `androidApp` had never been compiled.

- **A closed platform value read as if it were open.** `MediaCodecInfo.VideoCapabilities`
  `.PerformancePoint` has no public accessor for its width, height or frame rate; its whole
  interface is `covers()`. `performancePointsOf` read all three. Rewritten to *ask* rather
  than read: it walks a ladder of the shapes this app ever encodes, builds the platform
  point for each, and keeps the ones an advertised point covers. The answer still comes
  from the device — it is now the question the API will answer. A shape missing from the
  ladder is a capability never claimed, which is the safe direction: `canSustain` then
  falls back to the width, height and rate bounds.

- **A Guava return type in a Media3 signature.** `EncoderSelector.selectEncoderInfos`
  returns `ImmutableList<MediaCodecInfo>`, and a filtered `List` does not satisfy it.
  `ImmutableList.copyOf` — Guava is not an addition to STACK.md, it arrives with
  media3-transformer and is part of the interface being implemented.

- **A constructor that does not exist.** `IsoFile(FileDescriptor)`: mp4parser takes a
  `String`, a `File` or a `ReadableByteChannel`, and a SAF document has no path, so the
  channel is the only one of the three a content URI can produce. Confirmed with `javap`
  against isoparser 1.9.56 from Maven Central rather than guessed — the same trick that
  works for every dependency not behind Google Maven. One unresolved constructor produced
  twenty-two errors, because every type downstream of it became unknown.

- **Float where the data needs double.** `ExifInterface.getLatLong(output)` fills a
  `FloatArray`; a float holds about seven significant digits and a latitude needs nine to be
  right to the metre. The no-argument `getLatLong()` returns doubles. Filling the float
  array would have compiled and moved every photo a little.

### Two vendored copies of the same library

`configureCMakeDebug[arm64-v8a]` failed with six CMake errors, all one shape: `add_library
cannot create target "hwy" because another target with the same name already exists`.
jpegli is a fork of libjxl and vendors its own `third_party/highway` and its own `tools/`,
so adding both subdirectories defines `hwy`, `hwy_test`, `hwy_list_targets` and
`tool_version_git` twice. CMake refuses a duplicate target name outright (CMP0002), and
there is no policy that relaxes it. This is exactly the collision PROJECT.md predicted when
it noted that libjxl and jpegli each pull highway, brotli and skcms recursively — predicted
and then not handled, because nothing had ever configured the CMake.

Fixed without patching either submodule:

- **One highway.** `JPEGLI_FORCE_SYSTEM_HWY` sends jpegli down its `find_package(HWY)`
  branch instead of `add_subdirectory(highway)`. The find is made to succeed against the
  copy libjxl has already added by seeding `HWY_INCLUDE_DIR`, `HWY_LIBRARY` and
  `HWY_VERSION`; jpegli's own `FindHWY.cmake` ends with `if (HWY_LIBRARY AND NOT TARGET
  hwy)`, so with the target already defined it creates nothing and every `hwy` reference in
  jpegli resolves to libjxl's real in-tree target. `HWY_LIBRARY` is set to the string `hwy`
  on purpose: anything consuming `HWY_LIBRARIES` then links the target, not a path that
  does not exist until the build runs. The version is parsed out of `hwy/base.h` so it
  cannot drift from the pinned submodule.
- **One `tool_version_git`.** Both projects define it when their version is not pinned.
  Pinning jpegli's to its own `git rev-parse --short HEAD` leaves libjxl's — the `tools/`
  directory that supplies `ssimulacra2.cc` — as the only definition.

Found and fixed locally, not through CI, and the harness is now `tools/build-native-host.sh`:
cmake, ninja, meson and cargo are all present in this environment and the submodules are
checked out, so configuring and building `shared/native` for the host reproduces the arm64
*configure* failure exactly. Only the NDK is missing, and almost nothing that goes wrong in
that CMake is target-specific. It should have existed from milestone 7.

Running it immediately found a second fault the CI run had not reached: **jpegli's public
headers include each other by repository-relative path.** `lib/jpegli/decode.h` opens with
`#include "lib/jpegli/common.h"`, so `jpegli/lib` on the include path resolves the first
header and none of the ones it pulls in. The repository root is now on the path, the same
shape libjxl already had. With it, libvmaf, xpsnr, libjxl, jpegli, brotli and highway all
build and `libtrim_native.a` links — the first time the native tree has been built from
this repository at all.

### The cross build was only half cross

With the target collision gone, `configureCMakeDebug[arm64-v8a]` passed and
`buildCMakeDebug[arm64-v8a]` produced two more:

- **meson was compiling libvmaf for the host.** The NDK ships one generic `clang` driver
  for every ABI; without `--target` it builds for whatever the machine is. CMake passes
  that flag from its own rules, so CMake's targets were fine — but meson only knows what
  `meson-cross.ini` tells it, and that file carried the compiler path and `-march` and
  nothing else. The evidence is unambiguous:

  ```
  error: unknown target CPU 'armv8-a+simd'
  note: valid target CPU values are: nocona, core2, … znver4, x86-64
  ld.lld: error: undefined symbol: main
  >>> referenced by /lib/x86_64-linux-gnu/Scrt1.o:(_start)
  ```

  x86 CPU names and the host's C runtime, out of a compiler asked to build for arm64. The
  cross file now carries `--target` and `--sysroot` from the values CMake resolved, on the
  link line as well as the compile line — meson's sanity check links an executable, and a
  driver told to compile for arm64 but not to link for it reaches for the host's `Scrt1.o`.
  The template renders through the real `configure_file`, checked here against a stand-in
  toolchain, because the host build never takes this branch.

- **`cargo ndk` was not installed.** oxipng is Rust and the CMake shells out to the
  cargo-ndk subcommand, which sets the linker and sysroot for the Android target. The
  runner has cargo and now has the `aarch64-linux-android` target, but the subcommand is a
  separate install, and cargo answered `no such command: ndk` after the C libraries had
  already built. Added to the APK job's prerequisites beside meson and ninja.

- **And then `cargo ndk -o` asked for an artifact this crate deliberately does not
  produce.** With the subcommand installed, libvmaf cross-built and the next failure was
  `No usable artifacts produced by cargo. Did you set the crate-type in Cargo.toml to
  include 'cdylib'?`. `-o` turns on cargo-ndk's artifact collection, which copies
  **cdylibs** into a jniLibs layout — but `trim_oxipng` is a `staticlib` on purpose,
  archived into `libtrim_native.a` rather than loaded as its own `.so`. Dropping `-o`
  leaves cargo-ndk doing the one job needed: set the linker, ar and sysroot, and run
  cargo. The `.a` is read straight out of `target/aarch64-linux-android/release/`.

  Half-checkable here. `rustup target add aarch64-linux-android` and
  `cargo build --release --target aarch64-linux-android` get as far as a transitive
  `cc-rs` build script looking for `aarch64-linux-android-clang` — the NDK compiler that
  cargo-ndk exists to point at. That is the boundary of what this environment can prove,
  and it confirms the shape of the fix rather than the fix itself.

- **AGP was building libjxl's and jpegli's fuzzers, tools and benchmarks.** Both
  `add_subdirectory` calls carry `EXCLUDE_FROM_ALL`, which is meant to say "these targets
  exist so `trim_native` can link them, do not build the rest". AGP overrides that: it
  discovers every target in the CMake graph and names them all on the ninja command line,
  and a named target is built whether or not it is in `all`. So android-arm64 was compiling
  `djxl_fuzzer_runner`, `enc_fast_lossless`, `hwy_list_targets`, both brotli command line
  tools and the rest — none of which this app links, and several of which are not written
  to cross-compile. `defaultConfig.externalNativeBuild.cmake.targets += "trim_native"` asks
  for the one library the APK loads and lets CMake pull in exactly its dependencies.

- **The failure summary could not see a native failure.** `.github/failure-summary.sh`
  matched Gradle- and Kotlin-shaped lines only, so a `buildCMakeDebug` failure summarised
  to the ProcessException heading and the ninja command line — which lists every target and
  not one diagnostic. It now also matches `FAILED: `, `error: `, `CMake Error`,
  `ninja: error` and `undefined reference|symbol`, self-tested against a synthetic ninja log
  that the old pattern reduced to nothing. This is the second time this pass that the cost
  was not the bug but not being able to see it; a summary that silently omits a whole
  toolchain is worse than no summary.

- **A library libjxl does not define unless you ask for its tools.** `ssim2_score.cc` loads
  pixels through `lib/extras/codec.h`, which lives in the `jxl_extras-internal` target — and
  libjxl includes `lib/jxl_extras.cmake` only `if(JPEGXL_ENABLE_TOOLS OR BUILD_TESTING)`,
  both of which this build had set OFF. CMake does not object to a link name it has never
  heard of: it passes it through as `-ljxl_extras-internal`, so the whole tree cross-compiles
  and the failure lands on the last edge of 225, at the link. `JPEGXL_ENABLE_TOOLS` is now
  ON, which is what the comment above it had claimed all along — the code had drifted from
  its own stated intent. It defines libjxl's tool targets without building them, because the
  Android build now names `trim_native` on the ninja command line.

- **A tools source used through its header but never compiled.** `jpegxl::tools::
  NoMemoryManager()` is declared in `tools/no_memory_manager.h`, which both
  `ssimulacra2.cc` and our `ssim2_score.cc` include, and defined in
  `tools/no_memory_manager.cc`, which belongs to a tools library this build does not link.
  Five undefined references at the final link. Compiled into `trim_native` beside
  `ssimulacra2.cc`, which was already there for exactly the same reason.

- **The host harness proved compilation and called it a build.** Both link errors above
  could have been caught locally in seconds and were not, because on the host `trim_native`
  is a STATIC library and `tools/build-native-host.sh` built only that target. Archiving
  never resolves a symbol or looks for a dependency, so "it builds" meant no more than
  "every file compiled". `test_metrics` is the one target in this tree that links
  `trim_native` into an executable, so the harness now configures with
  `-DTRIM_NATIVE_TESTS=ON` and builds it. It caught the `NoMemoryManager` error on its first
  run, before CI ever saw it. The general lesson is worth more than either bug: a local
  harness has to reach the same *kind* of step as the real build, not just the same files.

- **The meson cross file described C and forgot C++.** The `--target` and `--sysroot`
  fix above went into `c_args` and `c_link_args`, which is all libvmaf appeared to need —
  it is a C library. It bundles libsvm, though, and `svm.cpp` is the one C++ file in the
  tree; meson applies `c_args` to C only, so that single object was compiled by the NDK's
  generic `clang++` with no target and no sysroot, which means for the host. Nothing
  objected: it archived into `libvmaf.a` cleanly and surfaced minutes later at the end of
  the arm64 link as `libvmaf.a(svm.cpp.o) is incompatible with aarch64linux`. `cpp_args`
  and `cpp_link_args` now carry the same values. The lesson is about the shape of the
  earlier fix rather than about meson: a toolchain description has to cover every language
  in the dependency, and "it is a C library" is a claim about the API, not about what the
  build compiles.

- **Every vendored native dependency links statically into `libtrim_native.so`.** Left to
  itself libjxl builds `jxl`, `jxl_cms` and brotli's three libraries as shared objects, and
  `libtrim_native.so` carried a DT_NEEDED on each — six `.so` files that all had to reach
  the device or `System.loadLibrary` throws the first time a night pass touches a photo.
  One self-contained library removes the failure mode rather than checking for it. The
  licences permit it: libjxl and jpegli BSD-3-Clause, libvmaf BSD-2-Clause-Patent, brotli
  MIT, Highway Apache-2.0, oxipng MIT — none copyleft, so static linking adds no obligation
  beyond the attribution that applies either way. A future LGPL dependency would have to be
  revisited target by target rather than by flipping `BUILD_SHARED_LIBS` back.

- **The APK's library set is checked by reading the ELF, not a list.** The first version of
  this check compared against six hardcoded names, which is a check that goes stale the
  moment the link line changes — and the static-link decision above changed it immediately.
  `tools/check-apk-libraries.sh` now reads DT_NEEDED out of each packaged `.so` and requires
  every entry to be either packaged for the same ABI or part of the NDK's documented stable
  ABI. It has a self-test that plants three violations, because a check that cannot fail is
  not a check — and the self-test earned itself on first run by finding a real bug: `unzip
  -Z1` writes "Empty zipfile." to *stdout*, so an APK containing no native libraries at all
  was parsed as two filenames and passed.

- **The app had never been started.** Everything else here is checked without a device —
  shared logic on the JVM, boundaries by the guards, compilation on four targets — and none
  of it can catch what only happens when Android loads the app: a missing native library, a
  theme that resolves at compile time and throws at inflate time, a Koin graph with a cycle,
  a manifest that merges to something the launcher will not start. Each is a crash on first
  run from a build that went green. `MainActivityLaunchTest` asserts the smallest thing that
  exercises all of them: the activity reaches RESUMED, and survives recreation. It is
  deliberately not a UI test — what is on screen belongs in tests that can afford to be
  wrong about layout.

- **The smoke test runs on x86_64 under KVM, and that is a deliberate exception to the one
  shipped ABI.** The first attempt reasoned: the APK is arm64-v8a, so the emulator must be
  arm64, so the host must be Apple silicon, so `macos-14`. The last step is wrong — GitHub's
  macOS runners are themselves virtual machines with no nested virtualisation, so
  Hypervisor.framework refuses (`HVF error: HV_UNSUPPORTED`) and the device never boots. No
  hosted runner can virtualise arm64 Android; every one that can boot an emulator is x86_64.
  So a `smoke` build type adds x86_64 to arm64-v8a, and only that build type: `release`
  still carries a single `abiFilters` entry, so shipping x86_64 would take a deliberate
  build-file change. The native tree now describes both ABIs properly — the `-march` flag
  moved out of Gradle (which applies `cFlags` to every ABI and would have handed an ARM
  architecture name to the x86_64 compiler) and into CMake, which knows which ABI it is
  configuring, and the meson cross file's `cpu_family`, `cpu` and arch argument are
  substituted per ABI instead of being hardcoded to aarch64. The extra cross-compile is
  cached. Nothing on x86_64 is measured and no number from it is a finding: the variant
  exists to prove the app starts, which nothing else here does.

- **The macOS smoke job is gone rather than kept on manual dispatch.** It could not run at
  all, so it offered nothing the Linux job does not.

- **`testBuildType` has to follow the smoke variant.** AGP builds an androidTest component
  for exactly one build type and defaults to `debug`, so adding a `smoke` build type gave it
  no instrumented tests and the managed device no task to run —
  `Cannot locate tasks that match ':androidApp:pixelSmokeAndroidTest'`. Setting
  `testBuildType = "smoke"` is what makes the tests build against the variant with an ABI the
  emulator can install. Worth writing down because it is invisible: everything about the
  build type, the device and the workflow was right, and the tests silently did not exist.

- **The native jobs have a 90-minute cap and a cache.** A cold arm64 cross-compile of
  libjxl, jpegli, brotli, lcms and Highway is about seven minutes, so 90 is far above the
  observed time and far below the six-hour default a wedged toolchain would otherwise sit
  through. The cache covers `androidApp/.cxx` and oxipng's `target/`, which Gradle's own
  cache does not, and is keyed on the CMake inputs plus the exact submodule commits — a
  submodule bump or a CMakeLists edit misses and rebuilds, because serving a stale object
  for a changed source is worse than not caching.

- **Free undo retention is 30 days, not 7.** MONETIZATION.md gave the free tier 7 while
  BUILD.md § 6 promised a 30-day default, and the code resolved it by clamping — so a free
  user saw the 30 they had been promised and got 7. "Free space" mode's whole premise is
  that originals are recoverable for the window the user was shown; a paywall that shortens
  it deletes photographs three weeks before they expect it, and deleting someone's only copy
  is not a conversion moment. Free is now 30 — the § 6 promise, kept — and Pro's value is
  the extension to 90, which takes nothing away. Both documents and the free-tier tests say
  the same thing now, including a new test that Pro is the only tier that can go past the
  free ceiling.

- **iOS replace is behind `FeatureFlags.IOS_REPLACE_ENABLED`, off.** `SafeReplacerIos.commit`
  refuses before opening a change block, and a shared test fails the build if the constant is
  flipped — so the only way to enable it is to open that test, read why it was off, and run
  the PhotoKit change-block atomicity procedure now written out in full in the
  device-required list above. The flag is not hiding unfinished code: the sequence is written
  and its rollback is tested against a fake that throws at every step. What is untested is
  whether PhotoKit itself rolls back rather than half-applying, which no test that avoids a
  real photo library can establish, and whose failure mode is the original deleted with
  nothing to restore from. Read paths, preflight, encode and `saveCopy` are unaffected: this
  build can measure and can save a copy, it cannot replace.

- **libvmaf's x86_64 SIMD is hand-written assembly, and needs nasm.** `libvmaf/src/
  meson.build` hard-requires an assembler to build it; arm64 never does, because there the
  SIMD is NEON intrinsics clang compiles itself. That asymmetry is the whole reason the
  emulator variant broke while the shipped ABI had been green for hours, and it is worth
  remembering generally: adding an ABI can add a *toolchain* dependency, not just a target
  triple.

- **The failure summary hid the answer for two rounds, and that is the more useful bug.**
  meson's linker-detection probe deliberately links a program with no `main` in order to
  read the linker's version banner, so it prints `ld.lld: error: undefined symbol: main`
  on every successful configure. Those lines matched the summary's `error:` pattern, came
  first chronologically, and pushed `ERROR: Program 'nasm' not found` past `head -30`. Two
  diagnoses were made from that noise and both were wrong — the second one confidently, in
  a commit message, on the strength of an error that referenced Android's own
  `crtbegin_dynamic.o` rather than a host runtime, which should have been the tell. Lines
  carrying `ERROR:` or `not found` are now hoisted above everything else. A summariser that
  ranks by position rather than by significance will eventually rank noise above the cause,
  and when it does it costs more than having no summariser at all.

- **The instrumented tests had never been compiled either, and had rotted.**
  `Milestone1EncodeTest` referenced `caps.hardwareHevc`, a property milestone 12 removed
  when it split `CodecCaps` into one `EncoderCaps` per codec — AV1's ceiling is commonly
  lower than HEVC's on the same chip, and a single flag hid that. The test kept the old
  name across four milestones because `testBuildType` was `debug` and CI only ever ran
  `assembleDebug`, so no androidTest source set was ever built. Pointing `testBuildType` at
  the smoke variant compiled it for the first time and the reference failed immediately.
  Worth noting what this says about the shape of the gap: the emulator job's value is not
  only that it runs a test, it is that it *compiles* a source set nothing else touches.

- **`testInstrumentationRunner` named a class that was never on the classpath.** The
  emulator booted, installed the APK and started instrumentation, then died with
  `ClassNotFoundException: androidx.test.runner.AndroidJUnitRunner`. That setting has been
  in `androidApp/build.gradle.kts` since milestone 1 and `androidx.test.ext:junit` has been
  an `androidTestImplementation` nearly as long — but ext:junit brings `androidx.test:core`
  and `monitor`, not the runner. Nothing could reveal it until an instrumented test was
  actually executed, which had never happened. `androidx.test:runner` is added and recorded
  in STACK.md; it is test-only and never shipped. The pattern is by now familiar enough to
  name: configuration that points at something absent is invisible until the thing is used,
  and this repository had four such settings — the ABI split, the eager task lookup, the
  detekt source set, and now the runner.

- **The workflow ran on `push` and `pull_request`, and that is what blocked the merge.**
  A commit on a feature branch fired both, each producing check runs with the *same* names
  — the names branch protection requires — and the concurrency group then cancelled one
  side. Every head SHA therefore carried six cancelled required checks beside six
  successful ones, and a cancelled required check is not a pass. The pull request read as
  `blocked` with every job visibly green, and nothing could clear it: re-running or
  approving did not help, because the next push recreated the pair. `push` is now
  restricted to `main`, so a feature branch gets exactly one run per commit through
  `pull_request` and `main` gets its own after a merge; nothing goes unbuilt, because a
  commit only reaches `main` through a PR that already built it.

  Worth recording that the wrong diagnosis was mine and cost real time. Seeing
  `mergeable_state: blocked` with green checks and no reviews, I concluded the ruleset's
  `require_extra_approval_for_unattributed_changes` was unsatisfiable on a solo repository
  — which is true, but was not what was blocking. I read the *rules* and never read the
  *check runs on the head SHA*, where six cancelled entries named exactly the required
  contexts. The lesson is the same one the meson failure taught: when a state is puzzling,
  read what the system actually recorded about that object rather than reasoning forward
  from the configuration.

- **x86_64 is opt-in, not part of the smoke variant.** Both `pixelSmokeAndroidTest` and
  `connectedSmokeAndroidTest` build the same `smoke` build type, so an unconditional second
  ABI made every physical-device run cross-compile libjxl, jpegli, libvmaf and oxipng twice
  — the second time for an architecture that phone cannot execute. It now sits behind
  `-Ptrimgallery.smoke.x86_64=true`, set by the CI emulator job and nowhere else. The
  configuration-time assertion follows the same rule: arm64-v8a is required always, because
  it is what ships and a smoke run without it is not testing the real artefact; x86_64 is
  required only when the property asked for it, since asserting it unconditionally would
  fail exactly the physical run this change exists to keep cheap.

- **`const val` at the top of a `.kts` file is a configuration-time failure.** A Kotlin
  script's top level is the body of an implicit class, so `const` is rejected there —
  `Const 'val' are only allowed on top level, in named objects, or in companion objects`.
  Because it fails script *compilation*, every job in the workflow goes red, including the
  ones that touch nothing Android and the separate review workflow. A plain `val` is
  correct.

  The reason it reached CI is worth more than the fix: **nothing local compiles
  `androidApp/build.gradle.kts`.** That script needs AGP, which lives on Google Maven, which
  this environment's egress policy refuses — so the local harness stages build scripts for
  *ktlint*, which parses them but does not type-check or compile them. A syntactically valid
  script with a semantic error passes every check available here and fails everything in CI.
  That is the third configuration-time fault to reach CI this way, after the ABI split and
  the eager `tasks.named`, and the pattern is identical each time: an error in the build's
  own configuration is invisible to a harness that only runs the build's *tasks*.
## AGP 9 / Compose 1.12 upgrade

- **The whole version set had to move in one commit.** androidx.compose 1.12.0 declares
  `minAgpVersion=9.1.0` and `minCompileSdk=37` in its AAR metadata, so Compose Multiplatform
  1.12.0 cannot land without AGP 9.1, Gradle 9, compileSdk 37 and — because it was held only
  by compileSdk 36 — coil 3.6.0. Splitting them would just fail `checkDebugAarMetadata` one
  dependency at a time. The lifecycle pair moved too: `org.jetbrains.androidx.lifecycle`
  2.10.0 resolves to `androidx.lifecycle` 2.10.0 (read from the Gradle module metadata on
  Maven Central), which is what pulls androidx.compose to 1.12.0 from behind Compose
  Multiplatform's back — so holding Compose down while that line moved achieved nothing.

- **Compose 1.12 deprecated two plugin accessors.** `compose.ui` and `compose.uiTooling` are
  now errors — "Specify dependency directly" and "Use org.jetbrains.compose.ui:ui-tooling
  module instead". They are catalogue entries now, versioned from the same
  `composeMultiplatform` reference so there is still exactly one Compose version in the
  build. `compose.runtime`, `compose.foundation` and `compose.material3` were not deprecated
  and are unchanged. `compose.ui` was used in `shared/core/ui` as well as `androidApp`, so
  fixing only the site CI named would have failed on the next module.

- **AGP 9 supplies Kotlin, and rejects the Kotlin plugin.** The first CI error class of
  this upgrade:

  ```
  Failed to apply plugin 'org.jetbrains.kotlin.android'.
    The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support
    since AGP 9.0.
  ```

  Removed from `androidApp` and `benchmark`, from the root `apply false` list, and from
  the catalogue, so the alias cannot be reintroduced by autocomplete. `kotlin-multiplatform`
  is untouched — the shared modules are not Android-plugin projects, and the rejection is
  specific to applying `kotlin.android` alongside AGP 9's built-in Kotlin.

  It failed *configuration*, so it took all six checks with it — including **iOS
  cross-compile on macOS, which died on `androidApp/build.gradle.kts`** while building for
  `iosArm64`. That is the same lesson this file already records twice: Gradle configures
  every project on every invocation, so a fault in one module's build script is a fault in
  every job, whatever that job asked for. Three configuration-time faults reached CI before
  this one (the ABI split, the eager `tasks.named`, the top-level `const val`); this is the
  fourth, and the reason each was invisible locally is unchanged — a harness that runs the
  build's *tasks* cannot see errors in the build's *configuration*.

- **AGP 9 will not sit alongside `kotlin.multiplatform` at all.** The second error class,
  and the one that turns this from a version bump into a build-system migration:

  ```
  The 'com.android.library' (or 'com.android.application') plugin is not compatible with
  the 'org.jetbrains.kotlin.multiplatform' plugin since AGP 9.0.
  Solution:
    - [Recommended] Replace the 'com.android.library' plugin with the
      'com.android.kotlin.multiplatform.library' plugin.
    - Or set the Gradle property 'android.builtInKotlin=false' and 'android.newDsl=false'
      to temporarily bypass this issue.
  ```

  All 14 shared modules apply both plugins, so this is every one of them. The saving grace
  is that their `android` blocks are uniform and thin — namespace, compileSdk, minSdk,
  `ndk { abiFilters }`, `compileOptions` — and only `shared/core/data` has an `androidMain`
  source directory at all; the other 13 are pure `commonMain`.

  Two things made this a decision rather than a fix. First, `com.android.kotlin.multiplatform.library`
  has no `ndk { abiFilters }` block, so the library-module half of the two-place ABI defence
  goes away (these modules ship no `.so`, so nothing breaks — but the belt-and-braces does).
  Second, the bypass is not free either: `android.builtInKotlin=false` disables the built-in
  Kotlin that the previous commit removed `kotlin.android` *for*, so taking it would mean
  putting that plugin back. The two error classes are coupled, in opposite directions.

  **Decision: take the recommended migration, not the bypass.** The bypass is a deprecation
  runway that closes on Google's schedule, and it would have to be undone anyway; doing the
  work once is cheaper than doing it twice with a revert in between. All 14 modules moved to
  `kotlin { androidLibrary { … } }`: `androidTarget()` is gone (the block declares the
  target), `namespace`/`compileSdk`/`minSdk` moved inside it, and `compileOptions` became
  `compilations.configureEach { compilerOptions { jvmTarget = JVM_17 } }`, since the new DSL
  has no `compileOptions`.

  **What the dropped `abiFilters` cost, precisely.** Nothing programmatic depended on it:
  the ABI set is decided by `androidApp`'s own `defaultConfig.ndk.abiFilters`, asserted in
  that module's `afterEvaluate`, and verified against the built APK by
  `tools/check-apk-libraries.sh` reading `DT_NEEDED`. What was lost is a claim two comments
  made — `androidApp/build.gradle.kts` said "`abiFilters` is what every library module in
  this project already uses" and README.md described an ABI split that had already been
  removed in the hardening pass. Both are corrected here rather than left to rot: a comment
  that describes a defence which no longer exists is worse than no comment, because the next
  person budgets for protection they do not have.

- **AGP 9.1 deprecates the block its own error message recommends.** Migrating to
  `androidLibrary { }` — the name printed in the incompatibility error — produced:

  ```
  'androidLibrary' ... is deprecated. The 'androidLibrary' block is deprecated.
  Please use 'android' instead.
  e: shared/engine-api/build.gradle.kts:23:41: Unresolved reference 'jvmTarget'.
  ```

  The block is `kotlin { android { … } }`, not `androidLibrary`. The error text and the
  developer.android.com page it links are a release behind the plugin they describe; the
  compiler is the authority, not the documentation.

  The second half is the more useful correction. `compilations.configureEach {
  compilerOptions.configure { jvmTarget.set(…) } }` does not resolve on this target, and
  rather than hunt for the shape that does, the JVM level is now `jvmToolchain(17)` at the
  `kotlin { }` level. That covers the `jvm()` and android targets in one line, and it is the
  idiom `androidApp` has been using all along — so it is already proven on this CI rather
  than being a second guess. Reaching for a construct the repo already runs beats reaching
  for the one the migration guide suggests.

- **The local harness was testing the wrong Gradle.** It invokes the system `gradle`, which
  is 8.14.3, not the wrapper — so every "local checks passed" on this branch was exercising
  the version being upgraded away from. Re-run against a downloaded Gradle 9.7.1, the whole
  local surface passes: shared modules, SQLDelight, ktlint, detekt, the guards over 194
  files, and the guard self-tests. That is the half of this upgrade provable here; AGP 9.1.0,
  compileSdk 37 and androidx.compose 1.12.0 live on Google Maven, which this environment
  refuses, so they are CI-only.

### The guards guard themselves

- **A rule declares the languages it polices, and must have a planted violation in each.**
  `GuardSelfTest` fails the build for a rule in `DEFAULT_RULES` with no probe. Between
  milestones 4 and 15 the codec and replacer rules carried Swift patterns that had never
  been run against any Swift, because the harness globbed only `.kt` — they were comments
  written as regular expressions and nothing could tell the difference.
- **Every probe carries a clean counterpart.** A rule that matched everything would pass a
  firing test while failing at its job, so each planted violation ships with the honest
  version of the same file.
- **The forbidden lists are enumerated, not sampled.** Every permission in the manifest
  guard's list and every key in the plist guard's is asserted to be one it actually catches,
  so adding an entry that the matcher does not see fails here.
- **Self-tests run before the guards are trusted.** Same CI job, earlier step.

### The thermal pause floor

- **A minimum pause duration, on both platforms, defaulting to 60 s.** Milestone 15 recorded
  that iOS's four discrete states leave the hysteresis nothing to bite on. The floor damps by
  *time* rather than by shape, so it works for a continuous Android reading and a discrete
  iOS one alike — one stand-down per minute instead of one per oscillation.
- **The floor delays resumption only, never protection.** Heat pauses the pass on the
  reading that reports it, always. A gate that made a phone wait to start protecting itself
  would be worse than no gate.
- **The clock is a parameter, not something the gate reads.** The guards already have one,
  and a gate that read its own would be a gate no test could wind forward.
- **`ThermalState.HELD_FAIR` stays the non-default.** With the floor in place it is belt and
  braces rather than the only defence, so ARCHITECTURE.md § 6's "run at fair" can hold.

## Device-required verification

Things that cannot be established without hardware. Each one has the procedure to run, so
the answer comes back comparable rather than anecdotal. Nothing here is faked in a test:
a green suite that asserts made-up platform behaviour is worse than an open question,
because it looks like an answer.

**Thermal behaviour on iOS.** Whether `ProcessInfo.thermalState` actually oscillates around
a boundary, and how often. *Procedure:* run a night pass on a plugged-in iPhone in a warm
room, log every `thermalStateDidChangeNotification` with a timestamp for two hours, and
count transitions per minute. If serious↔fair swings exceed roughly one a minute, switch
`ThermalState.FAIR` to `HELD_FAIR` — the constant is already there and tested. The 60 s
pause floor should already reduce the *pauses* to one a minute regardless; this measures
whether the underlying signal justifies the stronger fix.

**PhotoKit change-block atomicity — the gate on `FeatureFlags.IOS_REPLACE_ENABLED`.**
`SafeReplacerIos.commit` refuses before opening a change block while that flag is off, and a
shared test fails the build if it is flipped, so this procedure is the only thing that turns
iOS replace on. Run it, record the result here, then remove the flag and its test together.

*Procedure, in full:*
1. On a device, with a throwaway library, replace an asset that belongs to a user album.
   Confirm the replacement carries creationDate, location, favorite and album membership.
2. Force a failure *inside* the block, after the delete request and before it returns — add
   the asset to a collection made read-only from another app, or to a shared album the
   account cannot write. Confirm in Photos that **the original is still present and no new
   asset was created**. This is the assumption everything rests on; if the original is gone,
   stop and do not enable the flag.
3. Repeat with the asset in the Hidden album, and again with one in a smart album.
4. Kill the app mid-block (background it and force-quit during the write) and confirm the
   same: either both changes applied or neither did.
5. Record the device, iOS version and outcome of each step below. A pass on one iOS version
   is not a pass on the next major one — this is worth re-running per release.

*Result: not yet run.*

**PhotoKit change-block semantics.** That a failure re-applying album membership really does
roll back the delete in the same block. The shared tests assert what the sequence does when
`commit` throws; they cannot assert that PhotoKit throws rather than half-applying.
*Procedure:* on a device, replace an asset that belongs to an album you delete from another
app mid-block (or inject a failure by adding to a collection you have just made read-only),
then confirm in Photos that the original is still present and no new asset was created.
Repeat with the asset in the Hidden album. **Until this is confirmed, the iOS replace path
should not ship**: everything else in the § 7 contract rests on that atomicity.

**Whether `isHidden` can be set on a creation request.** `PHAssetChangeRequest.isHidden`
exists, but hidden assets are subject to their own authorisation and the write may be
silently ignored. *Procedure:* replace an asset in the Hidden album and confirm the
replacement is still hidden. If it is not, the preflight must treat `hidden = true` as
`WOULD_LOSE_STATE` rather than as a carried property — a one-line change to
`ReplacePreflight`, and the test that pins the current behaviour will fail, which is the
point of having it.

**Smart-album membership after replacement.** That the derived smart albums really do
re-derive. *Procedure:* replace an asset that is in Favourites, Recently Added and Videos;
confirm the replacement appears in all three without being added to any.

**The milestone 1 encode itself — physical devices only.**
`Milestone1EncodeTest.encodesToHevcWithAudioPassthroughAndPlaysBackInFull` requires a
hardware HEVC encoder, and there is no configuration in which CI can supply one: a hosted
emulator has no hardware codec, and BUILD.md § 2 rule 2 forbids the software fallback that
would make it run. So the test is gated on `assumeTrue(caps.hevc.hardware)` and **skips on
every CI run, by design**. That skip is the rule being observed, not a hole — the day it
stops skipping in CI, something has been weakened.

What the emulator does cover is `reportsCodecCapabilities`, which always runs: it exercises
the real `MediaCodecList` walk, the hardware filter and the performance-point probe on an
Android runtime, and logs what the device offered. It asserts only self-consistency — an
encoder reporting no hardware must not also advertise a ceiling — because requiring
hardware there would be requiring the impossible. The smoke job's remit is therefore
install, launch, and this capability report; the encode is a device test.

*Procedure:* on each of the three field-test device classes, run
`./gradlew :androidApp:connectedSmokeAndroidTest` with the device attached — arm64-v8a
only, because the second ABI is behind `-Ptrimgallery.smoke.x86_64` which only the CI
emulator job sets — and confirm the encode test runs rather than skips, produces HEVC video with the audio track transmuxed
rather than re-encoded, and plays back at full duration. Record the device, chip and the
`reportsCodecCapabilities` log line for each. A skip on physical hardware means the device
genuinely has no hardware HEVC encoder, which is itself a finding worth recording against
that chip.

**Encoder quirks.** Real-time multiples per chip, whether AV1 sustains its advertised
points, and the XPSNR↔VMAF calibration per bucket. All of it is FIELD_TEST.md, which is the
procedure; none of it can be inferred from a desk.

**Reduce-motion and TalkBack.** DESIGN_SYSTEM.md's motion springs and the content
descriptions on the grid, viewer and result card. *Procedure:* enable Remove Animations and
TalkBack, then walk the grid → viewer → result-card flow and confirm every control is
reachable and announced, and that no shared-element transition runs. Compiling is no longer
the obstacle — CI does that on every push — but nothing has drawn a pixel, and motion and
screen-reader behaviour are only observable once something does.

## Open questions

Rewritten at the end of the hardening pass, because most of the list had stopped being
true. Every entry that read "has never been compiled" is gone for the Kotlin and Swift
layers: since the CI work above, every Kotlin source in this repository is compiled on every
push for the JVM, for Android, for `iosArm64` and for `iosSimulatorArm64`, and every Swift
source is parsed. The android-arm64 native build is the one still being driven to green —
it now configures and links the right target, and what remains open about it is tracked as a
build task rather than as a question about the code. What is left below is grouped by what
would actually settle it, because "open" covers three quite different situations and mixing
them made the list useless as a plan.

### Needs a device, or the field test

The procedures are in **Device-required verification** above and in FIELD_TEST.md. Nothing
here is faked in a test: a green suite asserting made-up platform behaviour is worse than an
open question, because it looks like an answer.

- **The XPSNR↔VMAF threshold is still milestone 2's provisional one** — software x265, one
  640×360 clip — and AV1 has no calibration at all. `CodecLadder.xpsnrThreshold` returns the
  HEVC numbers for AV1, which is a placeholder rather than a finding: XPSNR is a proxy for
  VMAF and the mapping depends on what the artefacts look like, which AV1's and HEVC's do
  not do alike. The fitting and the per-bucket harness exist and sweep either encoder
  (`./calibrate.sh clip.mp4 out.csv av1`); what is missing is the sweep on device, per
  (resolution, codec) bucket, against the milestone 1 encoder.
- **`AV1_BITRATE_RATIO` is a literature number, not a measurement.** Two thirds is the
  conservative end of what codec comparisons report for hardware encoders. It only sets
  where the first probe lands, so being wrong costs a probe rather than quality — but it is
  the kind of constant milestone 13 should replace with something this app measured.
- **The field test has not been run, and no number in this repository is a field-test
  result.** It needs three device classes, a fortnight and a real library.
- **Nothing here has been *run* on Android.** Compilation is now proven on every push; the
  SAF grant mechanics, the mp4parser rewrite-and-rename, the `MediaExtractor` probe, the
  WorkManager constraints and the SQLDelight repository have still never met a real
  MediaStore. The decision logic under them — verify, ladder, replace ordering, rollback,
  offload, guard order, thermal hysteresis, budgets, the alarm window, the run loop — is
  verified on the JVM, which is why it was pushed there.
- **Nothing Compose has been rendered.** It compiles; no pixel has been drawn. That covers
  reduce-motion and TalkBack (procedure above), whether the grid holds its frame budget at
  the smallest cell size, and every API detail that type-checks but looks wrong.
- **The iOS replace path is unconfirmed on hardware** — PhotoKit change-block atomicity,
  `isHidden` on a creation request, smart-album re-derivation, and whether
  `ProcessInfo.thermalState` really oscillates. Procedures above. **Until the atomicity one
  is confirmed, the iOS replace path should not ship.**
- **Whether the GL tee for play-to-compress costs a dropped frame at 4K60.** Feeding the
  decoder's frames to an encoder input surface while they also reach the screen is a
  `GlEffect` on `ExoPlayer.setVideoEffects`; the cost is not a thing that can be reasoned
  out. `PlayToCompressTap` is the finished half: the ExoPlayer callbacks mapped onto the
  shared state machine, with the encoder behind a four-method `EncoderSink` seam.
- **Whether a map pack can be opened without copying it into app storage.** The document
  picker returns a `content://` URI and SQLite needs a real path; a pack is tens to hundreds
  of megabytes, so the copy is a real cost. A custom VFS over a `ParcelFileDescriptor` may
  avoid it — worth checking on device before settling for the copy.
- **Whether `MediaTranscodingManager` beats the in-app pipeline on any target device.**

### Needs a decision, not a machine

- **Which face-embedding model.** ARCHITECTURE.md § 6 says LiteRT on Android and Core ML on
  iOS, "same model converted"; none is chosen on either. It is load-bearing: `MlKitIndexer`
  returns normalised landmark geometry in the embedding's place, which exercises the whole
  path but is not a face embedding, and `FaceClustering`'s 0.72 threshold was chosen for the
  properties of a real embedding. Clustering quality changes on both platforms at once when
  one is picked, which is the argument for choosing before the Android launch rather than
  after.
- **Whether the iOS viewer is Compose Multiplatform or SwiftUI.** The shared-element motion
  in BUILD.md § 9 is the part that might want SwiftUI. Revisit at milestone 8's iOS half.
- **Whether Memories gets music, and on what licence.** BUILD.md § 9 says "Memories / On this
  day with music" and MONETIZATION.md puts it in Phase 2's Pro+ tier, so the v1.1 feature is
  the memory and the music is a later, paid addition. It also has a problem this repository
  cannot solve: an app with no network cannot stream a track, so music means bundling audio,
  and bundled audio means licensing. Worth settling before it is promised in a store listing.
- **Whether an edit recipe gets a table.** BUILD.md § 9's *"non-destructive"* is already
  satisfied by keeping the original, so nothing needs a stored recipe to be correct. What a
  recipe table buys is re-opening an edit to adjust it. `EditRecipe` is already a plain
  serialisable value, so it is a column when it is wanted — with a schema migration attached,
  which is what makes it a decision rather than a chore.
- **Whether `run_session` gets a failure column.** A night that falls over leaves no row
  saying so: `StopReason` has no value for "threw". `NightWorker` logs the exception rather
  than discarding it, but a diagnostics export still cannot answer "why did last night do
  nothing", which is the first question a field test asks. It wants a nullable column, so it
  belongs with the first migration.

### Written and not built, knowingly

Each of these is a named gap with a reason, not an oversight. None of them is blocked on
tooling any more.

- **`VideoOptimiseStep`.** `NightRun.Step` is unbound: the step that chains triage → search
  → encode → verify → replace is the missing centre of the night pass. Two things wait on
  it. Stage-boundary resume has no stage boundaries to interrupt at — only file boundaries,
  which are covered — so the test that kills at each stage should land with the step. And
  `CodecChoice.MeasuredSpeed` has no producer: the numbers are in the `job` table
  (`realtime_multiple` per row, `engine` naming the codec) but nothing aggregates them per
  device and codec. It is one query, and it belongs with the step. Until then the caller
  passes null, which means "try AV1" — the correct behaviour for an encoder nothing has
  measured.
- **The editor's renderer.** `EditRecipe` says exactly what to do to the pixels and
  `EditRender` says how little work it takes; the shader chain that applies eight sliders to
  a bitmap and to a video frame is not written. The two halves that would be wrong invisibly
  — the geometry and the save policy — are the halves that are.
- **The map canvas.** Tile source, clustering, trips and memories are written and tested;
  the Compose canvas that draws pins over tiles is not. `MbTilesFile` is written to
  documented SQLite behaviour and has never been run.
- **The Settings → Privacy row that exports diagnostics.** The report builder, the redaction
  and the Android file-and-share are written and tested; nothing calls them.
- **Most of the iOS adapter matrix.** `PhotoKitStorage`, `AVAssetWriterEncoder`,
  `YuvSourceIos`, `VisionIndexer`, `UndoBinIos`, the `CGImageDestination` photo path, the
  `AVPlayerItemVideoOutput` tap, and the cinterop binding `shared/native` for ios-arm64.
  Each implements an interface that already exists and is already exercised by fakes in the
  shared tests, which is the point of having written them that way. Four adapters are done —
  the replacer, the scheduler, the thermal guard, the codec factory — chosen because their
  contracts are the ones where getting it wrong loses a file or a user's albums.
- **There is no Xcode project, so `xcodebuild` does not run in CI.** The iOS job compiles
  every shared module for both iOS targets and runs `swiftc -parse` over every Swift source,
  which catches syntax and, for the Kotlin half, real cross-compilation. It does not catch a
  Swift type error against the framework header, because there is no framework consumer to
  build. That closes when the iOS app target is created at milestone 8's iOS half.
- **`androidApp` still has no `storage/` and `scheduler/` packages.** The twenty engine
  files now live in `app/trimgallery/engine/android/`, matching the package they always
  declared; the split ARCHITECTURE.md § 3 describes has not been made. Now a mechanical
  move that CI would check, rather than an unverifiable one.
- **The migration harness has nothing to cover yet.** The schema is at version 1 with no
  `.sqm` files, so "every version pair" is vacuously satisfied. What is missing is the
  harness that would stop the *first* migration landing untested; it should be written in
  the same change as that migration.

### Not in this repository at all

- **"Both jobs must be required" is a repository setting.** Required status checks live in
  branch protection or a ruleset on `main`, which no API available to this session can
  write. The workflow does everything the code side can: it runs on `push` and
  `pull_request`, and each of the five jobs fails the run on its own. Someone with admin has
  to add **Build guards**, **Shared JVM tests**, **iOS cross-compile**, **Android build +
  lint** and **Android APK + native** to the required checks for `main`. Until that is done
  a red run does not block a merge.

### Standing rule

- **The detekt baseline is 37 findings, and should only ever shrink.** They are shape rather
  than defect: five long `when` chains, seven six-parameter functions, a thirty-method
  repository. Each is working, tested code, and refactoring to a threshold in a hardening
  pass is churn with a risk attached. Anything new fails the build.

## Bugs the sweep found, kept for the record

These were live defects in code that was already written and already tested. They are here
rather than in a changelog entry because each one says something about where the tests were
looking and where they were not.

- **`Predictor.bounds` could construct invalid bounds and throw.** A confident entry whose
  learned setting fell entirely outside the fallback bracket produced `low > high`, which the
  `Bounds` constructor rejects — a crash in the night pass from a table row that was merely
  out of date. It became reachable at milestone 12, when the fallback bracket started being
  derived from the source's own bitrate rather than being a fixed wide range. Now the
  non-overlapping case falls back, which is also the right answer: a prediction that does not
  intersect the search space is not a prediction about this file. Found by a property test
  over settings, sample counts and variances rather than by a case somebody thought of.
- **The undo sweeper did not check that the job succeeded.** `TrashPolicy.expired` filtered
  on state and expiry alone, so an entry left by a night that died between the journal write
  and the job's status update — or by a rollback that could not reach the row — would be
  swept thirty days later, deleting what may be the only copy of that file and reporting the
  space as freed. The job's state is now a *required* argument: a default of "assume it
  succeeded" would put the hole straight back and no test would see it.
- **`History` offered a one-tap restore on iOS that the platform does not have.**
  `UndoLocation.SYSTEM_TRASH` fell through to `FromBin`. PhotoKit has no API to restore from
  Recently Deleted, by design, so the button would have failed every time. It is now its own
  state with its own copy and an Open Photos action.

## Why the review workflow posted nothing

The `review` check passed on three pull requests without reviewing any of them. Two
separate faults, one after the other.

First, `ANTHROPIC_API_KEY` was never set, so every run since the workflow was added died
at credential validation in about twenty seconds. That one was loud — a red check on
every PR — and was fixed by setting the secret.

The second was silent, and worse. With the key in place the job ran a real review — 18
turns, 108 seconds — and posted nothing, so the check went green. A planted
software-encoder fallback (PR #4, closed unmerged) passed it without a word.

The cause was the workflow, not the model. In agent mode the action runs the prompt and
nothing publishes the result: `track_progress` was false, so no tracking comment existed
for the agent to write into, and the prompt asked for a review without ever saying to
post one. `show_full_output` is false, so whatever it concluded went to a hidden log.
`permission_denials_count: 1` in the result suggests it tried something and was refused.

The fix is `track_progress: true` plus an explicit, unconditional instruction to post —
including when there is nothing to report. The prompt now also tells the reviewer to
judge behaviour rather than location, because the build guards already cover location and
the gap between them is exactly where the planted violation lived: inside
`MediaCodecFactory`, where constructing a codec is legal and a software fallback is not.

**The general lesson, which is the third time this project has met it.** A check that can
only pass is not a check. The build guards have planted-violation self-tests for this
reason; the hooks in `tools/git-hooks` have them; this reviewer had nothing, and spent
weeks green while doing nothing. Before trusting any new check, make it fail on purpose
once.

### The review workflow cannot be tested on its own pull request

`claude-code-action` refuses to run when the workflow file differs from the copy on the
default branch:

```
Skipping action due to workflow validation: Workflow validation failed. The workflow
file must exist and have identical content to the version on the repository's default
branch.
```

That is a sensible security property — it stops a pull request from rewriting the
reviewer that is about to review it — and it means the usual trick of relying on
`pull_request` workflows running from the merge commit does not apply here. Both #6 (the
fix) and #7 (the fix plus a planted violation) had their `review` job exit in about
eleven seconds without reviewing anything.

So a change to this workflow can only be validated **after** it lands on `main`, by
opening a pull request that does not itself touch the file. The calibration PR has to be
re-run at that point, not before.

Worth recording because it also validates the earlier finding rather than undermining it:
PR #4's review ran fully — 18 turns, 108 seconds — precisely because its workflow file
*was* identical to main's. That test was sound, and its result stands: the reviewer read
a planted software-encoder fallback and said nothing.

## Development guardrails

Three failures in this project were process failures, not code failures, and each is now
prevented by a mechanism rather than by remembering.

- **The harness was testing the wrong Gradle.** It invoked the system `gradle` (8.14.3)
  while the wrapper pinned 9.7.1, so every "local checks passed" during the AGP 9 upgrade
  exercised the version being upgraded away from. Both are real Gradle and both build, so
  nothing in the output gave it away. `tools/checkall.sh` now invokes `./gradlew` and has
  no way to invoke anything else, and `tools/wrapper-version.sh` compares
  `./gradlew --version` against `distributionUrl` and refuses to continue if they differ.

- **An uncommitted edit crossed branches.** A version bump in progress rode a
  `git checkout` onto an unrelated ABI branch, was committed there, and was pushed; it was
  caught only by reading the PR diff afterwards. `tools/branch.sh` gives each branch its
  own worktree, which removes the mechanism instead of asking for care, and a `pre-commit`
  hook keeps the primary checkout on `main` so the habit cannot quietly lapse back into
  `git checkout -b`.

- **Nothing checked that a diff stayed in its lane.** A branch declares its scope in
  `.github/pr-scope/<branch>.txt` and `pre-push` refuses anything outside it. The
  self-test replays the real leak — `androidApp/build.gradle.kts` plus
  `gradle/libs.versions.toml` on a branch scoped to the former — and confirms it is
  rejected.

  `PROJECT.md`, `CHANGELOG.md` and the scope file itself are always allowed without being
  declared. They are touched by nearly every branch here, and requiring them in every
  scope file would turn the mechanism into boilerplate people stop reading. A guardrail
  that is annoying enough to be routinely bypassed protects nothing.

- **A guardrail with no planted violation is a guardrail nobody has seen work.** Same rule
  as the build guards. `tools/git-hooks-selftest.sh` covers sixteen cases across both hooks and `branch.sh`,
  including the ones that must *not* fire: docs-only changes, in-scope changes, and branch
  work inside a linked worktree.

## The review bot is gone (31 Aug 2026)

`claude-code-review.yml` is deleted, along with the `claude-code-action` row and the
"add it to `.github/workflows/`" instruction in STACK.md. The history above stays: it
records three separate ways an automated reviewer can report success without having
reviewed anything, and those lessons outlive the tool.

Why it went, in the order the reasons were found:

1. **It ran for weeks without reviewing.** The secret was unset, then set; passing is not
   reviewing.
2. **It passed a planted software encoder** — a violation of the one rule this project
   treats as non-negotiable — in eighteen turns, silently, because nothing published its
   findings.
3. **It self-disabled quietly** on any pull request touching its own workflow file, and
   reported that as a green check.
4. **`"result": "Credit balance is too low"`, `api_error_status: 400`.** Three runs across
   two pull requests and an hour, none of which reached the model. Found only after
   `show_full_output: true` was added, because until then the log carried the result
   envelope and no reason.

Each of those was fixed or diagnosed, and the last one is not a code problem at all. The
decision is to stop paying attention to a check that has never once produced a review, and
to review in-session instead: before any pull request merges, the changes are read against
BUILD.md, the three skills in `.claude/skills/`, and the branch's own declared scope in
`.github/pr-scope/`, and a verdict is given to the user in the conversation.

**The weakness of that arrangement, stated rather than glossed:** almost every pull request
here is written by the same agent that would now review it, and an author reviewing their
own work catches less than a stranger does. What is *not* weakened is everything mechanical
— the three build guards, the merged-manifest scan, the APK library check, the emulator
launch, ktlint and detekt all still run on every pull request and none of them care who
wrote the diff. The reviewer was only ever the layer that reads intent, and for its whole
life it read none.

Nothing in branch protection referenced `review`: it was deliberately never a required
check, which is why #9 and #10 could merge with it red, and why deleting it blocks nothing.

## The review bot did not review anything

`claude-code-review.yml` had never once completed before 2026-08-31: `ANTHROPIC_API_KEY`
was unset, so every run died at credential validation in about twenty seconds. Once the
secret was set it started passing on every PR.

Passing is not reviewing. A throwaway PR (#4, closed unmerged) planted a software-encoder
fallback inside `MediaCodecFactory`:

```kotlin
val hardware = available.filter(::isHardware)
return hardware.ifEmpty { available }   // BUILD.md § 2 rule 2, violated
```

It was planted in that file deliberately: the build guard polices *where* codecs are
created, not what is done with them, so a violation there compiles and the guard passes.
Only a reviewer reading intent can catch it.

The bot ran for 108 seconds over 18 turns and posted nothing — no review, no inline
comments, no issue comment — and the check went green. `permission_denials_count: 1` in
the result, and `show_full_output` is false, so whatever it concluded went to a hidden
log.

The likely cause: the workflow's prompt asks for a review but never tells the agent to
*post* anything, and in agent mode nothing publishes the final message on its own.

**Until that is fixed, a green `review` check means the job exited zero and nothing more.**
It is not evidence that a diff was reviewed. That is worse than having no reviewer, because
a green tick invites the trust that an absent one would not.

*Superseded on 2026-08-31 by #6.* Runs after that commit do post: the first one read this
branch and filed six findings, one of them a real bug. The caution above applies to runs
**before** #6; it is kept rather than deleted because the failure it describes is the kind
that returns quietly, and one posting run is not yet a track record.

### What the fixed reviewer caught, on its first real run

The review workflow's first run after #6 landed was against the guardrails branch itself,
and it found a bug that the branch's own eight self-tests did not:

`tools/branch.sh` wrote its scope file to `.github/pr-scope/$branch.txt` after creating
only `.github/pr-scope`. Every branch name in this repository contains a slash, so the
redirect targeted `.github/pr-scope/claude/<name>.txt` in a directory that did not exist.
Under `set -euo pipefail` the script aborted — *after* `git worktree add` had already
succeeded. The result was a created worktree, on a new branch, with no scope file, which
`pre-push` reads as "no restriction". It failed into no-guardrail, silently, from then on.

It was invisible to the self-tests because the fixture used the flat branch name `scoped`
while the repository's convention is `claude/<name>`. A test fixture that does not look
like production is a test of something else. The fixture is now slashed, and there is a
case that runs `branch.sh` end to end and asserts the scope file exists.

It also went unnoticed by the author because both scope files on this branch were created
by hand with `mkdir -p` before `branch.sh` was ever asked to do it — so the PR's claim
that "the tooling has been used to build itself" was half true, and the half that was
false was the half under test. Corrected in the PR body.

Three further findings from the same review, all confirmed and fixed:

- **Scope globs matched recursively.** `case "$file" in $pattern` lets `*` match `/`, so
  `shared/*` would have authorised `shared/core/pipeline/**` — the boundary this project
  guards hardest. Patterns now compile to a regex where `*` stays inside a segment and
  `**` crosses, which is what a reader brings gitignore intuitions to.
- **`pre-push` checked `HEAD`, not the push.** Git passes the refs being pushed on stdin;
  the hook ignored them, so `git push origin other-branch` was checked against whatever
  was checked out — and if that was `main`, with no scope file, the push went unexamined.
  It now loops over the refs on stdin, and falls back to HEAD only for manual dry runs.
- **The self-tests were not run by anything.** `checkall.sh` claimed to be every local
  check while never invoking `tools/git-hooks-selftest.sh` or
  `tools/check-apk-libraries-selftest.sh`. Both now run in it, which is the difference
  between eight passing cases and a screenshot of eight passing cases.

The lesson is the one this file keeps recording from a new angle: the guards were written
by the same person who wrote the thing they guard, and shared its blind spot. An
independent reader found in one pass what the author's own tests were built not to see.

### The second review, on the fixes themselves

The reviewer read the fix commit and found that two of the four fixes did not work, both
failing open, and both invisible to the self-tests.

- **The scope file was read from the wrong tree.** `pre-push` correctly derived the branch
  from the pushed ref, then looked its scope file up in the *current working tree*. Under
  the worktree-per-branch workflow this branch mandates, pushing `other-branch` while
  standing in another worktree finds no scope file, and a missing scope file means "no
  restriction". The exact fail-open the previous fix was written to close was still open,
  one step further in — and harder to see, because the branch name in the message was
  finally correct. The scope now comes out of the pushed commit (`git show
  $sha:.github/pr-scope/$branch.txt`), which also stops an uncommitted `rm` of the scope
  file from disabling the check for a commit that still contains it.

- **`timeout` is not on macOS.** The bounded stdin read used `timeout 2 cat`, with stderr
  discarded and `|| true` after it. On a Mac that is: command not found → silenced →
  swallowed → empty `refs` → fall back to HEAD. Every Mac would have run the pre-fix hook,
  silently, on a project with an iOS app and a macOS CI job. It is a `read -r -t 2`
  builtin loop now.

- **`${#violations[@]}` on an empty array under `set -u`** is an error before bash 4.4,
  which is what macOS ships — so the first fully in-scope push would have been *rejected*
  with no message. `${violations[*]:-}` is safe on 3.2. Likewise `\s` in grep is a GNU
  extension that BSD grep reads as a literal `s`, so an indented comment in a scope file
  would have survived into the pattern list and matched nothing, making every file a
  violation. `[[:space:]]` everywhere.

**Why the self-tests could not see any of it.** Every case pushed the branch that was
already checked out, so the HEAD fallback and the stdin path returned the same answer.
The test asserted the outcome without distinguishing the mechanism that produced it.
There is now a case that commits a branch, returns to `main`, and pushes the branch that
is *not* HEAD — which fails on the old code and passes on the new.

That is the second time on this branch that the tests were shaped by the same assumption
as the code. The first was a fixture using a flat branch name where the convention is
slashed. Both were found by a reader who had not written either.

### Third round: three GNU-only constructs in a repo that ships an iOS target

The reviewer's third pass found the pattern rather than just the instances. Each round I
had reached for whatever worked on this Linux container, and each time it was a GNU
extension that fails differently — and mostly silently — on the macOS half of this
project:

| Construct | On macOS |
|---|---|
| `timeout 2 cat` | absent; stderr discarded and `\|\| true` swallow it, so the hook falls back to HEAD |
| `grep '^\s*#'` | `\s` is literal `s`, so an indented comment survives into the pattern list |
| `sed 's/...\(bin\|all\)...'` | BRE alternation is GNU-only; the parse returns empty and *every* local check refuses to start |

The last one I introduced myself, in the commit that fixed the reviewer's previous
complaint about that same regex. Fixing a portability-adjacent bug by writing a
less portable expression is worth recording as its own failure mode.

Two more holes from the same pass, both in the shape this branch is supposed to be about:

- **Rename detection hid a boundary crossing.** `git diff --name-only` reports only the
  destination of a rename, so `git mv shared/core/pipeline/Foo.kt androidApp/Foo.kt` on a
  branch scoped to `androidApp/**` passed — while deleting a file in the directory
  ARCHITECTURE.md guards hardest. `--no-renames` shows both paths. Verified by
  construction before fixing, and there is a case for it now.
- **`checkall.sh` could never pass without an ELF toolchain.**
  `check-apk-libraries-selftest.sh` exits 2 for "I cannot run here", distinct from 1 for a
  real violation, and the harness collapsed both into failure. On a Mac the green state
  was unreachable — and an unreachable green is how a harness gets ignored, which is the
  disease this file replaced.

The self-tests went 8 → 11 → 12 → 14 across the three rounds, and every added case came
from a defect a reader found rather than one the author predicted.

### Round four: the fix that invalidated the tool

Moving `pre-push` to read the scope out of the pushed commit — itself a fix for a
fail-open — silently broke `branch.sh`, which wrote the scope file and never committed
it. A file on disk but not in the commit is invisible to `git show`, and a missing scope
file means *no restriction*. So the tool whose job is to create guarded branches was
creating unguarded ones, with a scope file sitting visibly in the worktree saying
otherwise.

That is the third time on this branch that a guardrail failed into no-guardrail while
looking correct, and the second time a fix created the next defect. `branch.sh` now
commits the scope file as the branch's first commit.

The self-test could not see it because it asserted `[ -f ... ]` — presence on disk, which
is exactly the property that stopped being sufficient. It now asserts
`git cat-file -e HEAD:<scope>`, the property the hook actually depends on. **A test that
asserts a proxy for the real property will keep passing after the real property is gone.**

Also from that round: the fixture inherited the developer's global git config, so anyone
with a global `core.hooksPath` would have had the fixture's own commits rejected by this
repo's `pre-commit` and the case would have measured a hook against a commit that never
happened. `GIT_CONFIG_GLOBAL=/dev/null` now isolates it.

And the branch's scope file no longer claims `.github/pr-scope/**`, which had granted it
write access to every other branch's scope file — a guardrail that can edit its siblings
is a poor example for the branches that copy it.

### Round five, and where this stops

Two findings worth acting on, both about the guardrail's own integrity rather than new
behaviour:

- **The last fail-open.** A failed `git merge-base origin/main` printed one stderr line
  into the middle of a push and let it through — eighteen lines above a deliberate
  fail-closed for an empty scope file, reaching the same state (a scope file exists, so
  the intent to be scoped is on record) and resolving it the opposite way. Reachable
  ordinarily: a shallow or `--single-branch` clone, a fork whose origin has no `main`, a
  fresh worktree where `origin/main` was never fetched. It rejects now, naming the fetch
  that fixes it.

- **The two behaviours changed by argument were asserted by nothing.** The empty-scope
  direction was reversed this round on the strength of a review comment. A direction that
  nothing tests is the one that flips back the next time someone finds it inconvenient at
  six in the evening. Both edges have cases now: 14 → 16.

Five rounds, and the shape of the findings changed each time: original defects, then
defects created by the fixes, then a portability class, then a fix that invalidated its
own tool, then asymmetries in how failure is handled. The reviewer's own summary is the
right note to stop on — *"none of the three is a reason to hold the branch if you would
rather land it and file them"* — and what remains after this commit is genuinely that:
`checkall.sh` could assemble an APK and run the real ABI check rather than only its
self-test, `pre-push` could use the remote name git passes it rather than assuming
`origin`, and nothing enforces scope outside a clone that ran `install-hooks.sh`. All
three are follow-ups, not blockers, and none of them fails open.

## The Android host — mounting the gallery (31 Aug 2026)

Found by installing the APK and looking at it. `MainActivity` drew the app's name centred
on an empty page and nothing else; every screen under `shared/feature` had been written,
unit tested and never mounted. `MainActivityLaunchTest` passed throughout, because
reaching RESUMED says the process came up, not that anything is on screen. Seven of the
eight `shared/feature` modules contain only a `build.gradle.kts` — the milestone work for
Space, the editor, settings, search, people, cleanup and compress is *logic*, in
`shared/core/{model,domain,pipeline}`, and the screens for it do not exist.

Decisions taken while wiring the one module that does have screens:

**The platform owns the grant list, not our database.** `GrantedFolders` reads
`ContentResolver.getPersistedUriPermissions()`. That is what actually decides whether a
scan succeeds: it survives reboot and a cleared database, and the user can revoke a grant
in system Settings without telling us. A row of ours that disagrees with it is a scan that
fails at the first cursor. The database will hold what hangs off a grant — its folder
mode, when it was last scanned — once a settings screen exists to change them.

**Every grant is `FolderMode.KEEP` until the user is asked.** OFFLOAD moves originals and
FREE expires them after N days. Both are destructive, both are choices BUILD.md § 6 gives
the user, and no screen exists yet to ask. Defaulting to the one mode that never removes
anything is the only defensible choice while the question cannot be put.

**Read *and* write persisted permission is taken at grant time.** The write half is unused
until `SafeReplacerAndroid` commits a replacement, but a SAF permission cannot be widened
later without asking again — and discovering that on the night of the first replace would
strand a verified encode with nowhere to put it.

**The grid falls back to `mtime` when `takenAt` is null.** `SafStorage.scan` reads one
cursor per folder and no file headers, by design: an EXIF read per file turns a second
into a minute on a large library, and `ContainerReaderAndroid` does it later for the few
files the diff found new or changed. So on a first run every item is undated and the whole
library would land in one "Undated" section. The file's modification time is an
approximation — a photo copied from another device carries the copy's date — but an
approximately ordered grid beats one undifferentiated block, and the real date replaces it
as soon as the item is indexed.

**Coil's singleton loader is configured with `coil-video` and nothing else.** Both
artifacts are named in STACK.md and were already dependencies. No `coil-network-*` is on
the classpath, so the loader can only resolve the local content URI it is handed — which
matters, because an image loader that could fetch is the obvious way for BUILD.md rule 8
to quietly stop being true.

What this host still is not: no database, no night pass, no navigation, no viewer beyond
what `GalleryScreen` itself contains. It grants a folder, walks it, and shows what is in
it.

## The viewer and crash reporting (31 Aug 2026)

**A crash handler is the first thing a field loop needs.** The first report from a real
phone was "tapping a photo closes the app" — true, and consistent with about forty lines.
Without a cable there is no logcat, so the app keeps its own: `CrashReports` writes traces
to app-private storage and the diagnostics export carries them off. Nothing is uploaded;
that is not a policy choice to revisit, it is BUILD.md rule 8 and two build guards.

**Video playback is a slot, not a dependency.** `shared/feature/gallery` gained a `video`
composable parameter and the Android host fills it with ExoPlayer, exactly as `artwork` is
filled with Coil. The shared module compiles for iOS in CI, and it would not if it named a
player. iOS binds `AVPlayer` to the same seam.

**Only the visible page holds a player.** `beyondViewportPageCount = 0` and a
`page == currentPage` check. A paused player still holds a decoder, and the reason the
night pass sets `KEY_PRIORITY = 1` — that the foreground should win the hardware — cuts
the same way inside the app.

**Paging is off during the open animation.** The hero frame is still travelling then, and a
horizontal drag would fight the transition for the same gesture. It turns on when progress
reaches 1.

**The tile the close animation returns to is the one on screen**, not the one first tapped.
`tileBounds` takes the item now rather than closing over the tapped one, which is what
makes swiping and then dismissing land in the right place.

Known and not fixed here: the grid hides the *tapped* tile for the whole time the viewer is
open, so after swiping, the tile behind the viewer is visible and the tapped one is still
hidden. It is invisible behind a full-screen viewer and costs a state hoist to fix; it is
recorded rather than quietly left.

## ktlint without Gradle (1 Sep 2026)

Five ktlint failures reached CI across two pull requests — import order, a body expression,
a parameter list — each costing a ten-minute round trip to be told about whitespace. The
cause was structural, not carelessness: every module with ktlint also has the Android
plugin, so `./gradlew ktlintCheck` cannot *configure* in an environment that cannot reach
Google Maven, which is the environment this project is mostly written in. The cheapest
check in the build was the one check that could only run in CI.

`tools/ktlint.sh` runs the ktlint CLI directly: a fat jar from Maven Central, which is
reachable, reading the same `.editorconfig`. Eight seconds over the whole tree.

**Pinned to the version the Gradle plugin runs**, which was extracted from the plugin jar
rather than guessed — 1.5.0 — and the plugin is now pinned to the same number with a
comment pointing back. That pin is the whole value: a local check that disagrees with CI is
worse than no local check, because it teaches you to stop believing it. Verified in both
directions before landing: silent on a clean tree, exit 1 on a planted violation.

The jar is verified against a pinned SHA-256 and cached outside the repository. A checksum
mismatch fails rather than runs: it is an executable about to be run over the source tree.

**Found on the way, not fixed here: `build-logic` is not linted at all.** The first run
reported 85 violations there, and the Gradle plugin agrees with none of them — it applies
ktlint in `subprojects { }`, and `build-logic` is an included build rather than a
subproject, so it has never been scanned. That is the code holding the three build guards
and their 72 tests: the code that enforces this project's hard rules is the code with no
style check on it. `tools/ktlint.sh` excludes it to match CI exactly, because a local check
that fails where CI passes is the same disease. Worth its own change; 85 formatting edits
do not belong in a tooling commit.

## The folders Android will not grant (1 Sep 2026)

From the phone: "can't use this folder", with no explanation. Partly ours, partly not.

**Android refuses three locations to every app** through `ACTION_OPEN_DOCUMENT_TREE`, since
Android 11: the root of internal storage, `Download`, and the root of a removable volume.
The system picker greys out "Use this folder" there. That is not this app's rule and cannot
be argued with.

**The app cannot tell a refusal from a cancellation.** Because the picker prevents
confirmation rather than returning an error, a blocked attempt and somebody changing their
mind arrive identically: `null`. Every design that says "that folder is not allowed" in
response to `null` is guessing, and will eventually tell someone who simply backed out that
they did something wrong.

So the screen does the two things it honestly can:

- **Prevention.** The picker opens at `DCIM/Camera` via `EXTRA_INITIAL_URI`, so the ordinary
  path never meets a blocked folder. A hint, not a guarantee — a device without that folder
  opens where it likes.
- **Explanation, stated neutrally.** On an empty result the sheet leads with "No folder was
  chosen", names all three blocked locations, and says the way round each: any folder
  *inside* them works, including inside Downloads. It offers to open DCIM/Camera, and a
  second button that opens the picker with no hint for someone whose photos are elsewhere.

A returned tree is still classified, because a picker that allows what the platform
documents as unpickable would otherwise leave a grant that is stored, looks granted, and
scans nothing — which reads as an empty library rather than a refused folder.

### Downloads, and the All-files-access route

Granting `Download` itself needs `MANAGE_EXTERNAL_STORAGE` — "All files access". It is
technically available and it is a real option, with real costs: Google Play requires a
declaration and a review for it, it is the single most invasive permission on Android, and
it contradicts the app's own pitch that it only ever sees folders the user hands it. This
app optimises photographs, which live in `DCIM`, and a folder inside Downloads works today
without any of that.

**Deferred, not refused**, and worth revisiting only if field testing shows people keep
photographs directly in Downloads. Recorded here so the question is not rediscovered from
scratch.

### And a gap this uncovered

`sharedTest` runs the shared modules and nothing else, so **androidApp's own unit tests
have never been run by CI**. A test written beside the Android code was decoration: it
looked like coverage and executed nowhere. The `android` job now runs
`:androidApp:testDebugUnitTest`, and `FolderChoice`'s rule is split so the interesting half
is a pure function over a document id — `Uri` returns null for everything in a plain JVM
test, so a classifier taking a `Uri` could only ever have been checked on a device.

## The night pass had no caller (1 Sep 2026)

The single most consequential thing found by putting the app on a phone. `NightScheduler`
was complete and correct since milestone 5, and nothing in the app ever called
`schedule()`. Every milestone that depended on the night pass running was therefore
untested end to end on a device, and the symptom — "nothing is ever optimised" — pointed at
the pipeline, which was fine.

Decisions:

**`NightPass` is the only caller, and it both schedules and cancels.** A component that
only schedules leaves a revoked grant behind a job that wakes the phone to read nothing.

**It runs on app start, not only on grant.** A periodic work request does not survive a
reinstall. Someone whose app quietly stopped optimising has no way to know that re-granting
a folder is the fix, so the app re-asserts the schedule itself. `enqueueUniquePeriodicWork`
with `KEEP` makes that free: an existing schedule keeps its period.

**The export answers "is it scheduled?" and refuses to answer "when do you sleep?"** State,
attempt count, granted-folder count and constraint list, and no times at all — the rule
`Diagnostics` already states for the metrics export, with a test asserting no ten-digit
number can appear in the section.

**`WorkManagerScheduler` is bound as both itself and the port.** The pipeline must depend
on `NightScheduler` alone; the diagnostics export needs to ask WorkManager a question that
has no meaning on iOS. Two definitions, one instance.
