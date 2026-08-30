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
| [CHANGELOG.md](CHANGELOG.md) | What has actually been built so far, and what is still missing. |

Claude Code skills live in [`.claude/skills/`](.claude/skills). Three are specific to
this project and encode its hard rules: `codec-priority`, `safe-replace`, `ndk-build`.

## Status

Milestone 1 of [BUILD.md section 13](BUILD.md): one video encoded with Media3
Transformer, audio passed through, played back. See CHANGELOG.md for what is done and
what is knowingly missing.

## Layout

```
app/                     Android app (Kotlin, Compose, minSdk 29, arm64-v8a only)
  optimiser/             TransformerEncoder, HardwareOnlyEncoderSelector
  ui/milestone1/         Pick a video, encode it, play the result
build-logic/             Included build: the no-network-permission guard, with tests
config/detekt/           Detekt rules, including the ban on creating codecs by name
tools/verify-versions.sh Resolves the latest stable version of every dependency
.claude/skills/          Claude Code skills
```

## Two rules the build enforces for you

**No network permission.** BUILD.md rule 8 says the app has no `INTERNET` permission
and states this in the UI as a feature — so it has to be literally true.
`verifyNoInternetPermission` fails the build if a forbidden network permission
reaches the manifest, checking the AGP-**merged** manifest of every variant as well
as our own sources, so a permission arriving through a dependency is caught too. It
runs as part of both `assemble*` and `check`.

**arm64-v8a only.** Set in `ndk.abiFilters` and again in the ABI split with
`isUniversalApk = false`. The native metric libraries are built for one ABI; a second
one would ship code that cannot run them.

## Building

```
./gradlew :app:assembleDebug
```

Requires the Android SDK (compileSdk 36) and JDK 17.

> **Before the first build**, run `tools/verify-versions.sh`. The version catalog was
> written in an environment that could not reach Google Maven, so every entry marked
> `[google]` in `gradle/libs.versions.toml` still needs confirming. Entries marked
> `[central]` were resolved for real.

To run the guard's own tests, which need neither the Android SDK nor Google Maven:

```
./gradlew -p build-logic test
```
