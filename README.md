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
| [SCHEMA.md](SCHEMA.md) | The database schema of record. ARCHITECTURE.md section 4 is the sketch this fills in. |
| [DESIGN_SYSTEM.md](DESIGN_SYSTEM.md) | Colour, type, shape, motion and copy tone. The tokens are asserted in JVM tests. |
| [PRD.md](PRD.md) | The product: problem, users, success metrics, non-goals. |
| [USER_JOURNEY.md](USER_JOURNEY.md) | Every screen, state and flow, first run to uninstall. |
| [MONETIZATION.md](MONETIZATION.md) | Freemium tiers and the rules the paywall may never break. |
| [LAUNCH.md](LAUNCH.md) | Release phases, store listing, staged rollout. |
| [CHANGELOG.md](CHANGELOG.md) | What has actually been built so far, and what is still missing. |
| [FIELD_TEST.md](FIELD_TEST.md) | The milestone 13 procedure: devices, library, nights, and how the alpha gate is read. |

Claude Code skills live in [`.claude/skills/`](.claude/skills). Three are specific to
this project and encode its hard rules: `codec-priority`, `safe-replace`, `ndk-build`.

## Status

Milestones 1 through 14 of [BUILD.md section 13](BUILD.md) are built: the Media3 Transformer
encode, XPSNR and libvmaf over the NDK, the probe/search/predictor loop, the
verify-and-safe-replace path with undo and offload, the night scheduler with its guards,
the triage rules and skip list, the photo pipeline (jpegli, SSIMULACRA 2, JPEG XL, oxipng),
the gallery shell, the on-device index — hashes, duplicates, people, search and chat-media
review — the Space screen, history with restore, Compress now, the play-to-compress tap and
the settings store, the editor — crop, orientation, straighten, adjustments, filters and
video trim, with the save policy that decides when an edit needs no encoder at all — and the
AV1 path: per-encoder capabilities, the codec choice, and a search bracket that knows AV1
reaches the same quality at two thirds of HEVC's bitrate.

Milestone 13's instrumentation is written — the field metrics, the LAUNCH.md alpha gate, the
redacted diagnostics export and the threshold fitting — but **the field test itself has not
been run**: it needs three device classes and a fortnight. FIELD_TEST.md is the procedure,
and no number in this repository is presented as a field-test result.

Milestone 14 adds the v1.1 pair: Memories, with the exclusions that make the feature safe to
ship, and the map — clustering, home and trips, over an offline basemap the user supplies as
an MBTiles pack. There is no geocoder and never will be, so nothing here names a place.

Milestone 15 begins the iOS port. The shared layer's portability is now enforced by a build
guard rather than assumed, the decisions iOS forces — four thermal states instead of a
continuous reading, no alarm API at all — are shared with the Android ones, and four Swift
adapters are written where the contract is subtle. **None of the Swift has been compiled**:
there is no Mac in the build environment, and most of the adapter matrix is still unwritten.

All six native functions are verified against their upstream binaries. 869 shared JVM tests
and 59 build-guard tests pass, over Kotlin and Swift. Nothing Android, iOS or Compose has been
compiled — Google Maven is unreachable and there is no Mac — which is why every decision the
app makes lives in platform-free Kotlin that can be tested without either. See
CHANGELOG.md for what is done and what was verified, and PROJECT.md for what is knowingly
untested.

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
build-logic/             The three build guards, with 39 tests
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

**arm64 only** in everything that ships, on both platforms. The app module declares
`ndk.abiFilters`; the shared library modules declare nothing, because AGP 9's KMP library
plugin has no `ndk` block and they contain no native code. The native metric libraries are
built for one ABI, and `tools/check-apk-libraries.sh` checks the built APK rather than
trusting the declaration.

## Working on this repo

```
tools/install-hooks.sh                      # once per clone
tools/branch.sh my-branch 'androidApp/**'   # a branch in its own worktree, with a scope
tools/checkall.sh                           # every local check, through ./gradlew only
```

Three guardrails, each of which exists because the thing it prevents already happened:

- **`tools/checkall.sh` runs `./gradlew` and nothing else**, and refuses to start unless
  the wrapper agrees with `gradle-wrapper.properties`. An earlier harness called the
  system `gradle`, a different version from the pinned one, so its "all checks passed"
  was testing a Gradle the project does not use.
- **`tools/branch.sh` puts each branch in its own worktree.** A `git checkout` carries
  uncommitted edits with it; that is how an in-progress version bump ended up committed
  on an unrelated branch. Separate worktrees remove the mechanism instead of relying on
  care. A `pre-commit` hook keeps the primary checkout on `main` so the habit cannot lapse.
- **`pre-push` refuses a diff that reaches outside the branch's declared scope**, read
  from `.github/pr-scope/<branch>.txt` — one glob per line, where `*` matches within a
  path segment and `**` crosses slashes, as in gitignore. `PROJECT.md`, `CHANGELOG.md`
  and the scope file itself are always allowed. No scope file means no restriction. The
  hook reads the refs git gives it on stdin, so it checks the branch being pushed rather
  than whatever happens to be checked out.

`tools/git-hooks-selftest.sh` tests all of it, including a replay of the real leak.

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
