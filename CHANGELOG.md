# Changelog

## Milestone 10 — Space, history, Compress now, play-to-compress, settings

The screens where the app has to account for itself, and the one path that is allowed to
run on battery.

### The Space screen and history

`SpaceScreen` and `History` between them answer "is this worth having on my phone?", and
every number on them is one the user could in principle check. The running total is a sum
of what actually happened; the projected saving is labelled an estimate and never dressed
up as a fact. A paused run is visibly distinguishable from a working one, because a
progress ring spinning while the pass is stood down for heat is a lie the user can catch by
feeling the phone.

History is honest about restore in four ways rather than one: from the bin (with the date
it expires), from an external drive (so the app does not offer a one-tap restore for a file
in a drawer), already restored, and expired — carrying *when* the original went, so the
sheet can say so. Only succeeded jobs appear; a failure belongs on the Skipped screen with
its reason.

`EnergyEstimate` reports whole watt-hours and declines to show a battery percentage below
1%, because "0%" and "0.4%" are the same claim made with different confidence.

### Compress now

The one relaxation of BUILD.md rule 1 — *never encode on battery unless the user explicitly
taps Compress now* — and it is scoped so it cannot become a second night pass: one file, one
tap, nothing that could be applied to a queue.

The decision it exists to make is the split between refusals and warnings:

- **A user's explicit tap overrides "not worth it".** Already-efficient, too small,
  wouldn't-shrink are triage's judgements about whether a night's battery is well spent, and
  someone standing in front of the file has different information. They are warned, not
  refused; the verify gate still refuses to replace a file with a larger one.
- **It does not override "this would lose data" or "we already tried".** HDR, Motion Photos,
  Ultra HDR, Live Photos and RAW are refused to Pro users too. So is a file the search
  already failed on: it is deterministic and would fail again.
- **A file Trim already optimised cannot be optimised again**, at any tier. Every encode
  targets VMAF 95 against *what it is given*, so a second pass measures quality against an
  already-lossy copy. Without this rule Compress now would be a way around the
  generational-loss guard, five times a day, on the files a user cares about most.

Item facts are checked before the paywall, so a user is never shown a Pro offer for a button
that would still do nothing after they paid.

Neither number on the sheet is invented: the expected saving comes from triage or the
predictor and the expected time from a measured encode speed, and each is null — with copy
that says so — until something has measured it. And Compress now replaces nothing by
itself. It ends on Share / Replace original / Keep both, and until one is pressed the
original has not been touched.

### Play-to-compress

A decoder tap driven by a player is a tap driven by a user, and users pause, scrub back,
skip the middle and leave. Every one of those makes the frames arriving at the encoder stop
being the source, and the encoder cannot tell. It would produce a file — a shorter one, or
one missing the middle — that then goes through verify and replace looking like a success.

So `PlayToCompress` holds one rule: **a tap either delivers every frame, in order, from the
first to the last, or it delivers nothing at all.** Nine ways it can break are named and
tested — starting mid-stream, seeking back, skipping forward, dropped frames, an
end-of-stream that is really a stop, leaving early, a pause that never ends, heat, and a
decoder error. All but the last put the file back in the night queue, where it will be
encoded from a decode nobody is steering.

The one that matters most is starting mid-stream: a user resuming a half-watched video
would otherwise get an encode of the second half, and it would pass every later gate — the
output opens, it is smaller, and VMAF sampled against its own timeline looks fine.

The gap tolerance errs towards giving up. Being wrong that way costs one wasted encode;
being wrong the other way is a silently shortened video.

### Settings

`SettingsPolicy` sits between the screen and the store, and everything goes through it in
both directions. On the way in, because a value that cannot be honoured must never be
persisted — `AndroidGuards` parsed the stop-by time with a `runCatching { … }.getOrNull()`,
which meant a typo silently became "no stop time" and the phone worked all night. On the way
out, because entitlements change after the write: a Pro user who set Compact and 90-day
retention and then lapses gets Standard and 7 days from the very next read, with nothing
having to remember to re-save.

Writing the tests turned up a contradiction between two of the specs. BUILD.md § 6 gives
the "Free space" folder mode a default of 30 days; MONETIZATION.md gives the free tier 7.
Both are kept: the setting's default is 30 and a free user's copy of it is 7. The
consequence — that the UI must show the sanitised number, not the stored one — is written
down in PROJECT.md and asserted in a test, because promising a free user 30 days of
originals and deleting them at 7 is the worst kind of bug this app could have.

`DataStoreSettings` replaces the milestone-4 placeholder that ran the night pass on
hard-coded defaults. `TrimRepository` reads settings and tier through it now, so the guards,
the scheduler and the Settings screen cannot disagree about what the user chose.

### Numbers

562 shared JVM tests and 47 build-guard tests pass; the guards scan 141 source files clean.

## Milestone 9 — the index: hashes, people, search, duplicates, chat media

Everything that *decides* anything is shared, because ARCHITECTURE.md § 6 says the
perceptual hash is a "shared Kotlin impl" and the same argument applies to all of it: two
devices grouping faces or hashing pictures differently would take a user's library apart
the moment it moved between them.

### Perceptual hash

The standard DCT construction, 32×32 working size, and every property it needs is tested:
a resized copy matches, a brightness shift matches, re-compression noise matches, unrelated
pictures do not. Two things came out of writing it:

- **The DC bit was dead.** Thresholding the mean-brightness coefficient against the median
  of its neighbours sets that bit for every image ever hashed — sixty-three working bits
  where the type says sixty-four. The hash now takes the 64 lowest-frequency coefficients
  *after* DC, in zig-zag order. A test asserting that every bit is used is what found it.
- **The hash ignores aspect ratio**, because everything is reduced to a square grid. That
  is inherent, not a defect — but it means `DuplicateFinder` has to compare shape
  separately, and there is a test documenting the blind spot rather than papering over it.

### Duplicates

Exact (SHA-256) and near (perceptual), and the difference is explained to the user because
it matters: deleting one of a set of byte-identical files loses nothing at all, while
deleting a near-duplicate loses a few pixels.

The best-copy rule is: a favourite always wins, then the most pixels, then the largest file,
then the oldest, then by id so the suggestion never moves between two openings of the
screen. **A file this app optimised is not penalised for being smaller** — it is the same
picture at the same resolution, verified visually identical, and preferring the un-optimised
copy would quietly undo the night's work. Hidden items never appear; a cleanup screen that
showed them would be a hole straight through the locked folder.

### People

Greedy single-pass clustering on cosine similarity, ordered by detection quality so the
clearest faces form the clusters. **Tuned to under-merge**, at 0.72: splitting one person
into two clusters is a nuisance the user fixes with a tap, while merging two people puts one
person's photographs under another's name and cannot be undone without opening every
picture. The splits are offered back as merge suggestions.

A blurred face cannot *start* a cluster — an embedding halfway between everybody absorbs
strangers — but can still join one good faces formed. A face that clusters with nothing is
kept, not discarded: a second sighting next month turns two singletons into a person.

### Search

One box, six kinds of answer, no syntax. A term is a candidate label *and* a candidate piece
of text; "2019" is searched as a year **and** as a word, because it could be a date or the
number on a race bib and guessing wrong returns nothing. A name is only a person because the
user named a cluster that, and a place is only a place because a photograph was taken there
— inferring either from the shape of the word would put every capitalised noun in the people
facet.

Ranking puts a named person above OCR text above a place above a label scaled by the
classifier's own confidence. Recency may contribute at most a fifth of the score: enough to
separate two equal matches, never enough to lift a weak recent match above a strong old one.

### Chat media and the index step

`ChatMediaReview` groups by app and age and offers only what is old *and* never opened —
never inferred, because "not opened" is the strongest reason to suggest deleting something
and a wrong inference would offer up photographs the user looks at often. Favourites and
hidden items are never offered.

`IndexStep` guards each stage independently: a file whose OCR throws keeps its labels,
because a hundred thousand files guarantees unusual ones. **A bug caught by writing its
test:** the failure list was an instance field on an object the DI graph makes a singleton,
so one bad file's failures followed every file indexed after it for the rest of the night.

### Android

`MlKitIndexer` — the **bundled** models, not the Play-services ones, because the
downloadable variants fetch over the network and this app has no INTERNET permission. A
model that cannot download is a feature that silently never works. Face detection runs in
ACCURATE mode: this is a charging phone at night, and the alternative splits someone's child
across four "people".

### Verified

- **470 shared JVM tests**, all passing (up from 393).
- **47 build-guard tests**; guards clean across all 127 source files.


## Milestone 7 — photos: jpegli, SSIMULACRA 2, JPEG XL and PNG repack

The still-image half of the pipeline, and the first milestone where the native code is not
just built but **checked against its own upstream binaries** — which caught two bugs that
produced perfectly valid output files that were merely wrong.

### Native (`shared/native`), all four verified against upstream

| function | verified how | result |
|---|---|---|
| `ssim2_score` | against upstream's `ssimulacra2` binary | **68.24788284 both** — identical to the last digit |
| `jpegli_encode` | against `cjpegli -q 85` on the same pixels | **93.58177281 both**, in 7391 bytes against upstream's 7456 |
| `jxl_recompress` | `djxl` back to a JPEG, `cmp` against the original | **byte for byte identical** |
| `png_optimise` | decode both PNGs and compare pixels | **identical**, 202234 → 108258 bytes |

`shared/native/test/verify_photo.sh` runs all six checks and is what those numbers come
from.

**The bug the cross-check existed to catch.** `jpegli_encode` emitted **4:2:0 baseline**
where upstream emits **4:4:4 progressive**. The output was a valid JPEG of the right size
and the right dimensions; only the numbers gave it away. The blue channel came back with
five times the error of red or green, SSIMULACRA 2 fell from 93.6 to **67.0**, and the file
got *larger* — the chroma artefacts cost more bits than the subsampling saved. A test that
merely checked "it links and produces a JPEG" would have shipped it.

**The second one** was subtler and did not survive investigation: I first blamed the
`JpegliDataType` default, wrote a fix, and then found jpegli already defaults to 8-bit. The
explicit calls stayed — `JPEGLI_TYPE_FLOAT` is the enum's zero value, so anything that
zeroes the struct gets float samples read into a byte buffer — but the comment claiming
they cost 26 points was corrected rather than left standing.

**jpegli is no longer part of libjxl.** Upstream split it out; at libjxl's head
`lib/jpegli` does not exist. STACK.md's table already pointed at `google/jpegli`, but its
build-layout diagram and the `ndk-build` skill both said one submodule gave both — the same
class of error as the XPSNR one in milestone 2. Both corrected, and the skill now carries
the chroma trap, the `setjmp` requirement and the SSIMULACRA-2-is-a-tool-source note.

`oxipng` is a crates.io dependency of a thin C-FFI wrapper crate, built with
`default-features = false`: rayon would start a thread pool competing with the encoder on a
phone trying to stay cool, and zopfli spends minutes per image for a few per cent against a
cap measured in minutes. The FFI call is wrapped in `catch_unwind`, because oxipng panics on
some malformed input and a panic across an FFI boundary is undefined behaviour.

### Shared (`core/pipeline/photo/`)

`PhotoOptimiseStep` is the only way to obtain a `ReplacePlan` for a still, exactly as
`VerifyPass` is for video — BUILD.md rule 3 applies to photographs too, and photographs are
the files people are least willing to lose.

- **`PhotoQualitySearch`** bisects the quality range rather than spending a four-probe
  budget: a photo probe is milliseconds, not minutes. Targets are SSIMULACRA 2 **90** for
  Standard and **85** for Compact — upstream's own scale calls 90 "visually lossless" and
  85 "excellent quality", which is what makes BUILD.md's "≥ 85–90" and § 9's two settings
  one behaviour.
- **A transparent image never takes the lossy path.** JPEG has no alpha, so transparency
  would be flattened against whatever happened to be behind it — a visible change the gate
  *cannot* see, because it would compare a flattened result against a flattened reference
  and report a perfect match.
- **`PhotoRouting`** decides between jpegli, HEIC, lossless JXL and an oxipng repack.
  Reversible mode wins over the format setting; a PNG denser than 1 B/px is treated as a
  photograph and takes the gated lossy path, because there is no point making a smaller PNG
  of a photograph. Erring high merely repacks a photo losslessly; erring low would run a
  lossy encoder over text, where ringing is what people notice.

### Android

`PhotoCodecAndroid`. Three of the four paths are native and identical on both platforms, so
the numbers do not depend on the phone. Only HEIC is the platform's: a HEIC still is an HEVC
frame, and `HeifWriter` puts it on the hardware encoder, which is the only way to stay
inside BUILD.md rule 2 — hand-assembling a `MediaCodec` would have failed the codec guard,
correctly.

### The build guard, made robust

Adding the submodules made `verifySourceBoundaries` throw `StackOverflowError`: it stripped
comments with one regex over the whole file, and Java's engine recurses while backtracking,
so it died on a 2,000-line generated Kotlin file inside brotli. A guard that crashes on a
large file fails the build for a reason unrelated to the boundary it checks. It is now a
character scan, with tests for a 20,000-line file, for line-number preservation, and for an
escaped quote inside a string.

### Verified

- **393 shared JVM tests**, all passing (up from 363).
- **47 build-guard tests**; guards clean across all 113 source files.
- **6 upstream photo checks** and **16 metric checks** from milestone 2.


## Milestone 6 — triage rules and the skip list

The cheap step that decides what the expensive steps ever see, and the screen that explains
every file the app declines to touch.

### The library diff (`LibraryDiff`)

ARCHITECTURE.md § 7 opens the night with `storage.scan(grants) → DB diff`, and § 9 gives
the rule that makes it matter: `DONE/SKIPPED/FAILED → NEW when the file changes`. Both ways
of getting it wrong are now tested:

- **Too weakly**, and a re-edited video keeps a verdict describing a file that is gone.
- **Too eagerly**, and the app re-optimises its own output every night. Every re-encode
  targets VMAF 95 against *whatever it is given*, so a second pass measures quality against
  an already-lossy copy — **generational loss**, and two nights of it is visible.

Three things came out of writing it:

- **A folder that was not scanned never loses its rows.** The commonest real case is two
  granted folders with the SD card out; a diff that did not know which grants were covered
  would report every photo on the card as removed and delete its index, its labels and its
  faces.
- **`merge` takes the container facts from the scan, not the stale row** — a test caught the
  first version keeping the old codec and bitrate, which would triage a re-exported clip on
  numbers that no longer describe it.
- **The user's own decisions survive an edit.** `favourite` and `hidden` share a bitmask
  with the container flags (SCHEMA.md) but are not properties of the bytes: a re-edited
  photo must not fall out of the locked folder.

### Triage (`Triager`, extended)

- **A file this app already optimised is never optimised again.** Checked before every
  other rule, because no later rule would notice: our own output is, by construction,
  exactly the kind of file the bitrate rules are looking for. `MediaItem.optimisedAt` (a
  documented supplement to SCHEMA.md) makes the rule a property of the row rather than of a
  timestamp comparison a provider could round away.
- **Hardware capabilities are pre-checked** (ARCHITECTURE.md § 13). A file this phone could
  never encode is skipped with a reason, instead of costing a probe, a search and a full
  encode before failing.
- **A saving too small to notice is not worth a night's battery.** BUILD.md rule 5 says to
  skip files that will not shrink; in practice that means "will shrink by an amount nobody
  would notice", and a queue full of those pushes the videos that would free gigabytes past
  the nightly cap. Photos are exempt — a jpegli pass costs milliseconds, not a probe cycle.
- **PNG gets a size gate but no quality gate**, since the repack is lossless.

### Reading containers (`ContainerReader`)

New in `engine-api`, because triage needs codec, resolution, fps, bitrate and the format
flags, and BUILD.md § 5 says to read them *"from container"* without decoding.

Deliberately separate from `LibraryStorage.scan`: a scan of a granted tree is one cursor
query over thousands of rows, and opening every one of those files to read its header would
turn a second into a minute on a large library. So the pass scans cheaply, diffs, and reads
headers only for the handful of files that actually changed. `ContainerReaderAndroid` uses
`MediaExtractor` and `MediaMetadataRetriever` — HDR from the track's colour transfer, not
from the file name, because an HDR clip and an SDR clip are both `.mp4`.

BUILD.md's *"read camera-written encoder metadata from `udta`"* earns its place where the
spec puts it — immediately before "Predict": a file with no camera model but a recognisable
encoder becomes its own predictor family instead of being lumped into "unknown" with every
other metadata-less file, which would poison a prediction that is otherwise reliable.

### The skip list (`SkipList`)

BUILD.md § 9 requires it; this is where the reasons are written, which makes it product
surface rather than plumbing.

- **A reason is a sentence, not an enum name**, and never says "compress" or "shrink"
  (DESIGN_SYSTEM.md § Copy tone). Asserted, for all thirteen reasons.
- **"Try again" appears only where trying again could work.** Only two things can change: a
  file can arrive from the cloud, and a failure can be transient. A button that does nothing
  teaches the user not to believe the rest of the screen. `COULD_NOT_REACH_QUALITY` is
  deliberately not retryable — BUILD.md § 5 skips such a file permanently, and the search is
  deterministic.
- **Actionable groups lead, then the largest.** A screen that buries its one actionable row
  under four hundred already-efficient photos is a screen nobody scrolls.
- **A failed file never borrows a stale skip reason.** The pipeline records a `SkipReason`
  only for a deliberate decision, and "something went wrong" is not one.

### Wiring

`TriageStep` ties it together — scan, diff, read headers for what changed, triage, write the
verdict and the queue's ordering key — and `TrimRepository` gained the `Sink` that persists
it. One detail worth knowing: **an item with a live undo entry keeps its row when the file
disappears**, because `undo_entry.media_id` cascades and that row is what points at the
original still sitting in the bin.

### Verified

- **363 shared JVM tests**, all passing (up from 322).
- **43 build-guard tests**; guards clean across all 106 source files.


## Milestone 5 — scheduling, thermal polling, caps, alarm-aware stop

BUILD.md rule 6 in code: *"Pause when thermal headroom > 0.7; cap work per night; stop 30
min before the user's alarm."*

### The guards (shared, `core/pipeline/night/`)

`GuardChain` evaluates the ARCHITECTURE.md § 9 order — Foreground → Charging →
BatteryFull? → Thermal → StopBy/Alarm → Storage → Cap — and a failing test found the rule
that order alone does not express:

- **A stop is never masked by an earlier pause.** The § 9 order decides which reason the
  user is *told* among conditions of equal severity; severity decides *behaviour*. The
  first version returned `Pause(FOREGROUND)` for a phone that was in use **and unplugged**,
  which would have left the pass sitting "paused because you're using it" while running on
  battery — the exact thing rule 6 exists to prevent. Pauses are now collected and the
  chain runs to the end; a stop returns immediately.

- **`ThermalGate` has two thresholds, and the gap is the point.** Pause above 0.7, resume
  below 0.5, hold the previous answer in between. A single threshold on a value hovering
  around it starts and stops the encoder several times a second, which heats the phone
  *more* than running steadily would and produces a History screen reading "paused for heat
  400×". Pauses are counted per stand-down, not per reading. A device that reports NaN —
  no thermal sensing, or polled faster than every 10 s — is treated as *no information*,
  which never resumes a phone that was already hot.

- **`NightBudget` caps work, not elapsed time.** A pass plugged in for five hours but stood
  down for four of them has used one hour of its sixty. Counting wall clock would let a hot
  night quietly consume a cool one's allowance.

- **`AlarmWindow` goes through the calendar, not the arithmetic.** "Stop by 06:00" at 23:00
  means six *tomorrow*; at exactly 06:00 it means tomorrow too, or a run started on the
  boundary would end before a single file. A stale alarm in the past is ignored rather than
  treated as overdue — honouring one would stop every night from then on, a bug that looks
  exactly like "the app just stopped working". Tested across a daylight-saving change,
  where a day is 23 hours long and adding milliseconds lands an hour out.

- **`RetryPolicy`** is 5/15/60 s (ARCHITECTURE.md § 13), fixed rather than exponential
  because the thing being waited for is a person: the codec was reclaimed by a camera or a
  video call.

### The loop (`NightRun`)

ARCHITECTURE.md § 7's pass, platform-free and driven with virtual time in tests.

- **Guards are checked *during* a file, not only between them.** A full encode is minutes
  long; checking only at the boundaries means a phone that is picked up keeps working for
  the rest of the file. The watchdog cancels the step within one 5-second poll. A
  cancellation that did **not** come from the poll — the OS revoking the window — is
  rethrown, so a lost window is never recorded as a thermal pause.
- **An interrupted file goes back on the queue**, not into the skipped or failed list.
  Nothing was wrong with it.
- **A pause hands the window back after 30 minutes** rather than holding a wakelock until
  morning.
- Checkpoints after every file and while paused, so a night that is killed still explains
  itself.

### Android

`AndroidGuards` (reads only: `getThermalHeadroom(30)`, `BatteryManager`, `StatFs` on the
scratch volume, `AlarmManager.getNextAlarmClock`), `WorkManagerScheduler`,
`ForegroundWatcher`, and `NightWorker`.

Two platform facts drove design decisions worth recording:

- **The night pass must be a foreground service.** WorkManager stops an ordinary worker
  after ten minutes and BUILD.md budgets sixty. USER_JOURNEY.md § 3 says the night has no
  UI, so the required notification goes on a `IMPORTANCE_MIN` channel — silent, no badge,
  no heads-up. `mediaProcessing` service type where the platform has it (API 35+), because
  Android 15's six-hour daily budget for `dataSync` would eventually cut nights short.
- **"Full" is 98%, not 100.** Many phones sit at 99 for a long time on a topped-up battery,
  and a pass waiting for a number it may never see would simply never run.

### Milestone 4's gap, closed

`TrimRepository` implements `UndoJournal`, `OriginalLocator`, `NightFacts` and the night
queue against SQLDelight, so `SafeReplacerAndroid` and `NightWorker` are now constructible.
One class for several ports because they read and write the same tables in the same
transactions; splitting them would let two of them disagree about what the queue is. Every
query is spelled with an explicit column mapper rather than the generated row type, so a
schema change fails to compile instead of silently mapping the wrong value.

`MediaFlagsBits` — the SCHEMA.md bitmask — is split out as pure Kotlin and tested
exhaustively over all 256 combinations, because a pair of transposed constants would pass
any sampled test and bit 128 is what hides the user's photos.

### Verified

- **322 shared JVM tests**, all passing (up from 258).
- **43 build-guard tests**, all passing; guards run clean across all 99 source files.


## Milestone 4 — verify, safe replace, undo and offload

The gate in front of the user's only copy of their photos, and the swap behind it.

### Verification (shared, `core/pipeline/verify/`)

`Verifier` implements BUILD.md § 5's gate: VMAF `vmaf_v0.6.1` at 1080p, `n_subsample=10`,
on three 5-second windows at the start, middle and end. Around it, `VerifyPass` runs the
step-up ladder — one encode plus at most two re-encodes at a higher bitrate — and is the
**only** way to obtain a `ReplacePlan`, so no caller can construct one for a file that
never passed.

Decisions worth knowing about:

- **The worst window decides, not the mean.** 99, 99, 88 averages to 95.3 and would pass
  on a mean. Three windows exist precisely to catch the one place an encode fell apart.
- **Losing the audio track is a failure, not a saving.** Audio is passed through, never
  re-encoded; a smaller file with no sound would otherwise sail through the size gate.
- **Only a quality failure is retryable.** A truncated mux and an output that is not
  smaller are both terminal — a higher bitrate cannot fix either, and stepping up would
  produce two more bad files at a cost of two encodes each.
- **A step up is 15%**, chosen against `SettingSearch`'s own 12% convergence: a smaller
  notch would land inside the bracket the search already called indistinguishable.
- **Careful mode tiles the whole file**, exactly, with no gaps and no overlap.

### The replace contract (shared, `core/pipeline/replace/`)

`ReplaceSequence` is the one implementation of ARCHITECTURE.md § 7 — copy metadata → park
original → commit → restore timestamps → notify library → write `UndoEntry` — with every
completed step reversed on failure, in order. Both platforms delegate to it, which is what
makes § 14's *"Replacer plan/rollback with fake storage"* a JVM unit test rather than a
device test nobody runs.

- **Rollback is uncancellable.** A night pass is cancelled the instant the phone is
  unplugged; unwinding inside a cancelled coroutine would abandon the swap exactly halfway
  — original in the bin, nothing in the library. The unwind runs under `NonCancellable`
  and the cancellation is rethrown afterwards.
- **Untidy loses to lost.** Every inverse is attempted even if an earlier one failed: a
  stray replacement is a swept file, an unreachable original is a deleted photograph.
- **A journal failure unwinds the whole swap.** An optimised file with no undo row cannot
  be restored by the UI, so keeping the original and losing the saving is the better trade.
- **The snapshot is re-checked inside the Replacer**, immediately before the first
  mutation, as well as by the pipeline — only a check taken there closes the window.

`OffloadMove` holds the cross-volume ordering on its own: copy → verify → *then* remove.
Every platform ships a convenient `move` that silently degrades to copy-and-delete across a
mount point with no way to tell whether the destination write finished; SD cards are pulled
mid-write, and counterfeit ones report a write as complete while dropping the data. The
source delete is unreachable except through a verification that returned true.

### Android

`SafeReplacerAndroid` (SAF: staged create, stream, then an atomic rename onto the
original's name), `UndoBinAndroid` (bin and offload, both via `OffloadMove`; restore stages
the original back before removing what holds its identity), `MetadataCopierAndroid` (EXIF
and XMP tag by tag, MP4 `mvhd` creation time and `tkhd` rotation matrix),
`OutputProbeAndroid` (`MediaExtractor`, longest track), and `SafStorage`.

Every SAF write in the app is inside the two files the build guard's allow-list names.

### Build guard, strengthened

Writing the implementation found a hole in the guard: the write rule was anchored on the
literal receiver `contentResolver`, and `SafeReplacerAndroid` holds
`private val resolver: ContentResolver`. Aliasing the resolver would have walked straight
past it. The pattern now matches any receiver, and a fourth rule was added — opening a
user's file with a write mode (`"w"`, `"rw"`, `"wa"`) is now a build failure anywhere
outside the four writer files. It matches raw source, because the mode is a string literal
and the scanner blanks those.

## Applied: PRD, USER_JOURNEY, DESIGN_SYSTEM, SCHEMA, MONETIZATION, LAUNCH

Six new governing documents landed and were applied where they bear on code.

### SCHEMA.md → the model layer and the database

- **Ids are TEXT UUIDv7**, not autoincrementing integers. `core.model.Uuid7` generates
  them from an injected clock and randomness so the layout is asserted on a JVM rather
  than assumed. Version 7 because SCHEMA.md's hot indexes — `(status, est_saving DESC)`,
  `(state, expires_at)`, `(finished_at DESC)` — are appended to in id order, and a random
  v4 key would scatter those B-tree writes across the whole index on phone flash.
  **A real bug, caught by its own test:** a clock stepping backwards re-seeded the
  intra-millisecond counter randomly, which could hand out an id sorting *before* one
  already issued. It now holds the timestamp and counts up.
- `MediaItem` gains `folder_grant_id`, `mime`, `est_saving`, `created_at`/`updated_at`;
  `favourite` and `locked` move into `MediaFlags` as the bitmask SCHEMA.md specifies, with
  `locked` renamed `hidden`.
- `Job` gains the metrics BUILD.md § 14 asks to log: `run_session_id`,
  `stage_before_pause`, `ssim2`, `encode_ms`, `verify_ms`, `realtime_multiple`,
  `attempts`, `user_initiated`.
- `UndoEntry` gains `job_id`, `original_size` and `created_at`; `FolderGrant` gains
  `offload_ref` — the destination volume is written to, so it needs its own persisted
  grant, and offload is refused rather than guessed without one.
- `Predictor` gains `setting_var`. Confidence is now sample count **and** spread: the mean
  alone cannot tell a predictable family from one whose files merely average out.
- The SQLDelight schema was rewritten to match, including SCHEMA.md's indexes.

### DESIGN_SYSTEM.md → the palette, type, shape and motion

- `TrimPalette` is now the DESIGN_SYSTEM.md token table: dark default, mint accent,
  `accent-on`, `danger`, `warning`, `scrim`, and hairlines as `text` at 8%.
- **One deviation, and it is deliberate.** DESIGN_SYSTEM.md pairs the light accent
  `#16A37B` with white, which is 3.2:1 — below the 4.5:1 the same document requires for
  text, and a button label is text. The accent is kept exactly; only the ink on it changes,
  to the dark used on dark's mint, giving 5.3:1.
- New Compose-free `TrimType`, `TrimShape`, `TrimSpacing`, `TrimSpring` and
  `ReducedMotion`. The hero transition is now `spring-standard` with the corner radius
  going 4 → 0, superseding the reference prototype's duration-and-Bézier version; grid
  gutters close 2 → 1 → 0 as the grid zooms out.

### MONETIZATION.md → a testable entitlements policy

`core/domain/billing/Entitlements`: 3 GB freed per month free, Compress now five a day,
7-day undo retention against Pro's 90, and the Pro feature set. Three rules are structural
rather than conventional — **restore is never gated**, indexing continues after the cap,
and retention is clamped to the tier rather than rejected so a lapsed Pro user gets 7 days
instead of an error. A first file larger than the whole allowance is still allowed through,
because otherwise a user whose first video would save 4 GB never gets to use the free tier
at all.

### Verified

- **258 shared JVM tests**, all passing.
- **43 build-guard tests**, all passing, plus the guards run clean across all 74 source
  files in the repo.


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

### Added — milestone 3, probe + search + predictor

The search phase from BUILD.md § 5. ARCHITECTURE.md § 15 gives this milestone no platform
work, so it is all shared Kotlin and **all of it is tested** — 52 new tests, 177 in total,
0 failures.

- `WindowPlan` — one 5-second window from the middle, three for files over three minutes,
  three at start/middle/end for verification, scored at 720p. Windows never overlap and
  never run past the end of the file; scoring the same frames twice would weight them
  twice in the average.
- `SettingSearch` — binary search on bitrate for the cheapest setting that still clears
  the XPSNR threshold. Early exit to the low bound when the first probe is far clear,
  the top of the bracket tried before bisecting a range that may have no answer, a hard
  probe cap, and no bitrate ever probed twice. A file that cannot reach the threshold is
  reported as such rather than fudged — shipping something the user can see is worse is
  the one outcome the app must never produce.
- `Predictor` + `BitrateBucket` — the (device, camera, codec, resolution, fps, bitrate
  bucket) table. At 20 samples it narrows the bracket around a running mean; below that it
  moves the starting point only.
- `ProbeAndSearch` — ties them to `YuvSource`, `ProbeEncoder` and `QualityScorer`, and
  **decodes the source window once**, reused by every probe. That is the difference
  between a search costing one decode and one costing four.

Three real bugs the tests caught before they shipped:

- The early-exit path probed the low bound without checking the probe budget, so
  `maxProbes = 1` spent two.
- Convergence at 8% needed three probes inside a confident prediction's bracket, against
  BUILD.md's "1–2 probes with prediction", to win about 4% of bitrate. Now 12%.
- Two clips from the same camera minutes apart can straddle a bucket edge and be treated
  as different families. Inherent to bucketing, so it is documented in a test and in
  PROJECT.md rather than papered over.

### Added — milestone 2, the quality metrics

XPSNR and libvmaf now build from source behind the `trim_native.h` C ABI, bound to Kotlin
with JNI, and **both are verified against their upstream implementations**.

| Metric | Ours | Upstream | Source of truth |
|---|---|---|---|
| XPSNR y | 29.3297 | 29.3297 | FFmpeg's own `xpsnr` filter |
| VMAF | 63.8494 | 63.8494 | libvmaf's `vmaf` CLI (and FFmpeg's `libvmaf` filter, 64.1769 vs 64.1770 on the full clip) |

That is the whole point of the milestone: a metric that is fast and wrong silently ruins
every replace decision, so neither number was accepted until something that did not come
from us produced the same one.

- `shared/native/vmaf` and `shared/native/xpsnr` are now real submodules, pinned.
- `src/vmaf_score.c` — libvmaf, `vmaf_v0.6.1` embedded (no asset to read at runtime),
  single-threaded because ARCHITECTURE.md § 8 already owns the parallelism, cancellation
  polled between frames.
- `src/xpsnr_score.c` — a standalone extraction of the Fraunhofer algorithm. **The
  upstream repo has no standalone C**, contrary to what STACK.md and the `ndk-build` skill
  claimed; both are corrected. Luma only, which is what the search needs and what makes
  the value comparable to FFmpeg's "XPSNR y".
- `CMakeLists.txt` — drives libvmaf's meson build from a cross file generated out of the
  toolchain CMake resolved, so the two cannot drift onto different ABIs. Wired into
  `androidApp` via `externalNativeBuild`.
- `jni/trim_native_jni.c` — one bridge, `RegisterNatives` in `JNI_OnLoad`, direct buffers
  only, return codes rather than exceptions from C.
- `NativeQualityScorer` implements the shared `QualityScorer`, bridging coroutine
  cancellation onto the flag the native loop polls — without it a night pass told to stop
  would keep scoring until the current window finished.
- `test/test_metrics.c` — **16 checks, all passing**: the two golden values, identical
  input at the ceiling, monotonicity (the search cannot converge without it), and the full
  error contract (cancellation, null windows, mismatched sizes, invalid subsample).
- `calibration/` — the harness for PROJECT.md's open question, with a first data point:
  **VMAF 95 ≈ XPSNR y 39.8** under x265 on the golden clip. Explicitly not a shippable
  constant; the README says why in three points.

Two build traps worth knowing, both now documented in the skill: libvmaf needs `xxd` at
configure time or it builds *silently* without models and fails at runtime, and it bundles
libsvm in C++ so the link needs the C++ runtime.

### Added — milestone 8 completed

The rest of the gallery shell: sectioned grid with sticky date headers, pinch between
day/month/year densities, the fast-scroll date bar, Albums (including auto-albums),
Favourites, Recently deleted, the locked folder, and the viewer's info sheet.

**Verified — 113 JVM tests, all passing.** Everything that decides what the user sees is
deliberately free of Compose types so it can be tested without a UI toolkit: date
sectioning and header wording, scrubber arithmetic, auto-album rules, trash retention,
the lock gate, and the number formatting.

- `DateSections` / `GridZoom` — day/month/year grouping, relative headers ("Today",
  "Yesterday", the year dropped in the current year), a 1.35× pinch deadband, never more
  than one level per gesture. Undated items get their own section rather than vanishing.
- `FastScroll` — ticks spaced by *item position* rather than by section, so a holiday
  week that holds more photos than a quiet year does not bunch every label at the top;
  repeated labels collapsed; thumb ↔ index round-trips.
- `AutoAlbums` — screenshots, selfies, documents, videos, chat media. Filename beats
  label for screenshots; a screenshot of a receipt is not filed in Documents; a selfie
  needs the front camera, not a "portrait" label; locked items appear in no album at all.
- `TrashPolicy` — only "Free space" expires; countdowns round **up** so nothing
  recoverable ever shows zero; the subtitle tells the truth about where the original
  actually is ("On external storage", not "Kept").
- `LockedFolderGate` — backgrounding re-locks from any state, a two-minute session, and a
  cancelled prompt is not rendered as a failure.
- `MediaFormatting` — decimal units matching the phone's own storage figures, saved
  percentages rounded down, and null rather than a string when nothing was saved, so the
  app cannot claim a saving that did not happen.

Compose screens: `GalleryScreen` (sections, pinch, scrubber), `FastScrollBar`,
`AlbumsScreen`, `TrashScreen`, `LockedFolderScreen`, `ViewerInfoSheet`. Schema gains
`MediaItem.favourite`, `MediaItem.locked`, `Album` and `AlbumMember` — a deviation from
ARCHITECTURE.md § 4, recorded in PROJECT.md.

Still not compiled, for the same reason: Compose Multiplatform needs androidx artifacts
from Google Maven.

### Added — milestone 8, gallery shell (Compose Multiplatform)

The motion from `design/buyer-gallery/` is now ported into the app, in
`shared/core/ui` (design system + motion) and `shared/feature/gallery` (grid, tile,
viewer). Compose Multiplatform, shared by Android and iOS.

**Design system** — built on Compose foundation, **not Material 3**. BUILD.md § 9
specifies the look directly (dark by default, media on near-black, one typeface, one
accent colour) and Material would have to be overridden at every turn to reach it. The
palette is Trim Gallery's own; only the motion came from the reference, which is what the
reference was for.

**Motion, ported** — `MotionSpec` carries every timing (arrival 600ms with a 70ms
stagger, breathing at 4.6s, hero open 420ms / close 340ms, sheet 450ms after 120ms, veil
350ms, press 0.97), `HeroGeometry` the transition arithmetic, `TilePhase` the per-tile
offset. Modifiers: `breathing()`, `arrival()`, `pressScale()`. The grid → viewer
transition is a single 0..1 progress driving one interpolated rectangle, so the image
travels as one shape and an overshoot easing past 1 stays coherent. Drag-down dismissal
springs, per BUILD.md § 9.

**Verified — 39 JVM tests, all passing.** The timings, geometry, phase hash and palette
are deliberately Compose-free so they can be tested without a UI toolkit. The hero
geometry is asserted against the numbers *measured in a browser* from the signed-off
reference (354dp square at 390dp wide, centred, above centre), which makes it a check on
the port rather than a restatement of it. The palette tests assert WCAG contrast, one
shared accent, and that the page is near-black rather than black.

**A real bug, in both codebases.** The per-tile breathing phase used `hash * 31 + char`,
which gives sequential ids hashes ~31 apart — so neighbouring tiles landed 31ms apart in
a 4600ms cycle: distinct on paper, identical to the eye, defeating the entire point of
the offset. Now FNV-1a plus a murmur3 finalizer, fixed in the Kotlin port **and**
back-ported to the React reference. Caught only because the test asserted the phases
*spread*, not merely that they differed — the browser test had asserted distinctness and
passed.

Also fixed: `shared/feature/*` and `shared/core/ui` exposed Compose and model types in
public signatures through `implementation` dependencies, which no consumer could have
compiled against. Now `api`.

**Not compiled.** Every Compose Multiplatform version resolves `androidx.annotation`,
`androidx.collection` and `androidx.lifecycle` from Google Maven, refused by this
environment — the desktop target included, so there is no way to build the composables
here at all. The Compose-free half is verified; the composables are not.

**Still to come in milestone 8:** albums, favourites, trash (= undo bin), locked folder,
the fast-scroll date bar, and pinch between day/month/year grids.

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
