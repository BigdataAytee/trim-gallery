# Changelog

All notable changes to Trim Gallery. Newest first.

## [Unreleased]

### Changed — restructured to Kotlin Multiplatform for ARCHITECTURE.md

ARCHITECTURE.md arrived after the first Android-only skeleton and supersedes it. The
single `app/` module has been replaced by the § 3 layout: `shared/` (KMP) + `androidApp`
+ an `iosApp` target that is present but not implemented.

**Skills** — the three project skills were rewritten against BUILD.md § 2 and
ARCHITECTURE.md § 7, and are now cross-platform:
- `codec-priority` — the `CodecFactory` single door, Android `KEY_PRIORITY = 1` and iOS
  VideoToolbox side by side, capability pre-checks, and the 5/15/60 s reclaim ladder
  from § 13.
- `safe-replace` — the § 7 `Replacer` contract in order, reverse rollback, and the
  platform mechanics table (SAF rename vs. PhotoKit add-then-delete, including album
  membership).
- `ndk-build` — one CMake build for android-arm64 and ios-arm64 behind `trim_native.h`,
  JNI on one side and cinterop on the other.

**Module layout (ARCHITECTURE.md § 3)**
- `shared/engine-api` — every interface from § 5, verbatim, with no platform types.
- `shared/core/model` — the § 4 data model.
- `shared/core/pipeline` — `Triager` implementing the BUILD.md § 5 triage rules.
- `shared/core/{domain,data,ui}` and `shared/feature/{gallery,search,people,space,cleanup,editor,compress,settings}`.
- `shared/native` — CMake project, `trim_native.h` C ABI, and the five STACK.md
  submodules declared in `.gitmodules` but **not initialised**; milestone 2 turns them on.
- `androidApp` — `MediaCodecFactory`, `TransformerEncoder`, Koin wiring, host Activity.
- `iosApp` — directory shape, `Info.plist` and entitlements with no network keys, and a
  README mapping each planned file to the interface it will implement.
- `benchmark` — Macrobenchmark module (cold start now, grid scroll at milestone 8).

**Three build guards (ARCHITECTURE.md § 14)** — all in the `build-logic` included build,
with no AGP on the classpath, so they run in CI in seconds without an Android SDK:
1. **No network permission** — scans every `AndroidManifest.xml` *and* the AGP-merged
   manifest of every variant, so a permission arriving through a dependency is caught.
2. **Codecs only in `CodecFactory`** — `MediaCodec.create*`, `MediaCodecList` and
   `VTCompressionSessionCreate` outside `MediaCodecFactory` / `VideoToolboxFactory` fail
   the build.
3. **Library writes only in `Replacer`** — `DocumentsContract` mutations,
   `openOutputStream`, `DocumentFile` writes, `PHAssetChangeRequest` and `deleteAssets`
   outside the two `SafeReplacer`s and the two `UndoBin`s fail the build.

   Plus the source half of the network guard (any networking API, empty allow-list) and
   the iOS plist half (`NSAppTransportSecurity`, network entitlements). Comments and
   string literals are stripped before matching, so a rule named in a doc comment is not
   itself a violation. A guard that finds nothing to scan **fails**, because a guard that
   passes on an empty file set reads as green.

**Tooling** — Detekt and ktlint on every module; `.github/workflows/build.yml` with three
jobs (guards, shared JVM tests, Android build + static analysis); `./gradlew sharedTest`
and `./gradlew guards` as the two entry points CI gates on.

**Milestone 1 (BUILD.md § 13.1)** — rebuilt against the shared interfaces:
- `MediaCodecFactory : CodecFactory` — the only place a codec is created. Hardware-only
  filter (`isHardwareAccelerated`, `!isSoftwareOnly`, plus a name check because those
  flags have lied on some devices).
- `TransformerEncoder : HwEncoder` — Media3 Transformer, HEVC, **audio transmuxed**,
  2-second GOP, **background codec priority** via
  `VideoEncoderSettings.setEncoderPerformanceParameters(…, priority = 1)`, and
  `setEnableFallback(false)` so a device without a hardware HEVC encoder fails the export
  instead of quietly encoding in software.
- `shared/testdata/golden-h264-640x360-3s.mp4` — H.264 + **real AAC track**, 3 s, so
  audio passthrough is tested rather than assumed.
- `Milestone1EncodeTest` — encodes the golden clip, then asserts the output is HEVC, that
  the audio track survived with its original MIME type, that the duration matches the
  source, and that ExoPlayer **plays it through to `STATE_ENDED`**. On a device with no
  hardware HEVC encoder the test skips via `assumeTrue`, because skipping the file is the
  correct behaviour there.

### Verified

Everything that does not need the Android SDK or Google Maven was actually run:

- **39 build-guard tests pass** (`./gradlew -p build-logic test`) — unit tests for all
  four scanners plus Gradle TestKit tests proving a violating build genuinely fails, for
  each of the three guards.
- **12 `Triager` tests pass**, compiled with the real Kotlin compiler against the shared
  model, covering every BUILD.md § 5 threshold and every § 2.5 skip flag.
- **All three guards run clean over the real tree** — 16 source files, 2 plists, 1
  manifest.
- `trim_native.h` and the placeholder C compile under `-Wall -Wextra -Werror`.

### Fixed — dependency catalog verified

Ran `tools/verify-versions.sh`: **60 pinned coordinates, 35 resolved, 0 missing, 0 behind
latest stable**; the 25 unchecked are all on Google Maven, still refused by this
environment's egress policy (a confirmed gateway denial, not a tooling fault).

- Removed **KSP** — dead since Hilt and Room were replaced by Koin and SQLDelight.
- **Kotlin 2.3.21 → 2.4.10.** KSP was the only reason for the old pin. Shared sources
  recompiled and the `Triager` tests re-run under 2.4.10.
- Removed the superseded `room`, `navigationCompose` and `espresso` pins.
- Gave the eight STACK.md libraries that had a version but no coordinate a full
  coordinate, so they can actually be verified.
- Rewrote `verify-versions.sh` to parse `libs.versions.toml` rather than carry its own
  list, which had already drifted. It now checks the **exact pinned coordinate** (not
  just the group), and separates *missing* (catalog bug, exit 1) from *unchecked*
  (repository unreachable, exit 0).

### Known gaps

- **Nothing Android has been compiled.** Google Maven (`dl.google.com`) is blocked by
  this environment's egress policy, so there is no Android SDK and no AGP. The 25
  `[google]` versions in `gradle/libs.versions.toml` remain best-known-good guesses —
  AGP, androidx, Compose BOM, Media3, ML Kit, LiteRT. Run `tools/verify-versions.sh`
  from a machine with access before the first build; it exits non-zero only on a real
  catalog error.
- **`Milestone1EncodeTest` has not been executed.** It needs a device or emulator with a
  hardware HEVC encoder. It is written to run, not yet observed running.
- `shared/core/{domain,data,ui}` and the eight `feature/*` modules are build files and
  structure only — the pipeline beyond `Triager`, the repositories and the screens arrive
  with their milestones.
- Native submodules are declared but not initialised, per ARCHITECTURE.md § 15
  (milestone 2).

---

### Added — buyer gallery screen (design reference, not shipped)

`design/buyer-gallery/` is now complete: the "Photos and clips from buyers" screen from
`buyer-gallery-spec/`, built with React + Vite, Tailwind v4, Motion and Lucide.

It still does not ship — ARCHITECTURE.md §11 requires Compose Multiplatform for every
screen — but it is now a finished, running reference for the gallery shell's motion at
milestone 8 rather than a scaffold.

Every animation in DESIGN_SPEC.md §4 is implemented: breathing tiles out of phase, clips
that play muted only while ≥50% visible, staggered arrival, the hero zoom with its review
sheet, press feedback, and a full `prefers-reduced-motion` fallback.

**Verified in a real browser — 45 assertions, all passing**, covering all seven items of
the §7 acceptance checklist. The suite ships as `tests/acceptance.mjs` and exits non-zero
on failure. Three real bugs it caught along the way:

- An unlayered `* { margin: 0; padding: 0 }` reset outranked **every** Tailwind v4
  utility (unlayered CSS beats `@layer utilities`), silently killing `px-*`, `mx-*` and
  `mb-*` — the shell was not even centred.
- Motion drops inline `style` keys it does not manage once a layout animation starts, so
  the source tile's `visibility: hidden` was discarded and the tile stayed drawn behind
  the veil. Now hidden via an attribute selector CSS owns.
- The play badge showed "playing" whenever playback was *requested*, not when it actually
  started — a visible lie whenever `play()` was refused. It now follows the element's own
  play/pause events.

Clips are offered as WebM as well as H.264 MP4: many Chromium builds ship no H.264
decoder and would otherwise show nothing but the poster.

## Earlier — first Android-only skeleton (superseded)

The initial single-module Android skeleton, its `no-internet` lint check, and the
milestone 1 encode written against it. Replaced by the KMP layout above; the guard
survived and grew into the three-guard set.

`design/buyer-gallery/` holds a parked React + Vite prototype of a "Photos and clips from
buyers" screen, built from `buyer-gallery-spec/`. It does not ship — ARCHITECTURE.md § 11
requires Compose Multiplatform for every screen — and serves as an executable motion spec
for the gallery shell at milestone 8. See `design/buyer-gallery/README.md`.
