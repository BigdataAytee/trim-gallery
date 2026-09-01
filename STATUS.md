# What works, what does not

Every item you have reported, with the pull request that addressed it and an honest status.

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

---

## Screens and features that do not exist in the app yet

Each of these is written, unit tested, and **not reachable**. This is the pattern behind
every report so far: the logic was tested, the wiring was never done.

| # | Feature | Where the code is | What is missing |
|---|---|---|---|
| 14 | **Settings** — per-folder modes, quality, cap | [#20](https://github.com/BigdataAytee/trim-gallery/pull/20) | reachable from the grid. 🟡 unconfirmed on a phone |
| 15 | **Space** — freed, history, skipped | [#21](https://github.com/BigdataAytee/trim-gallery/pull/21) | reachable, but it will read **zero for everything**, because nothing has ever been optimised (see 17) |
| 16 | **Restore an original** from Space | `shared/feature/space`, `UndoBinAndroid` | the button is drawn from a job row and no job rows exist. `onRestore` is an empty lambda today |
| 17 | **The night pass actually optimising anything** | `NightWorker`, `NightRun` | ⛔ **`NightRun.Step` has no binding.** The pass is scheduled ([#19](https://github.com/BigdataAytee/trim-gallery/pull/19)) and would fail the moment it woke. `VideoOptimiseStep` — the assembly of probe, search, encode, verify, replace — is not written. **Nothing has ever been compressed on your phone, and nothing will be until this exists.** It is the one path that can destroy a photograph, which is why it is being written last and slowly |
| 18 | **Compress now**, and play-to-compress | `shared/feature/compress`, `PlayToCompressTap` | ⛔ nothing in the app references either. `PlayToCompressTap` has no callers at all |
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

- **The stack trace for the video-tap crash** (item 6). Settings → Export diagnostics. If the
  app cannot get that far, the recovery screen in [#26](https://github.com/BigdataAytee/trim-gallery/pull/26) offers the same export.
- **Folder mode defaults.** BUILD.md § 6 says Offload/Free by default where external storage
  exists; the app ships **Keep** for every folder, because Keep is the only mode that never
  removes an original and nobody had been asked. Your call.
- **MediaStore.** Reading metadata through MediaStore instead of opening every file through
  SAF needs `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO`, which PROJECT.md § Access rules out
  today. Your call, and it changes what the app asks for on first run.
