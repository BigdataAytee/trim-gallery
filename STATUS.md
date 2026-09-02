# What works, what does not

Every item you have reported, with the pull request that addressed it and an honest status.

> **The pivot, 2 Sep 2026.** Trim is no longer a gallery — it is a background file-trimming
> utility with five screens (PROJECT.md, "The pivot"). Items 1–4, 6, 7, 19, 20, 21 and 22
> below were gallery defects and gallery features; the code they refer to is being deleted,
> so they are **moot** rather than fixed, and are marked so. Nothing about the engine
> changed, and the items about it stand exactly as they were.
>
> They are kept rather than removed because a status file that quietly drops what it once
> promised is the drift this file exists to prevent. You reported them; you are owed the
> answer that the answer is now "this screen is going away".

**Nothing here says "done".** A thing is done when it has passed a UI test on the emulator
*and* you have confirmed it on your phone. Until both, the most this file will say is
**"believed fixed"** — code that was written and merged and has never been watched working.
That distinction is the whole point of the file: three builds went out marked done.

Keep this current. A status list that drifts is worse than none, because it is believed.

| | Status | Meaning |
|---|---|---|
| ✅ | **Confirmed** | passed an emulator UI test **and** you saw it work |
| 🟡 | **Believed fixed** | merged, and there is a test — but not both of the above |
| 🔨 | **In flight** | pull request open, not merged |
| ⛔ | **Not built** | the code exists; nothing in the app reaches it |
| ⚰️ | **Moot** | the screen or feature is deleted by the pivot; nothing to fix |

---

## First report — the screenshot

| # | What you reported | PR | Status |
|---|---|---|---|
| 1 | The app opened on its own name. There were no screens at all. | [#11](https://github.com/BigdataAytee/trim-gallery/pull/11) | 🟡 the gallery is mounted; PR [#28](https://github.com/BigdataAytee/trim-gallery/pull/28) is the first test that proves it |
| 2 | Tapping a photo closed the app | [#15](https://github.com/BigdataAytee/trim-gallery/pull/15) | 🔨 fix merged; the emulator test for it is in [#28](https://github.com/BigdataAytee/trim-gallery/pull/28) |
| 3 | Nothing moved — no reveal, no transition | [#16](https://github.com/BigdataAytee/trim-gallery/pull/16) | 🟡 unconfirmed on a phone |
| 4 | Android refused folders with no explanation | [#18](https://github.com/BigdataAytee/trim-gallery/pull/18) | 🟡 partly superseded by [#27](https://github.com/BigdataAytee/trim-gallery/pull/27) |

## Second report — the four items

| # | What you asked for | PR | Status |
|---|---|---|---|
| 5 | Say which build I am on — version and commit SHA in Settings and in the diagnostics file | [#23](https://github.com/BigdataAytee/trim-gallery/pull/23) | 🟡 merged, unconfirmed on a phone |
| 6 | Tapping a video closes the app | — | 🔨 **does not reproduce on the emulator.** Journey 3 in [#28](https://github.com/BigdataAytee/trim-gallery/pull/28) taps a video, opens the viewer and requires the player to reach READY and play — and it passes. That is not the same as fixed: the emulator plays a `file://` clip, your phone plays a `content://` SAF document, and the difference is exactly where a crash could still live. **Still no stack trace** — the diagnostics you exported never reached me |
| 7 | Video tiles go black | [#24](https://github.com/BigdataAytee/trim-gallery/pull/24) | 🟡 provider thumbnail, then a frame off a descriptor, cached to disk; never a black tile. Unconfirmed on a phone |
| 8 | Loading far too slow | [#25](https://github.com/BigdataAytee/trim-gallery/pull/25) | 🟡 the grid is drawn from the database on launch and the folder walk happens behind it |
| 8a | …with Macrobenchmark numbers, before and after | — | ⛔ **not delivered.** Nothing was measured, so there are no numbers, and I will not invent them. It needs a device: the benchmark cannot run on this build environment |

## Third report — the crash loop

| # | What you asked for | PR | Status |
|---|---|---|---|
| 9 | Crash on granting a folder, then a crash loop | [#26](https://github.com/BigdataAytee/trim-gallery/pull/26) | 🔨 open. Cause: `media_item.folder_grant_id` references `folder_grant(id)`, foreign keys are on, and nothing wrote the grant row — two identities for one folder |
| 10 | A crash loop must be impossible: recovery screen, diagnostics, remove the grant, never retry | [#26](https://github.com/BigdataAytee/trim-gallery/pull/26) | 🔨 open |
| 11 | The folder picker must open at the top level, not DCIM | [#27](https://github.com/BigdataAytee/trim-gallery/pull/27) | 🔨 open |
| 12 | This list | — | you are reading it |
| 13 | Emulator UI tests, and the "launches" check must run them | [#28](https://github.com/BigdataAytee/trim-gallery/pull/28) | 🔨 open — five journeys, plus a check that the job really ran them |

## Fourth report — "keeps stopping the moment I tap a picture"

| # | What you asked for | PR | Status |
|---|---|---|---|
| 23 | Make the emulator crash the way the phone does: real photos and videos, real Coil, tap a photo tile, tap a video tile, twenty times each | [#36](https://github.com/BigdataAytee/trim-gallery/pull/36) | 🔨 **reproduced on the first tap of both kinds.** Real `MainActivity`, `content://` documents through a test provider, production `ImageLoader`, 8-megapixel photographs |
| 24 | Report the root cause with file and line before fixing | [#36](https://github.com/BigdataAytee/trim-gallery/pull/36) | 🔨 `HeroGeometry.lerpRadius` returned a negative corner radius past fraction 1.0 because `OPEN_EASING` overshoots; `HeroViewer` passed it to `RoundedCornerShape`, which throws. Every earlier tap journey ran with `reduceMotion = true`, which never overshoots. Fix: clamp at the source, with three JVM tests |
| 25 | A second emulator target at my phone's API level | [#36](https://github.com/BigdataAytee/trim-gallery/pull/36) | 🔨 **added at API 36**, alongside 34, and it found a real bug on its first run: both ExoPlayers were configured during composition and crashed with "Player is accessed on the wrong thread" at API 36 while passing at API 34. Every journey must now pass on **both** levels; the check names which one failed |
| 25a | Tapping a video, at API 36 | [#36](https://github.com/BigdataAytee/trim-gallery/pull/36) | 🔨 `TilePreview.kt` and `VideoPlayer.kt` now pin the player to the main looper and touch it only there. This is the same journey your field report was about |
| 29 | Big files, with both sections | [#42](https://github.com/BigdataAytee/trim-gallery/pull/42) | 🔨 "Find big files" runs the real triage; rows show current and estimated size, largest saving first; "Large but can't be trimmed" groups the pipeline's own skip reasons. Trim runs one file at a time — batch actions need a queue on the controller and land separately |
| 28 | Home and Folders, with emulator journeys | [#41](https://github.com/BigdataAytee/trim-gallery/pull/41) | 🔨 Home says what was freed, when the next run is, and carries the overnight switch; Folders adds, removes, sets per-folder modes and explains whole-phone access before asking for it. Seven journeys |
| 27 | The gallery, and everything built on it | [#38](https://github.com/BigdataAytee/trim-gallery/pull/38) | ⚰️ deleted. The complete pre-pivot tree is on `shelf/pre-pivot` at `90895cf` |
| 26 | No build until both tile types survive twenty taps on both API levels | [#36](https://github.com/BigdataAytee/trim-gallery/pull/36) | 🔨 the twenty-tap suite is required on both devices; no build has been sent |

---

## Screens and features that do not exist in the app yet

Each of these is written, unit tested, and **not reachable**. This is the pattern behind
every report so far: the logic was tested, the wiring was never done.

| # | Feature | Where the code is | What is missing |
|---|---|---|---|
| 14 | **Settings** — per-folder modes, quality, cap | [#20](https://github.com/BigdataAytee/trim-gallery/pull/20) | reachable from the grid. 🟡 unconfirmed on a phone |
| 15 | **Space** — freed, history, skipped | [#21](https://github.com/BigdataAytee/trim-gallery/pull/21) | reachable, but it will read **zero for everything**, because nothing has ever been optimised (see 17) |
| 16 | **Restore an original** from Space | `shared/feature/space`, `UndoBinAndroid` | the button is drawn from a job row and no job rows exist. `onRestore` is an empty lambda today |
| 17 | **The night pass actually optimising anything** | `NightWorker`, `NightRun`, `VideoOptimiseStep` | 🔨 **wired end to end** ([#34](https://github.com/BigdataAytee/trim-gallery/pull/34)). `NightWorker` asked for `NightRun.Step` and nothing provided one, so a night that woke would have failed looking for it — with no screen, at 3am. It is bound now, with `NightWiringTest` asking the assembled graph on a device for the same seven definitions the worker resolves. **Nothing has been compressed on your phone yet**, and the first thing that does will be a night on a charger. The gates that stand in its way — VMAF, the mtime snapshot, `Replacer` as the only writer — were built first and are asserted; what is new is that they are reachable |
| 17a | **`YuvSource` and `ProbeEncoder` on Android** | [#32](https://github.com/BigdataAytee/trim-gallery/pull/32), [#33](https://github.com/BigdataAytee/trim-gallery/pull/33) | 🔨 **both built and bound, and the search is assembled from them.** `YuvSourceAndroid` decodes on a real runtime and six instrumented tests prove it on the emulator. `ProbeEncoderAndroid` encodes a window in hardware and decodes it back — and **that half cannot be proved in CI**, because an ATD image has no hardware encoder and BUILD.md rule 2 forbids the software one. What CI does assert is the rule: no hardware encoder means an empty window and a skipped file, never a software encode. The encode itself needs your phone |
| 18 | **Compress now**, and play-to-compress | [#35](https://github.com/BigdataAytee/trim-gallery/pull/35) | 🔨 **long-press → Optimise is built and reachable**, with the estimate, a progress ring on the tile, "Now 165 MB (was 380 MB)" and Keep / Undo — five emulator journeys drive it over a faked step, because CI has no hardware encoder. It ends on Keep / Undo rather than USER_JOURNEY § 6's Share / Replace original / Keep both; that difference is deliberate and recorded in PROJECT.md. ⛔ **play-to-compress is still unreferenced** — `PlayToCompressTap` has no callers |
| 19 | **The viewer's info sheet** — what a file is, what it saved | `ViewerInfoSheet` | ⛔ the grid's `sheet` slot is never filled |
| 20 | **Tile badges** — the video clip badge, durations | `ClipBadge`, `TileChip` | ⛔ the grid's `tileOverlay` slot is never filled |
| 21 | **Processing tiles** — the breathing halo and progress ring | `Modifier.breathing`, `ProgressRing` | ⛔ `processingIds` is hard-coded empty, and would stay empty anyway until 17 exists |
| 22 | **Search**, **People**, **Albums**, **Recently deleted**, **Locked folder**, **Editor**, **Memories** | `shared/feature/{search,people,cleanup,editor}` | ⛔ no file in `androidApp` references any of these modules |

---

## What the emulator now proves

[#28](https://github.com/BigdataAytee/trim-gallery/pull/28) adds five journeys to the CI job
that previously only asserted the Activity reached RESUMED. **All five pass** as of
`c2501a7`:

1. Grant a folder → the grid renders, the grant row is written, the night pass is scheduled
2. Tap a photograph → the viewer opens
3. Tap a video → the player reaches READY and plays
4. Relaunch after a grant → the photographs are there before anything is walked
5. A startup that fails → the recovery screen, and the mark stays set so nothing retries

A green job is no longer taken as evidence they ran: the instrumentation result XML is read
back and the job fails unless every journey is there and passed.

**What five passing journeys do not mean.** They are an emulator, with a fake grant and
`file://` refs, because a persisted SAF permission cannot be obtained without the system
picker. Your phone has real `content://` documents, a real provider, real hardware decoders
and your own library. Everything in the table above is still 🟡 until you have seen it.

## Still waiting on you

- **The Android version on the Pixel 9 Pro XL** (item 25). The message naming it was cut off
  twice. The stack trace for the tap crash (item 6) is no longer needed — the emulator
  produced it (item 23).
- **Folder mode defaults.** BUILD.md § 6 says Offload/Free by default where external storage
  exists; the app ships **Keep** for every folder, because Keep is the only mode that never
  removes an original and nobody had been asked. Your call.
- **MediaStore.** Reading metadata through MediaStore instead of opening every file through
  SAF needs `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO`, which PROJECT.md § Access rules out
  today. Your call, and it changes what the app asks for on first run.
