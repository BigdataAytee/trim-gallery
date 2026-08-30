# Trim Gallery

Trim Gallery is a beautiful on-device photo and video gallery that quietly shrinks
your library while your phone charges, so you keep everything, use half the space,
and nothing ever leaves your phone.

## The documents that govern this repository

| File | What it is |
|---|---|
| [BUILD.md](BUILD.md) | The spec. Non-negotiable rules, architecture, pipeline, build order. |
| [PROJECT.md](PROJECT.md) | Decisions already made, and why. Do not re-litigate these. |
| [STACK.md](STACK.md) | The only approved library list. Adding anything else needs a decision recorded in PROJECT.md. |
| [ARCHITECTURE.md](ARCHITECTURE.md) | The module layout, engine interfaces and platform adapter matrix. Build to this. |
| [CHANGELOG.md](CHANGELOG.md) | What has actually been built so far, and what is still missing. |

Claude Code skills live in [`.claude/skills/`](.claude/skills). Three are specific to
this project and encode its hard rules: `codec-priority`, `safe-replace`, `ndk-build`.

## Status

Milestone 1 of [BUILD.md section 13](BUILD.md): one video encoded to HEVC with Media3
Transformer, audio passed through, at background codec priority. See CHANGELOG.md for
what is done, what was verified and what is knowingly missing.

## Layout

Kotlin Multiplatform, per [ARCHITECTURE.md section 3](ARCHITECTURE.md). Roughly 75%
shared, and the 25% that is not is exactly the platform adapter matrix in section 6.

```
shared/
  engine-api/            Every interface from ARCHITECTURE.md section 5. No platform types.
  core/model/            The section 4 data model
  core/pipeline/         Triager and the optimise/index steps -- pure Kotlin, tested with fakes
  core/{domain,data,ui}/ Use cases, SQLDelight + DataStore, Compose Multiplatform design system
  feature/*/             gallery, search, people, space, cleanup, editor, compress, settings
  native/                CMake + trim_native.h C ABI; submodules declared, not yet built
  testdata/              Golden clips per source codec
androidApp/              MediaCodecFactory, TransformerEncoder, Koin wiring, host Activity
iosApp/                  Present, not implemented (v1.5). See iosApp/README.md
benchmark/               Macrobenchmark
build-logic/             The three build guards, with 30 tests
design/buyer-gallery/    Parked motion prototype -- does not ship
```

## Three rules the build enforces for you

ARCHITECTURE.md section 14. All three live in `build-logic`, depend on nothing but the
Gradle API, and run without an Android SDK — so CI reports a violation in seconds.

1. **No network access.** BUILD.md rule 8 says the app states "no network" in the UI as
   a feature, so it has to be literally true. Two halves: nothing may be *declared*
   (every manifest, the AGP-**merged** manifest of each variant, and the iOS
   `Info.plist`/entitlements), and nothing may be *written* (any networking API in
   source, with an empty allow-list).
2. **Codecs only in `CodecFactory`.** BUILD.md rule 2 bans software video encoding, and
   that rule is only as strong as its weakest call site.
3. **Library writes only in `Replacer`.** Originals are read-only until one atomic
   replace.

A guard that finds nothing to scan fails, rather than passing quietly.

**arm64 only**, on both platforms: `ndk.abiFilters` plus an ABI split with
`isUniversalApk = false`. The native metric libraries are built for one ABI.

## Building

```
./gradlew guards          # the three build guards, no SDK needed
./gradlew sharedTest      # shared JVM unit tests, no SDK needed
./gradlew :androidApp:assembleDebug
```

The first two are what CI gates on and need only a JDK. The third needs the Android SDK
(compileSdk 36) and JDK 17.

> **Before the first build**, run `tools/verify-versions.sh`. The version catalog was
> written in an environment that could not reach Google Maven, so every entry marked
> `[google]` in `gradle/libs.versions.toml` still needs confirming. Entries marked
> `[central]` were resolved for real.

To run the guards' own tests, which need neither the Android SDK nor Google Maven:

```
./gradlew -p build-logic test
```

The native layer is not built yet (milestone 2). Its submodules are declared in
`.gitmodules` but deliberately not initialised.
