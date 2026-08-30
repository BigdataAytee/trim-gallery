# Changelog

All notable changes to Trim Gallery. Newest first.

## [Unreleased]

### Added — project setup and milestone 1

**Claude Code skills** (STACK.md, "Anthropic open-source")
- Ran the STACK.md setup step verbatim: cloned `anthropics/skills` and copied
  `frontend-design`, `skill-creator` and `webapp-testing` into `.claude/skills/`,
  plus `template/SKILL.md` as `.claude/skills/TEMPLATE.md`.
- Wrote the three project skills STACK.md calls for:
  - `ndk-build` — arm64-only CMake layout, NEON flags, meson-inside-CMake for
    libvmaf, cargo-ndk for oxipng, and the JNI bridge rules (direct buffers,
    `AutoCloseable` handles, cancellable native calls, `RegisterNatives`).
  - `codec-priority` — hardware-only encoder selection, `KEY_PRIORITY = 1` on every
    codec, performance points, `isBitrateModeSupported` before CQ, codec reclaim as
    an expected event, and the ban on software video encoding.
  - `safe-replace` — the ordered replace procedure, size+mtime change detection,
    verify-before-replace, folder modes, SAF specifics, and the list of calls that
    must never appear near an original.
- Added `.github/workflows/claude-code-review.yml` so every PR is reviewed against
  BUILD.md, PROJECT.md, STACK.md and the three skills.

**Android skeleton**
- Kotlin + Jetpack Compose (Material 3), `minSdk 29`, `targetSdk`/`compileSdk` 36.
- **arm64-v8a only**, enforced in two places: `ndk.abiFilters` and an ABI split with
  `isUniversalApk = false`.
- **No `INTERNET` permission, enforced by the build.** `verifyNoInternetPermission`
  fails the build if a forbidden network permission reaches the manifest. It runs per
  variant against the **AGP-merged** manifest as well as our own sources, so a
  permission contributed by a dependency is caught too. Wired into both `assemble*`
  and `check`. Covers `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` and
  `CHANGE_NETWORK_STATE`.
- The scanning logic lives in the `build-logic` included build with no dependency on
  AGP, and has 13 passing tests — including a Gradle TestKit test that asserts a
  build with `INTERNET` in the manifest actually fails.
- Gradle version catalog (`gradle/libs.versions.toml`) restricted to STACK.md
  entries; Detekt and ktlint applied to all modules. Detekt additionally forbids
  `MediaCodec.createEncoderByType` / `createByCodecName`, the back door through which
  a software encoder could be created.
- Gradle wrapper 8.14.3.

**Milestone 1 — Media3 Transformer encode (BUILD.md section 13, step 1)**
- `TransformerEncoder`: decode → encode → mux one video to HEVC via Media3
  Transformer, with **audio transmuxed** (`Composition.setTransmuxAudio(true)`), a
  2-second GOP, and progress exposed as a `StateFlow`. Cancelling the calling
  coroutine cancels the export and deletes the partial output.
- `HardwareOnlyEncoderSelector`: filters Media3's encoder list to hardware encoders
  only (`isHardwareAccelerated`, `!isSoftwareOnly`, and a name check against
  `OMX.google.` / `c2.android.` because those flags have lied on some devices).
  Combined with `setEnableFallback(false)`, a device with no hardware HEVC encoder
  fails the export instead of quietly encoding in software — BUILD.md rule 2.
- `Milestone1Screen` + `Milestone1ViewModel`: pick a video via
  `ACTION_OPEN_DOCUMENT`, encode it, show source/output sizes and the size factor,
  then play the result back with ExoPlayer.
- The source file is only ever read. Output goes to app-private cache, never beside
  the original: verification and safe replace are milestone 4.

### Known gaps

- **Dependency versions on Google Maven are unverified.** The authoring environment's
  egress policy blocked `dl.google.com`, so every catalog entry marked `[google]`
  (AGP, all of androidx/Compose/Media3, ML Kit, LiteRT, Accompanist) is a
  best-known-good guess. Entries marked `[central]` were resolved for real against
  `repo1.maven.org`. Run `tools/verify-versions.sh` from a machine that can reach
  Google Maven and reconcile before the first build.
- **The project has not been compiled.** Same cause: no Android SDK and no AGP could
  be downloaded. Everything that does not need them was run — see the 13 passing
  `build-logic` tests. The Kotlin and Gradle sources have not been through a
  compiler.
- **`KEY_PRIORITY = 1` is not yet set on the milestone-1 codecs.** Media3's
  `VideoEncoderSettings` exposes no way to add arbitrary `MediaFormat` keys, so
  background priority needs a custom `Codec.EncoderFactory`. Milestone 1 is an
  explicit, user-initiated foreground encode, where realtime priority is in fact the
  right behaviour; the custom factory lands with the night worker in milestone 5,
  which is the first code that has any business running at background priority. The
  rule and its rationale are recorded in the `codec-priority` skill.
