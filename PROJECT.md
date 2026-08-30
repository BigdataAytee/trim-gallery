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
- Which small face-embedding model to use for people clustering.

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

## Open questions added
- **The Compose layer has never been compiled.** Every Compose Multiplatform version
  resolves `androidx.annotation`, `androidx.collection` and `androidx.lifecycle` from
  Google Maven, which this environment refuses, so there is no path to building it here —
  desktop target included. The Compose-free half of the design system is verified; the
  composables are not. Expect to fix API details on the first real build.
- Whether a Compose Multiplatform host on iOS is the right call for the viewer, or
  whether the shared-element motion in BUILD.md § 9 wants SwiftUI there. Revisit at
  milestone 8.
