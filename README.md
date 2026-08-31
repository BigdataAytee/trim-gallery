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

Milestones 1 through 14 of [BUILD.md section 13](BUILD.md) are built and milestone 15, the
iOS port, is begun: the Media3 Transformer encode, XPSNR and libvmaf over the NDK, the
probe/search/predictor loop, the verify-and-safe-replace path with undo and offload, the
night scheduler with its guards, the triage rules and skip list, the photo pipeline (jpegli,
SSIMULACRA 2, JPEG XL, oxipng), the gallery shell, the on-device index — hashes, duplicates,
people, search and chat-media review — the Space screen, history with restore, Compress now,
the play-to-compress tap and the settings store, the editor — crop, orientation, straighten,
adjustments, filters and video trim, with the save policy that decides when an edit needs no
encoder at all — the AV1 path: per-encoder capabilities, the codec choice, and a search
bracket that knows AV1 reaches the same quality at two thirds of HEVC's bitrate — the v1.1
pair of Memories and the map, clustered into home and trips over an offline basemap the user
supplies as an MBTiles pack — there is no geocoder and never will be, so nothing here names
a place.

### What is compiled, and what proves it

Six required checks in [the `Build` workflow](.github/workflows/build.yml), on every pull
request and on every push to `main`:

| Check | What it proves |
|---|---|
| `Build guards` | The three rules below hold across every source file and manifest in the tree, and the guards' own tests pass |
| `Shared JVM tests` | The shared unit tests run and pass on a bare JDK — no Android SDK, no Google Maven |
| `iOS cross-compile` | Every shared module compiles for `iosArm64` and `iosSimulatorArm64`, and every Swift file parses, on a macOS runner |
| `Android build + lint` | detekt and ktlint over the real source sets, then `:androidApp:compileDebugKotlin` |
| `Android APK + native` | `assembleDebug` — Compose, Koin, Media3 and the native tree built from source into an arm64 APK — then the APK library check |
| `App launches on a device` | The app installs and runs on a Gradle-managed Pixel 6, API 34 |

908 `@Test` functions in the shared modules, 72 in `build-logic`, 4 instrumented tests in
`androidApp`. Those are counts of test functions in the source tree, not of a runtime tally;
what CI reports is that the suites containing them pass.

The APK check reads the built artefact rather than the build files: it pulls `DT_NEEDED` out
of every packaged `.so` and fails if a library something loads is not in the APK. A missing
library is otherwise a crash on the first night pass, not a build failure. It has its own
self-test that plants a violation, because a check that cannot fail is not a check.

The device check is the only one that proves the app *runs* rather than that it compiles: the
emulator boots, the smoke variant installs, and `MainActivity` reaches `RESUMED` and survives
recreation. Nothing beyond launch is exercised there.

The Swift is parsed, not compiled. `swiftc -parse` catches a file whose second half sits
inside an unclosed comment — a fault this repository has actually had, twice, in Kotlin,
where the equivalent parser found it — but it does not resolve `import Photos`, so it cannot
catch a wrong PhotoKit call. There is no Xcode project and no XCFramework: `iosApp/` is four
adapters written to documented behaviour, not an application, and most of the adapter matrix
is still unwritten.

Google Maven resolves on GitHub's runners and does **not** resolve in the sandbox this
repository is mostly written in, where `dl.google.com` is refused. That asymmetry is why
every decision the app makes lives in platform-free Kotlin that can be tested without an SDK,
and why the entries marked `[google]` in `gradle/libs.versions.toml` were best-known-good
guesses until CI resolved them for real. compileSdk and targetSdk are 37, minSdk 29.

All six native functions are verified against their upstream binaries, and the native tree —
xpsnr, libvmaf, libjxl, jpegli and oxipng — now cross-compiles for arm64 in CI from the
submodules rather than being taken on trust.

Milestone 13's instrumentation is written — the field metrics, the LAUNCH.md alpha gate, the
redacted diagnostics export and the threshold fitting — but **the field test itself has not
been run**: it needs three device classes and a fortnight. **Nothing here has been
field-tested.** A green pipeline says the app builds, holds its rules and launches; it says
nothing about what a night pass does to a real library on a real phone. FIELD_TEST.md is the
procedure, and no number in this repository is presented as a field-test result. See
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
  native/                CMake + trim_native.h C ABI; built from the submodules by assembleDebug
  testdata/              Golden clips per source codec
androidApp/              MediaCodecFactory, TransformerEncoder, Koin wiring, host Activity
iosApp/                  Present, not implemented (v1.5). See iosApp/README.md
benchmark/               Macrobenchmark
build-logic/             The three build guards, with 72 tests
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
  path segment and `**` crosses slashes. Patterns are **anchored at the repository root**,
  which is where they differ from gitignore: write `**/Foo.kt`, not `Foo.kt`, and
  `tools/**`, not `tools`. A pattern that reaches no file matches nothing, so the whole
  diff is rejected against a scope that reads correctly.

  `PROJECT.md`, `CHANGELOG.md` and the scope file itself are always allowed. No scope file
  means no restriction — but a scope file with every pattern commented out is rejected,
  not treated as open, and so is a push with no merge-base against `origin/main` (run
  `git fetch origin main`). The hook reads the refs git gives it on stdin, so it checks the
  branch being pushed rather than whatever happens to be checked out, and it only fires in
  clones that ran `install-hooks.sh` — it is not a substitute for review.

`tools/git-hooks-selftest.sh` tests all of it, including a replay of the real leak.

## Get the APK

Every run of the `Android APK + native` job uploads the debug APK as an artifact called
**`apk`**, including the run for each merge to `main`. Open
[Actions → Build](https://github.com/BigdataAytee/trim-gallery/actions/workflows/build.yml),
pick the most recent green run on `main`, and take `apk` from the Artifacts box at the foot
of the run summary. It is debug-signed, arm64-only, and carries the native metric libraries.
GitHub expires artifacts after the repository's retention period, 90 days by default.

It builds, holds its rules and launches. It has not been field-tested — see Status.

## Building

```
tools/checkall.sh                 # everything this repository can check locally
```

Or one at a time:

```
./gradlew guards                  # the three build guards
./gradlew sharedTest              # the shared JVM unit tests
./gradlew -p build-logic test     # the guards' own tests
./gradlew :androidApp:assembleDebug
```

The first three need only a JDK and Maven Central, which is why they are the checks that run
anywhere. `assembleDebug` needs the Android SDK at compileSdk 37 and Google Maven; CI runs it
on temurin 21, and the modules build against the JVM 17 toolchain Gradle provisions for them.

`assembleDebug` also builds `shared/native` from source, so it needs the submodules and the
build systems CMake drives on top of its own:

```
git submodule update --init --recursive
python3 -m pip install meson ninja          # libvmaf builds with meson
rustup target add aarch64-linux-android     # oxipng is Rust
cargo install cargo-ndk --locked
```

`find_program(... REQUIRED)` fails configuration when any of them is missing. A cold
cross-compile of libjxl, jpegli, brotli, lcms, Highway and libvmaf is around seven minutes.

> `tools/verify-versions.sh` confirms every coordinate in `gradle/libs.versions.toml`
> resolves, and reports newer stable versions. The entries marked `[google]` were written
> where Google Maven was unreachable; the ones the Android jobs pull are resolved for real on
> every CI run now, but anything no job compiles against — the `benchmark` module's, for one
> — is still a guess.
