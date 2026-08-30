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

- Exact XPSNR threshold that maps to VMAF 95 on hardware HEVC — calibrate in milestone 2.
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

## Open questions added

- Dependency versions for everything on Google Maven (AGP, androidx, Compose, Media3,
  ML Kit, LiteRT) are **unverified**: the environment this was written in could not reach
  `dl.google.com`, so nothing has been compiled. They are marked `[google]` in
  `gradle/libs.versions.toml`; `tools/verify-versions.sh` resolves them all in one run.
  Entries marked `[central]` were resolved for real. No STACK.md library was substituted
  — only version numbers are in doubt.
- Whether a Compose Multiplatform host on iOS is the right call for the viewer, or
  whether the shared-element motion in BUILD.md § 9 wants SwiftUI there. Revisit at
  milestone 8.
