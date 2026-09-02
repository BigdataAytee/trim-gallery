# Changelog

## History and Settings

The last two of the five screens, and the end of the image loader the gallery brought with
it.

**Space is now History.** Same code, honest name. It was called Space when the app's job was
to report free space on a phone full of photographs; in a trimming utility the screen is a
record of what was changed and what was left alone, and the freed total is the headline
rather than the point. The artwork slot went with the gallery — a row that tried to show a
thumbnail it can no longer load is worse than a row that never offered one.

**An empty History says so.** A library nothing has run over yet looks exactly like one where
everything was already efficient, and both deserve a sentence instead of a blank page. The
journeys assert this state, not a populated one: a populated History needs a job row and an
undo entry, which needs a real encode, which the emulator cannot do.

**Settings gains the control that decides whether a file is recoverable.** "Keep originals
for" was in `Settings` and in `SettingsPolicy`'s warnings but had no control anywhere — the
one number that decides how long an original survives under *Free the space* was unreachable.
It steps 5 to 90 days, and the sentence under it says the thing a user needs to know: only
folders set to Free the space ever delete an original.

**Face clustering left the settings model.** `faceClusteringEnabled` and its warning were
about a feature that no longer exists in this build. A toggle for a deleted feature is a lie
the settings screen tells.

**Coil is gone.** Both dependencies, the `ImageLoader` set-up, the video-frame fetcher, its
key and its test. Nothing left in the app displays a photograph, so an image loader was a
few hundred kilobytes and one more thing that could crash on tap — which, on this project,
it did.

**Four journeys.** History opens and leads with the freed total; an empty History explains
itself; the retention stepper actually changes the stored value; Settings says which build
this is — the question every field report so far has turned on.
## Big files

The screen the utility exists for, and Home's "Find big files" now has somewhere to go.

**"Find big files" is `TriageStep.run`, not a second opinion.** Every estimate on the screen
is the Triager's own projection and every skip reason is its own verdict — the same
decisions the night pass acts on, written to the same rows. A screen computing its own
numbers would eventually disagree with the thing that does the work, and the user would be
right to believe neither.

**Each row says both numbers**, current size and what it could become. One alone is not a
decision: "380 MB" does not say whether it is worth a tap, and "saves 215 MB" does not say
what is being risked. The total is worded "could free about", because nothing has been
encoded or verified at that point.

**"Large but can't be trimmed" is not a consolation prize.** A scan that finds nothing
compressible still has to say where the storage went, or it wasted the user's time and
battery. The reasons and their wording come from `SkipList`, which already had them —
"Already efficient", "RAW files hold the sensor's own data", and the rest — grouped by
reason, because "HDR video" said forty times is a wall of text hiding the line that mattered.

**Trim runs one file at a time**, through the same `OptimiseController` the long-press flow
used, so the job row, the daily count and the undo entry all behave as they were already
tested to. Trim-selected and trim-all need a queue on that controller and land separately.

**Triage is real in the journey.** The step runs over fake files with declared sizes and
writes its own verdicts, so the suite asserts the actual decision path rather than a screen
fed prepared data. Only the bytes are fake, and the Triager never reads bytes.

**One stale comment corrected.** `TrimRepository.succeededJobs` still claimed nothing ever
writes a job row and that `VideoOptimiseStep` was unbound. Both stopped being true two
milestones ago, and History is about to depend on it.

## Home and Folders

The first two of the five screens, and the first emulator journeys since the gallery's went
with it.

**Home says three things and no more.** What has been freed, what the schedule is, and
whether there is one at all. The freed total is summed from recorded runs rather than kept
as a counter, for the reason the Space screen already gives: a counter drifts the first time
a run is killed mid-write, and a number the user cannot trust is worse than none.

**There is no "Find big files" button yet.** It is Home's primary action in BUILD.md § 9 and
it arrives in the same change as the screen it opens. A button that goes nowhere is the
thing this project has already written down as unforgivable.

**The overnight switch is a persisted setting, not a call to the scheduler.**
`Settings.nightPassEnabled` is new. `NightPass.sync` schedules when a folder is granted and
cancels when the last one goes, so a switch that only called the scheduler would be turned
back on by the next grant change — a control that silently undoes itself. Both existing
callers now pass the stored value: the app's start-up, and the moment a new folder is
granted.

**Folders is its own screen.** It was the first section of Settings, which was right while
this was a gallery and a granted folder was setup. In a utility it is the product. It gains
what it never had: removing a folder, and the "Scan my whole phone" entry — which opens an
explanation, never the system dialog. The last line of that explanation is the honest one:
you do not need this, everything works on the folders you add yourself.

**Seven journeys.** Home shows its three facts, the switch changes what it offers, and Home
reaches Folders; a granted folder lists its modes, Free-the-space is saved, removing a
folder takes it off the screen *and* out of the grants, and whole-phone access explains
itself before Android is asked. All on the fixture harness kept back from the deletion.

## The index comes out

Perceptual hashing, face clustering, search ranking, chat-media review, `IndexStep` and
`MlKitIndexer` are gone, with the `Indexer` interface and the `Label`, `FaceEmbedding` and
`TextBlock` model types. They existed to power search, people and duplicates, all of which
went with the gallery.

**`TrimRepository` no longer implements `IndexStep.Sink`.** The five write methods behind
it — labels, faces, text, hashes, indexed — and the float-embedding packer they used are
deleted. `fromHex` stays: another write path uses it.

**Two things were checked before cutting, not after.** `MediaStatus.INDEXED` is *written* by
the indexer and read by nothing — no triage rule, no query, no screen gated on it, so
removing the writer changes no behaviour. And `IndexStep` was registered in Koin but never
reachable from `NightRun`: it was built, bound, and never run, which is the same pattern
this whole project has been correcting.

**`MediaStatus.INDEXED` is kept even so, and the enum says why.** It is a persisted value:
rows written by an earlier install still carry the string, and an enum missing the constant
fails to parse them. Removing it is a migration, not a deletion.

**The index tables stay in the schema, deliberately.** Dropping `label`, `face` and
`text_block` means a SQLDelight migration against a database that already holds real job
history and undo entries. An unused table costs nothing; a bad migration costs a user their
originals. It goes on the next migration that has to happen anyway.

**Stale detekt baseline entries pruned.** Eight suppressions pointed at files deleted across
this pivot, and one — `TrimRepository`'s function count — was keyed on a signature that
included `IndexStep.Sink` and stopped matching the moment the supertype went. Dead config is
the same problem as dead code.

## Shelving what the gallery carried

The pieces that existed only to serve the gallery, out of the build: `core/ui/grid` (date
sections, fast scroll, pinch zoom), `HeroGeometry` with `MotionSpec.Hero` and
`GridZoomMotion`, the `album`, `memories`, `places`, `edit` and `lock` domain packages, and
the offline basemap reader. All of it is on `shelf/pre-pivot` at `90895cf`.

**`core/ui/motion` survives, minus the hero.** `pressScale` is used by `TrimApp`, and
`ProgressRing` by both the Space screen and the Optimise sheet. Deleting the folder would
have taken three working things with the one that had to go.

**`MotionSpecTest` lost three assertions and kept a fourth by re-anchoring it.** The hero
spring, the grid gutters and the overshoot check went with what they described. "The sheet
starts after the opening move but finishes with it in view" compared against
`Hero.OPEN_MS`, which was `ReducedMotion.DURATION_MS` — so it now compares against that
directly and still asserts the thing it was written to assert.

**The index pipeline is not in this change, deliberately.** Perceptual hashing, face
clustering and search ranking served the deleted screens and should go, but `TrimRepository`
*implements* `IndexStep.Sink`: removing them changes a supertype on the data layer, the
`indexed()` write path, the hash packing and Room columns. That is engine surgery, not
shelving, and it gets its own change for the same reason the startup wiring did — so that a
failure in it is unambiguous.

## This is not a gallery any more

Trim becomes a background file-trimming utility with a minimal UI. People already have a
gallery; what nobody else offers is a verified visually-lossless on-device encoder that
keeps the original recoverable. That is what this app is now, and the user's own gallery
becomes its front end through a share-sheet entry.

**Nothing in the engine changes.** The optimise pipeline, probe and search, the predictor,
safe replace, the undo bin and offload, the verify gate and its native metrics, the night
scheduler and every guard, triage and the skip list, and the photo path are untouched, along
with every test and build guard that holds them in place.

**Five screens replace the gallery**: Home, Big files, Folders, History, Settings — plus a
share-sheet target that trims one video and hands back a new file, never replacing the
sender's copy, because a `content://` URI from another app cannot be safely replaced.

**Big files reports what it cannot trim, and why** — already efficient, HDR, Motion Photo,
RAW, document, APK — so a scan that compresses nothing still tells the user where their
storage went. The reasons come from the skip list that already existed.

**Access is two levels and the first one is the whole product.** SAF grants alone run every
screen. Whole-phone scan sits behind an explicit "Scan my whole phone", needs a Play
file-management declaration, and may be rejected; nothing depends on it.

This change is documents only: BUILD.md, PRD.md and USER_JOURNEY.md rewritten to match, and
the pivot with its reasoning recorded in PROJECT.md. No code moves until the next change.
## The gallery is gone

`shared/feature/gallery` and the four empty feature shells beside it are deleted, along
with `GalleryHost`, `TilePreview`, `VideoPlayer` and the four emulator journeys that drove
them. The complete pre-pivot tree is on `shelf/pre-pivot` at `90895cf`, so nothing is lost.

**`HomeHost` keeps the wiring the gallery host was carrying.** The fast start from the
database, the folder walk, the library diff, `StartupGuard` and the recovery path are
byte-for-byte what shipped — only the grid they fed is gone, replaced for now by the two
numbers this screen can state honestly: how much is in the granted folders, and how many
files. What it does *not* do is guess a saving; that is a per-file question the probe and
predictor answer on the Big files screen, and inventing it here would be the "saves about
200 MB" that turns into 40 MB on the first screen anyone sees.

Splitting the screen from the wiring was the reason to do this on its own. All of that is
work the app begins by itself at launch over a persisted grant — the exact path that
produced a crash loop once already — and rewriting it in the same change that deleted the
gallery would have left any failure ambiguous between the two.

**The emulator suite is smaller, and says so.** Four journeys went with the screens they
drove. `assert-journeys-ran.py` now requires the engine suites and the launch check, and
carries a note saying that is less than yesterday rather than papering over it. The five
screens bring their own journeys as each lands. `JourneyFixtures`, `JourneyFakes` and the
test `ContentProvider` are kept exactly as they are: they depend only on the model, the
repository and the engine, so they are the harness the new journeys will be built on.

**Coil and the thumbnail path stay for now.** `SpaceHost` still draws thumbnails and
becomes History in a later change; removing them here would have meant rewriting that
screen in this one.

## The tap crash, found where the phone has it

Four builds crashed the moment a picture was tapped, and four builds of tap journeys stayed
green. This change is the test that made the emulator crash the way the phone does, the stack
trace it produced, and the one-line fix that trace named.

### What the emulator now does that it never did

`TapCrashReproductionTest` runs the **real `MainActivity`** — not `GalleryHost` in a bare
`ComponentActivity` — over a folder of nine files served as **`content://` documents** by a
test-manifest `ContentProvider`, loaded by the **production Coil `ImageLoader`**, with
eight-megapixel photographs rather than 64-pixel squares. It taps a photo tile, waits for
the viewer, drags it closed, and does that twenty times; then the same for video tiles. It
is a required suite: the job fails if either test is missing from the results.

It went red on the **first tap of both kinds**:

```
java.lang.IllegalArgumentException: Corner size in Px can't be negative(topStart = -0.13895845, topEnd = -0.13895845, bottomEnd = -0.13895845, bottomStart = -0.13895845)!
    at androidx.compose.foundation.shape.CornerBasedShape.createOutline-Pq9zytI(CornerBasedShape.kt:125)
```

### The cause

`MotionSpec.Hero.OPEN_EASING` is `Easing(0.2f, 0.9f, 0.25f, 1.1f)` — the second control
point sits above 1, so the open overshoots on purpose and the image lands with a spring.
`HeroGeometry.lerpRadius` interpolated from the tile's 4 dp to the viewer's 0 dp with no
clamp, so for a few frames past fraction 1.0 it returned a negative radius, and
`HeroViewer` handed that to `RoundedCornerShape`, which throws.

Every earlier tap journey ran `TrimTheme(reduceMotion = true)`, under which `HeroViewer`
snaps its progress to exactly 1.0 and never overshoots. `MainActivity` reads the system's
animator scale and gets `false` on any normal phone. That is the whole difference between
green CI and a crash on every tap.

### The fix

`lerpRadius` clamps at zero, at the source rather than the call site: the frame's width and
height were already clamped at the call site for the same overshoot and the radius was
missed. Three JVM tests pin it — the easing must keep overshooting (so nobody "fixes" this by
flattening the curve), the radius must never go negative past 1.0, and it must still
interpolate normally inside 0..1.

### The second API level found a bug on its first run

`TilePreview` and `VideoPlayer` both built their ExoPlayer inside `remember` and configured
it there — during composition, on whatever thread composition happens to be on.
`ExoPlayer.Builder` takes the current thread's looper, and a thread without one falls back
to main, so the player expects main while the calls come from a Compose worker:

```
java.lang.IllegalStateException: Player is accessed on the wrong thread.
Current thread: 'DefaultDispatcher-worker-4'   Expected thread: 'main'
    at androidx.media3.exoplayer.ExoPlayerImpl.verifyApplicationThread(ExoPlayerImpl.java:3102)
    at app.trimgallery.ui.TilePreviewKt.TilePreview(TilePreview.kt:35)
```

Both now state `setLooper(Looper.getMainLooper())`, configure the player in a
`LaunchedEffect` on `Dispatchers.Main`, and post `release()` to main rather than calling it
from the applier thread. Compose makes no promise about the composing thread; Media3 asks
that every access be on one thread. Saying which one is the whole fix.

It passed on API 34 and failed on API 36, which is the argument for the second device.

### Two API levels, and a check that requires both

The emulator was API 34 and the phone the field reports come from is not. A second managed
device runs the same suite at **API 36**, and CI runs both in one invocation with
`--continue`, so a failure on one still reports the other rather than hiding it.

The results check counts per device now. A journey has to have run and passed on *every*
listed device, and the device is named in the failure line, so "it passed on one of the two
API levels" is an answer the job refuses rather than reports as green. A device whose
emulator never booted is a failure too, not a quietly smaller matrix.

### Failures now say what failed

The instrumentation result check appends one line per failed or missing journey to the
smoke log, and the failure summary hoists those lines into the head of the job log and into
`::error::` annotations. Four CI cycles were spent reading around a log window before this;
that does not happen again.

## Optimise, on one file, because you asked

The first place a person can make this app change a file. Everything under it was built and
reachable only by a night pass nobody watches; long-press a video and it is now a thing you
can watch happen, decide about, and undo.

Long-press a tile → a sheet with the estimate and Start → a progress ring on the tile itself
→ **"Now 165 MB (was 380 MB)"**, with Keep it and Undo.

### Undo is offered only when there is something to undo

The rule the whole screen is built around, and the one thing tested from every angle. The
replace parks the original and writes an `UndoEntry` naming where it went; that reference is
what a restore needs. A run that skipped or failed replaced nothing, so it offers no Undo and
says what happened instead. A button that appears to work and does not is a bad thing on any
screen and an unforgivable one here, where the user is deciding whether to trust this app
with the rest of their library.

Undo looks the entry up by media id rather than carrying it from the result, so it still
works if the process was killed between the replace and the tap.

### The run is recorded, so Space can show it

An optimise now writes a `job` row before it starts and closes it out afterwards. Two things
depend on that and both would have been quietly wrong without it: the free tier's five-a-day
counts `job` rows with `user_initiated = 1`, and Space and History read the same table. A tap
that freed 215 MB and left no row would have reported nothing on the screen the user checks
first.

The row is written *before* the encode, not after. The battery is spent whether or not the
user lets it finish, so counting completions would let someone who cancels at 99% run it all
day.

### What the sheet will not say

The estimate appears only where something measured one. `CompressNow.Estimate` leaves both
numbers nullable exactly so a sheet with no prediction can say how big the file is and offer
to start, rather than inventing a saving the result will contradict. Progress starts as
*unknown* rather than as zero, because a bar pinned at 0% and a bar that has not started look
identical and only one of them is honest.

### Why it ends on Keep / Undo rather than § 6's three buttons

USER_JOURNEY.md § 6 ends on Share / Replace original / Keep both — three choices made before
anything is written. This ends on Keep it / Undo, which is the same safety with the order
reversed: `VideoOptimiseStep` replaces as the last step of a verified chain and parks the
original rather than deleting it, so Undo is a real restore. Building § 6's version would
need a second write path that holds a finished encode unreplaced. The shape the field report
asked for is the one the engine already supports; Share and Keep both are still open, and
recorded as such.

### The emulator test caught a real one

The sheet was building "Was 380 MB → now 165 MB" from the item's own `size` — the number a
library *scan* recorded — rather than from `result.wasBytes`, the `storage.stat()` snapshot
the step takes immediately before the encode. They are both "how big it was" and they
disagree: a scan can be minutes old, and a file the user has just edited is exactly the case
where it is wrong.

That number is not decoration. The sheet shows it, the `job` row stores it, and Space
subtracts it from the new size to report what was freed — so a stale original size is a wrong
saving on the screen this whole feature is judged by. Both now use the measured value.

### Tested where it can be

`OptimiseFlow` is unit tested on the JVM as the sentences the sheet must never be able to
say. Five emulator journeys drive the real screen over a **faked** step — the hold, the run,
the recorded job, the missing Undo after a skip, and the restore. The step is faked because
it has to be: the CI image has no hardware encoder and rule 2 forbids the software one. What
the journeys prove is everything between the finger and the encoder, which is where this
change lives. The encode itself is proved on a phone.

## The night pass can now change a file

Every part of the encode path existed, was tested, and was unreachable. `NightWorker`
resolved `NightRun.Step` from the graph and **nothing provided one** — so a night that
actually woke would have failed looking for it, in a worker, with no screen, at 3am. The
symptom would have been "the app never optimises anything", not a crash anyone could see.

That binding is now in place, along with `VideoOptimiseStep`, `VerifyPass`, `Verifier` and
`OptimiseFacts`. From here, a charging phone at 3am will replace originals with smaller
copies on its own, with nobody watching. That is the thing to be careful about, and it is
why this change does only this.

### What makes that safe was already built

Nothing new guards it, because the guards were written first and have been asserted all
along: `VerifyPass` steps up at most twice and refuses anything below the VMAF bar, the size
and mtime snapshot taken before the encode is re-checked after it, a `ReplacePlan` can only
be issued by `VerifyPass.Result.Ready`, and `Replacer` is the only component in the app that
may write to a granted folder — a build guard enforces that last one. `VideoOptimiseStepTest`
is written as the ways a file could be lost, each counting the plans a fake `Replacer` was
handed.

### A wiring test, because this class of bug has no other catch

A missing Koin definition compiles, passes every unit test, and fails at runtime. Every unit
test in this project constructs its subject directly, which is exactly the step a graph
skips. So `NightWiringTest` asks the assembled graph, on a device, for **the same seven
definitions `NightWorker.doWork` resolves, in the same order** — and for `VideoOptimiseStep`,
whose seven constructor arguments each have their own. If one goes missing again, the failure
names it in CI rather than overnight on someone's phone.

## The encoder half of the search

`ProbeEncoder` was the last engine with no implementation on any platform. The search asks
"how good does this file look at 4 Mbps?", and the only way to answer is to encode at 4 Mbps
and measure — so milestone 3's search, complete and unit tested against fakes, could not run
a single probe. `ProbeEncoderAndroid` is that half.

It encodes one cached window at one candidate setting and hands back the **decoded** result,
because XPSNR compares pictures and a bitstream is not an answer. Encoder and decoder are
pumped in one loop: no muxer, no container, no temp file. A search does about twelve probes
per file, and writing and re-reading an MP4 for each of them would add a write, a read and a
container parse twelve times over to answer a question that has its input already in memory.

### The search was measuring the wrong encoder

Building this exposed a real defect in code that was already merged and green. `ProbeEncoder`
took a window and a setting and nothing else — so the probe had no way to know which codec
the file was going to be encoded in. `CodecChoice` picks HEVC or AV1 per file, and the two do
not sit on the same rate-quality curve: a bitrate bisected on HEVC means something else on
AV1. Nothing would have failed. The search would have returned a plausible number and the
file would have been encoded at the wrong quality.

Frame rate had the same hole. A bitrate is bits per *second*, so the same number is half as
many bits per frame at 60 fps as at 30, and a probe run at a nominal rate mis-prices every
high-frame-rate clip on the device. Both are now arguments, threaded from the same
`CodecChoice` decision that configures the final encode, with tests that assert the probe
encodes in the codec the file will be encoded in, at the file's own rate.

### Hardware only, and what that means when there is none

The encoder comes from `MediaCodecFactory.probeEncoder`, which offers hardware encoders and
nothing else. When a device has no hardware encoder for the codec, the probe returns an
**empty window**: it scores as unusable, the search ends in `NotReachable`, and the file is
skipped with a reason the user can read. That is the whole of the error path — there is no
software branch to fall into (BUILD.md § 2 rule 2).

Frames are padded out to the encoder's alignment on the way in and cropped back on the way
out, because some encoders will not take 1278 pixels wide and configuring one that way throws
rather than rounding. The padding replicates the edge rather than filling with black: a hard
black border costs real bits, and those bits would come out of the budget being measured.

### What the emulator can and cannot prove

An ATD image has no hardware encoder at all, so **no test here claims an encode was performed
in CI.** What it does assert is the rule itself: the outcome must match what the device says
it can do — an empty window where there is no hardware encoder, real frames where there is.
A CI machine that lacks the hardware is exactly the machine where a software fallback would
be tempting and would go unnoticed.

The encode assertions — same size as the source, a picture rather than the input handed back,
and more bitrate landing closer to the source — stand down on that image. They are named as
*not required* in the journey checker rather than pretended over, and the checker now prints
which tests stood down, so a green tick never reads as "everything was proved here".

## The decoder the quality gate reads through

`YuvSource` had no implementation on any platform. `ProbeAndSearch` needs it to score
candidate settings and `Verifier` needs it to compare an encode against its original — so
milestone 3's search and milestone 4's VMAF gate were both written, unit tested against
fakes, and unable to run. **The check standing between a video and a worse copy of it had
nothing to decode with.**

`YuvSourceAndroid` decodes a window with `MediaExtractor` and `MediaCodec`, keeps the frames
whose timestamps fall inside it, and scales each plane to the measurement size.

### Two details that would have been wrong quietly

A decoder hands back frames in whatever layout the hardware prefers, and `rowStride` and
`pixelStride` are rarely 1: rows are padded to alignment, and chroma commonly arrives
interleaved with U and V sharing a buffer. **Read as if packed, such a plane produces a
picture that is sheared or half the wrong colour — and it still scores.** `YuvScale` reads
the strides the decoder reports, and its tests are written as those two mistakes.

The resampling is a box average rather than nearest-neighbour. Dropping rows and columns
aliases fine detail into noise, and the entire purpose of the number being computed
downstream is to notice when detail has been lost; a scaler that invented its own artefacts
would have them measured as the encoder's.

### It is proved on a real runtime, which its sibling cannot be

The instrumented test decodes the golden clip on the emulator: real `MediaCodec`, real
extractor, real plane layouts. It asserts the planes are exactly the size the metrics will
read them as, that the frames hold a picture rather than correctly sized zeroes, and that a
window at two seconds is a different picture from the one at zero — which is what proves the
seek moved, rather than scoring one file three times on its opening second.

That is possible because BUILD.md § 2 rule 2 bans software *encoding*, not decoding. The
encoder half has no such luxury: an ATD image has no hardware encoder, so `ProbeEncoder`'s
first real exercise will be a physical device.

Creating a decoder now goes through `MediaCodecFactory` like every other codec. Not for the
hardware-only rule — a software decoder is fine — but for priority: the codec-priority skill
is explicit that a realtime-priority decoder feeding a background encoder still holds the
slot a foreground camera wants.

## The encode path exists, and what it is still waiting for

`NightRun.Step` had no binding. The night pass was scheduled, woke, and failed on its first
`get()` — so **nothing in this app had ever optimised a file**, and nothing would have,
however many nights it ran. Every piece of the chain had been built and unit tested across
six milestones; the assembly had not.

`VideoOptimiseStep` is that assembly, and it reads as the safety contract it implements:

1. choose the output codec, or skip — never a software encoder (BUILD.md § 2 rule 2)
2. **snapshot size and mtime, before any work**
3. search for the cheapest setting that should clear the quality bar
4. encode the whole file, verify VMAF on three windows, step up at most twice
5. confirm it is smaller, and that the original has not moved
6. and only then hand a `ReplacePlan` to the one component allowed to write

It cannot skip step 6's gate even by mistake: a `ReplacePlan` is issued **only** by
`VerifyPass.Result.Ready`, and `Replacer` is the only writer in the app, which a build guard
enforces. Nothing in the step opens a user's file for writing, and nothing deletes an
original — the original is *parked* by the Replacer at replace time.

### The tests are written as the ways it could lose a file

The happy path gets one test. The refusals get the rest, and each one counts calls to a
faked `Replacer`: "the Replacer was never called" is the strongest assertion available,
because it is the only component that may write to the library. A device with no hardware
encoder, a file no setting can encode well enough, a file the user edited mid-encode, an
original that vanished, an unreadable container — none of them may reach it. A rolled-back
replace must teach the predictor nothing, because a setting that verified and then failed to
commit is not evidence about a file that is not on disk.

### What it is waiting for, stated plainly

**It is not bound, and the night pass still cannot run.** Two engine-api ports have no
implementation on any platform:

- `YuvSource` — decodes a probe window, and decodes the output back for the VMAF comparison
- `ProbeEncoder` — encodes one cached window at one setting during the search

Nothing implements either, on Android or iOS. That is not only the search's problem: the
verifier needs `YuvSource` too, so **the VMAF gate — the check standing between a video and
a worse copy of it — has never had a platform implementation either.**

Binding the step over `get()` calls for types nobody provides would move the failure from
"this is not wired" to "the night pass throws at 3am", which is strictly worse. So it stays
unbound until those two engines exist and have been run against a real encoder.

## The crash report you can reach when the app will not start

Three field reports asked for a stack trace. None arrived, and the reason was structural
rather than anybody forgetting: **Export diagnostics lives inside the app, and the app is
what is broken.** A tester whose launcher says "Trim Gallery keeps stopping" has no route
to it at all.

**There is a second launcher icon now, "Trim Diagnostics".** It shares no code with the
gallery: it resolves nothing from the dependency graph, opens no database, starts no scan,
mounts no gallery and reads no media. It constructs four objects that each take a `Context`,
reads the files the crash handler already wrote, and offers them to the share sheet. It is
not the pretty part of this app; it is the part that has to work on the day nothing else
does.

### The startup guard covered the wrong span

The field report asked for a recovery screen when "the previous launch crashed **before the
first frame**". What was built bracketed the folder scan alone — and that bracket begins
*inside* the composition. Everything ahead of it was unguarded: the Koin graph, the
Activity, the first composition, the `koinInject` calls, reading the platform's list of
granted folders. A crash there killed the process leaving no mark, so the next launch did
the same thing. That is the loop, and the guard could not see it.

There are two marks now. One spans `MainActivity.onCreate` to **the first frame drawn**;
the other spans the scan, which outlives that frame. Either one left set opens the recovery
screen. The first frame is detected with a pre-draw listener rather than `onResume`, and
the difference is the point: `onResume` runs before the content has been measured, so an
Activity that reaches RESUMED and then dies composing would clear the mark on its way past.

`Application.onCreate` no longer takes the process with it either. A throw while building
the graph happens before any Activity exists, so it kills every launch with no screen ever
shown — the worst shape of the loop, the one where the app cannot say what happened. The
graph and the image loader are both wrapped; a failure is recorded and reported on screen.

### The test gap that let it ship

Two instrumented suites covered this app and between them they missed the only
configuration that ships. `MainActivityLaunchTest` runs the real Activity — but an emulator
has no persisted folder grant, so the scan never starts and it asserts that an empty app
reaches RESUMED. `GalleryJourneyTest` exercises the granted path thoroughly — but hosts the
gallery in a bare `ComponentActivity` with fakes, and never runs `MainActivity`, `TrimApp`,
or the real graph.

**No test had ever run the real Activity with a folder granted**, which is what a phone
does on every launch after the first. `MainActivityGrantedLaunchTest` does, over the real
Koin graph, with only the two things an emulator cannot supply replaced. The CI guard that
reads the instrumentation results back now requires it too.

## Screens that prove they work, on a device

Four builds went out green while the first screen was broken. The only instrumented test in
this repository asserted that the Activity reached RESUMED — which it did, on a build that
crashed the moment a folder was granted. Reaching RESUMED says the process came up; it says
nothing about what is on the screen, or what happens when it is touched.

**So the screens are now tapped, on the emulator, in CI.** `GalleryJourneyTest` runs the
journeys a person actually makes:

1. Grant a folder → the grid renders (and the grant row is written before any media row
   points at it, and the night pass is scheduled).
2. Tap a photograph → the viewer opens.
3. Tap a video → it plays. Not "the viewer opened over it": the player has to reach READY
   and actually be playing, over the golden clip.
4. Relaunch after a grant → the photographs are there **before** anything is walked. The
   walk is held shut for the second launch, so a tile on screen can only have come from the
   database — which is the fast start, and it is where the crash loop always died.
5. A startup that does fail → the recovery screen, with the mark left set so the next
   launch does not repeat the work that just died.

Three things make these worth having rather than decorative:

**The database is the real one.** Opened through `AndroidSqliteDriver` and its callback, so
`PRAGMA foreign_keys = ON` is in force exactly as it is on a phone. The crash loop was a
foreign key that the JVM test driver leaves off; a journey test running against a permissive
database would certify the same bug a second time. Held in memory, so a run leaves nothing
behind.

**Only the platform edges are faked** — the system picker's grant, a tree of the user's own
files, WorkManager. Everything else is the app: the picker's result handling, the grant row,
the scan, the diff, the grid, the viewer, ExoPlayer. A journey test that fakes the middle
proves only that the fakes agree with each other.

**A green job is checked for having actually run them.** `pixelSmokeAndroidTest` is green
when it runs zero tests, when a filter excludes a class, and when every test skips. So the
job now reads the instrumentation's own result XML back and fails unless each journey is
there and passed.

The tiles, the grid and the viewer carry test tags (`GalleryTestTags`) so a test can name
what it means rather than count nodes. The `ComponentActivity` the Compose rule needs is
declared in the **smoke** variant's manifest only — the CI-only build — so no installable
build has it.

## The folder picker opens where the system opens it

It used to pass `EXTRA_INITIAL_URI` pointing at `DCIM/Camera`, on the reasoning that the
ordinary path would then never meet one of the three folders Android blocks. What that
actually did was drop the user *inside* one folder: the hint sets where the picker starts,
and starting deep is indistinguishable from being trapped there for anyone whose photographs
live somewhere else.

The hint is gone. The help sheet already covers the blocked-folder case, and it does it after
the fact — when the user has actually hit it — rather than by narrowing what they can choose
in advance.

The sheet loses a button with it. There were two, "Open DCIM/Camera" and "Choose a different
folder", and the first only differed because it passed the hint. Keeping it would be a label
describing something the app no longer does, so there is one button now: "Choose a folder".

`FolderChoice.cameraFolderHint` and the two constants that fed it are deleted rather than
left unused.

## The crash loop, and the test environment that certified it

Granting a folder killed the app, and every launch afterwards killed it again. Clearing app
data was the only way out.

**The cause is a foreign key.** `media_item.folder_grant_id` references `folder_grant(id)`,
and `AndroidDatabase` turns foreign keys on because SCHEMA.md's cascades are inert without
them. Nothing wrote a `folder_grant` row when a folder was granted — `saveFolderGrant` only
runs when somebody picks a mode in Settings — so the first scan inserted media rows pointing
at an empty table and SQLite refused. The grant was already persisted by then, so the next
launch read it, rescanned, and died the same way.

Underneath it is one design error: **two identities for one folder.** `GrantedFolders` uses
the tree URI as a grant's id and argues, in its own comment, that minting a UUID "would
create a second identity that has to be kept in step with the first" — and then
`saveFolderGrant` minted exactly that. A row keyed on a UUID can never match a scan keyed on
a URI. The tree URI is now the id in both places, and `recordGrants` writes the row when the
grant is taken rather than when someone happens to visit Settings. It runs on every launch,
so a phone already stuck in the loop repairs itself.

### Why the tests were green

`JdbcSqliteDriver` does not set `PRAGMA foreign_keys = ON`, and SQLite defaults it off. Seven
tests inserted media rows with a dangling grant id and passed, because the constraint that
fails on a phone was not being enforced in the test.

Every test now builds its database through one helper that turns foreign keys on. **A test
environment more permissive than production does not merely miss bugs; it certifies them.**
`scanningWithoutRecordingTheGrantIsRefused` asserts the constraint is live, so if anyone ever
turns it off again the suite says so rather than going quietly green.

### A crash loop is now impossible, not just unlikely

Two mechanisms, because they fail differently.

`StartupGuard` records when the app begins work it started **by itself** and when that work
finishes. A launch that finds the mark still set does not start the work again — it opens a
recovery screen that shows the crash, offers Export diagnostics, and lets the folder grant go.
`commit()` rather than `apply()`, because the process is about to run the thing that may kill
it and a write still queued when it dies is a mark that was never made.

And the startup work is wrapped: an exception inside it becomes that same screen in the same
launch, so the common case never reaches a crash at all. Cancellation is re-thrown and clears
the mark — leaving the screen is not a failure — and everything else does not clear it,
because it is exactly the failure the guard exists for.

The screen starts nothing. "Try again anyway" is there and is never taken on the user's
behalf.

## Video tiles show a frame, and no tile is ever black

The Coil setup was not missing anything — `VideoFrameDecoder` is registered and works. The
problem is what it has to do to reach a SAF document: Coil's fetch pipeline materialises the
stream into a temp file so `MediaMetadataRetriever` has something seekable, which means
**copying the whole video to draw one tile**. On a real library that does not fail loudly,
it simply never finishes, and the tile stays black.

Videos now take their own path, asking for the cheapest thing first:

1. `DocumentsContract.getDocumentThumbnail` — the provider usually has one already and
   returns it without opening the video at all.
2. `MediaMetadataRetriever` over a **file descriptor** — not a path and not a copy, so it
   reads a header and one GOP rather than a gigabyte.

Either result is cached to disk as JPEG, written to a temporary name and renamed so a
process killed mid-write cannot leave a truncated file for the next launch to decode.

Four extractions at once, no more. Each holds a hardware decoder, and a grid flung through
two hundred tiles would otherwise ask for two hundred — the same "the foreground wins the
hardware" concern BUILD.md § 2 raises for the night pass, pointed at ourselves.

**No tile is black at any point.** Every tile, photo or video, is filled with the card
colour before anything loads, and the picture fades in over it. A video with no obtainable
frame stays that colour: a plain empty tile is honest, a black rectangle reads as a broken
photograph.

It deliberately does *not* keep the previous item's picture while a recycled tile loads.
That would draw the frame of a different video with nothing on screen to say so, which is
worse than showing nothing.

The cache key is split into its own object so it can be tested without a device — five
cases, because its failures are the silent kind: a collision draws one video's frame on
another's tile, and an over-specific key caches nothing and re-extracts forever.
## The app says which build it is

Twice now a field report has had to be answered by comparing APK file sizes on a release
page, because there was no way to ask the app what it was. That is the wrong first question
to be stuck on: after "it crashed", the next thing anyone needs to know is whether the fix
for the *last* crash was even in what they installed.

`Trim Gallery 0.1.0 (1) · a03262a` now appears in Settings, and at the top of the
diagnostics export where it arrives attached to the stack trace it explains.

The commit comes from `GITHUB_SHA` in CI and from `git` for a local build, and **neither may
fail the build**: the whole lookup is wrapped and falls back to `unknown`. A version string
is not worth a red pipeline, and a build that admits it cannot name its commit is still more
useful than one that would not compile.

Two traps in `androidApp/build.gradle.kts` this walked into and out of, both of which fail
*configuration* and therefore every job in the workflow, including the ones that never touch
Android:

- `const val` at a `.kts` top level is a script compilation error — the file already carries
  that lesson next to `smokeX86Property`, and it applies to a second constant just as well.
- Statements **above** `plugins { }` are compiled without the Project API, so `providers`
  does not exist there. `smokeX86Property` sits above it only because a string literal needs
  nothing. The commit lookup sits below.

No timestamp, in Settings or in the file. `Diagnostics` bans absolute times from the export
because when the app ran says when its owner sleeps; a build date invites the same question,
and the commit answers it better anyway by saying exactly what the code was.
## The grid comes from the database, not from a folder walk

The app rescanned every granted folder on every launch and could not draw a single tile
until that walk produced its first two hundred items. Nothing was persisted, so it did the
whole thing again the next time.

Now the first thing that happens is one indexed query. The rows were written the last time
the app ran; the grid is on screen before any folder is opened. The walk still runs — in the
background — and its result goes through `LibraryDiff`, which has been written and tested
since milestone 6 and had no caller on this path.

Three things that were quietly expensive, all gone:

- **A transaction per row.** A first scan of fifty thousand files was fifty thousand
  commits, and SQLite fsyncs on every one. One transaction for the whole diff.
- **A full re-sort per batch.** The host sorted the entire list every time the scan
  published two hundred more items — O(n log n) on a list that only grows. The order is now
  `ORDER BY COALESCE(taken_at, mtime) DESC` in SQL, against the column's own index. The
  fallback is the same one the Kotlin did, and it matters: nothing is dated until it is
  indexed, and `taken_at DESC` alone puts the whole library in one block, because SQLite
  sorts NULLs last in DESC.
- **Rewriting rows that had not changed.** `unchanged` is most of the library on most
  nights and is not written at all.

Seven tests over the persisted library, including the one that guards the worst outcome: a
file whose optimised copy is gone but whose **original is still in the bin keeps its row**,
because that row is the only handle on a file the user can still restore.

### The benchmark, and what it can and cannot say

`StartupBenchmark` measures cold and warm start separately — they fail for different
reasons, and a slow warm start is specifically work being repeated.

**No before-and-after numbers are reported here, because none were measured.** Nothing in
this environment can build an Android app, and CI does not run Macrobenchmark either — it
needs a device. Quoting numbers from a benchmark nobody ran would be worse than quoting
none. The benchmark is in place for a real phone with a real DCIM folder, which is the only
measurement that would mean anything anyway: an empty install starts fast whatever the code
does.

### MediaStore was not adopted, deliberately

The report asked for MediaStore metadata instead of SAF. Two facts got in the way, and the
second is a decision that is not mine.

The scan does **not** open every file: it is one cursor per directory, and the columns come
back with the listing. So the win from MediaStore is not "stop opening files" — it is one
query for the whole library instead of one per folder, which is real but smaller than it
sounds.

And reading MediaStore needs `READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO`. PROJECT.md § Access
says SAF grants by default and all-files access is a later opt-in, on Play policy and
privacy grounds. Adding a media-read permission changes what the app asks for on first run
and what its privacy story is. That is a product decision, so it is recorded as an open
question rather than taken quietly inside a performance change.

## The two tables the night pass writes to

`job` and `predictor` have had a schema since milestone 3, queries written for them, and no
caller on either side. Both now have one.

**`job`** is what an attempt did: the setting it settled on, the probes it spent, the VMAF
it reached, the two sizes, the encode and verify times, the thermal readings at each end
and the energy — BUILD.md § 14's per-file metrics, and the row the History screen lists.
The write replaces rather than appends, so a night killed mid-file leaves the last state it
reached instead of nothing; two rows for one attempt would double-count the file in every
total on the Space screen.

**`predictor`** is what makes the second night on a phone cheaper than the first. A family's
running mean and variance, keyed including the *output* codec — AV1 reaches the same quality
at about two thirds of HEVC's bitrate, so a table without it would average the two and
predict a number wrong for both. `Predictor.learn` does the arithmetic and is already tested;
this is only its memory between nights.

Nine tests against the shipped schema, including the ones that would catch a silent drop:
every field the field test is scored on surviving a round trip, a second result folding into
the same family's mean, two output codecs staying apart, and a job state this build does not
recognise reading as `FAILED` rather than as a completed replacement.

**This does not make the night pass work.** `NightRun.Step` still has no binding, so nothing
calls either of these on a device yet. It removes two of the three things that were missing;
the third is `VideoOptimiseStep` itself, which is the code that parks and replaces originals
and is being held for review rather than merged alongside the plumbing.

## Space, and the crankshaft that is missing

The screen that answers "is this app worth having on my phone?" — total freed, a progress
ring while a run is working, what changed with Restore, what was left alone and why, and
the energy it cost. Reached from a second pill over the grid.

None of its arithmetic is new. `SpaceScreen`, `History` and `SkipList` were written and
tested in milestone 10 and had never been mounted; this is the Compose composition over
them plus the reads that feed it. In particular the Restore button is drawn from
`History.isOneTap`, not from whether an undo row exists — a file offloaded to a card in a
drawer has an entry and cannot be restored by a tap, and a button that appears to work and
does not is the specific failure that screen exists to avoid.

### The night pass has no step to run

Found while wiring History to the `job` table: **`NightRun.Step` is not bound.**
`NightWorker.doWork()` resolves it from Koin and nothing provides it — the module has a
comment where the binding should be, saying the assembly "is not written yet, and binding a
half-built one would be worse than binding none".

That was true when nothing scheduled the worker. Since the night pass acquired a caller it
is no longer harmless: the worker now runs, throws on the first `get()`, and WorkManager
records a failed attempt. The run-attempt count in the diagnostics export is where it
shows.

So `job` has no INSERT anywhere in this project — the same table-with-no-writer shape
`folder_grant` had — and History is empty on every device until the assembly exists. The
screen says "Nothing has been optimised yet" rather than showing a blank list that reads as
a bug, and `hasNoHistoryUntilSomethingRecordsAJob` is the test that should start failing the
day that changes.

Every piece of the assembly is built and tested: the probe and search, the encoder, the
VMAF verifier, the safe replace, the predictor. What is missing is the thing that puts them
in order. **"Compress now" needs the same assembly** — it is the same pipeline on an
explicit tap — so it is the next piece of work rather than the third screen.

### What the reads had to add

Six queries against tables that already existed: every run session rather than only the
last, the sum of `est_saving` over the queue, the candidate count, every undo row whatever
its state, and the succeeded jobs. `selectSucceededJobs` names its five columns instead of
`SELECT *`, so the mapper is honest about what History actually depends on; the other
nineteen belong to the field test.

`SpaceReadsTest` covers them against the shipped schema, including a stop reason no build
recognises reading as absent rather than throwing — the same totality the folder mode got.

## Settings, and a way to reach it

There was no way to change anything. The app read one folder, ran on defaults, and had no
screen that could say otherwise — which is what "no way to optimise anything" comes down to
first.

Settings is now a screen: the granted folders with a mode each, the quality target, whether
to wait for a full battery, and the nightly limit. It is reached from a pill over the
top-right of the grid, and the system back gesture closes it. Two screens do not need a
navigation library, so there is a boolean in `TrimApp` instead.

### The folder modes, and which of them can lose a file

They are not three equal radio buttons. *Keep originals* is always available and never
removes anything. *Free the space* says, in the row rather than a help page, that it deletes
originals for good after the retention period — in the warning colour, because BUILD.md § 6
requires that consequence shown and "Free space" as a label tells nobody what it costs.
*Move originals to another drive* names the drive it would move them to, and is refused
outright when there is no second grant on a different volume: a folder on the same disk
frees nothing, and offering it would be a lie about where the space came from.

**The choice is stored but not yet obeyed.** The night pass still treats every folder as
Keep, which is the safe direction — Keep never removes an original — but choosing Offload or
Free does not change what runs tonight until the replace path reads the mode. Recorded in
PROJECT.md rather than left to be discovered.

### Where the choice lives

`folder_grant` has been in SCHEMA.md since milestone 4 with `platform_ref` unique, a mode, a
display name and an offload target — and exactly one query against it, a SELECT, with no
writer anywhere. Nothing had ever written a row.

It has a writer now, keyed on the tree URI, which is the identity the platform and the
database already share. The platform stays the source of truth for *which* folders are
granted — persisted URI permissions survive a cleared database and are the only thing that
decides whether a scan succeeds — and the row says what to do with the originals inside one.
A row left behind by a revoked grant simply never joins, so re-granting a folder restores
the mode chosen for it.

### Two things found on the way

**The folder-help sheet was being drawn underneath the gallery.** Compose draws siblings in
the order they are emitted, and the sheet was emitted first, so the grid's opaque background
painted straight over it. The message added last week could not have been read by anybody.
It is now emitted last, inside a Box, and both screens that ask for a folder go through one
`rememberFolderPicker` — the alternative being two copies of the take-grant, schedule and
help logic, one of which eventually forgets a step.

**CHANGELOG.md had conflict markers committed in it**, from a merge in the ktlint change.
Removed, keeping both sections.

`DiagnosticsButton` has moved to Settings, where LAUNCH.md § Support puts it. It is one
screen away from the gallery rather than on it, which is enough for the crash it is for: a
viewer that dies on a tap leaves the grid rendering perfectly on the next launch.

## The night pass is scheduled

`NightScheduler.schedule` was written in milestone 5 — periodic request, the four
constraints, linear backoff, unique work under `KEEP` so a late settings change cannot push
tonight's window past morning — bound in Koin, and **never called by anything**. Not a
broken pipeline: an unopened door. The encoder, the search, the verifier and the safe
replace were all built and tested behind it, which is why a phone with the app installed
and a folder granted has never optimised a single file.

`NightPass.sync()` is the caller. It schedules when any folder is granted and cancels when
the last one goes — the cancel half matters as much, because somebody who revokes their
last grant in system Settings should not be left with a job waking the phone nightly to
find nothing to read.

Two call sites, both needed: when a folder is granted, which is the first moment there is
work; and on every app start where a grant already exists, because a periodic work request
does not survive a reinstall and nobody is going to re-grant a folder to fix something they
cannot see. `KEEP` makes both safe to call as often as they like.

### It says so in the export

"Is it actually going to run tonight?" was unanswerable from a phone. The diagnostics
export now opens with the scheduler's state, its run-attempt count, how many folders are
granted, and which constraints are on — and prints `WARNING: folders are granted but no
work is enqueued` when it sees exactly the fault above, rather than leaving it to be
inferred from an absence.

**No timestamps, and a test that keeps it that way.** `Diagnostics` in core/domain bans
absolute times from the export because when the night pass ran says when its owner sleeps.
`carriesNoTimestamps` fails on any ten-digit number in the section, so a later "last run at"
cannot drift in.

`WorkManagerScheduler` is now bound twice on purpose: the pipeline depends on the
`NightScheduler` port and must not know what schedules it, while the export asks the
concrete class what WorkManager holds — a question the port has no business answering on
iOS. The second definition resolves the first, so it is one instance.

## The folder picker says which folders Android blocks

"Can't use this folder", with nothing after it. Partly Android, partly ours.

Android refuses three locations to every app: the root of internal storage, `Download`, and
the root of an SD card. The picker greys out "Use this folder" rather than returning an
error — so a blocked attempt and somebody changing their mind reach the app identically, as
`null`. **The app cannot tell them apart**, which rules out any message that assumes the
user picked wrongly.

The picker now opens at `DCIM/Camera`, so the ordinary path never meets a blocked folder.
When it comes back empty the sheet leads with "No folder was chosen", names all three
blocked places, and gives the way round each — any folder *inside* them works, including
inside Downloads — then offers to open DCIM/Camera or the picker with no hint.

A returned tree is still classified. A picker that allows what the platform documents as
unpickable would otherwise leave a grant that is stored, looks granted and scans nothing,
which reads as an empty library rather than a refused folder.

Downloads itself needs "All files access", which is deferred rather than refused; PROJECT.md
records why and what would change the answer.

### A gap this uncovered

`sharedTest` runs the shared modules and stops, so **androidApp's unit tests had never been
run by CI**. A test written beside the Android code executed on no machine. The `android`
job now runs `:androidApp:test`, and `FolderChoice`'s rule is split so the part worth
testing is a pure function over a document id — six cases, including the one that matters
most: `Download/Holiday` must *not* be refused, because it is the workaround the sheet
recommends.

## ktlint that can actually run here

Five ktlint failures reached CI over two pull requests, each a ten-minute round trip to be
told about an import order. The cause was structural: every module with ktlint also has the
Android plugin, so `./gradlew ktlintCheck` cannot configure without Google Maven — and this
sandbox cannot reach it. The cheapest check in the build ran only in CI.

`tools/ktlint.sh` runs the ktlint CLI straight from Maven Central, which is reachable,
against the same `.editorconfig`. Eight seconds for the tree, and `tools/checkall.sh` runs
it first.

It is pinned to the version the Gradle plugin runs — 1.5.0, read out of the plugin jar
rather than assumed — and `build.gradle.kts` now pins the plugin to the same number with a
comment tying them together. Both directions were checked before landing: silent on a clean
tree, and exit 1 with the right message on a planted violation.

Found while doing it: **`build-logic` is not linted by anything.** It is an included build,
not a subproject, so `subprojects { }` never applies ktlint to it — 85 violations sit there
today. That is where the three build guards live. Recorded in PROJECT.md; excluded from the
script so it agrees with CI exactly, rather than fixed in a commit about tooling.

## The viewer, and the crash that hid it

First real use on a phone: tapping any photo closed the app. The viewer was not missing —
`GalleryScreen` has mounted `HeroViewer` since milestone 8. It threw before it could draw.

`GalleryScreen` opened from `HeroGeometry.target(0f, 0f)` whenever a tile had not reported
its bounds yet. `min(0, 430) - 36` is **-36**, so the viewer asked `Modifier.size` for a
negative width and Compose rejected it. Fixed at the source (`target` clamps to zero), at
the call site (a point, which is the honest degenerate case) and in the viewer (a clamp,
because a viewer that crashes is worse than one that briefly shows nothing). Two tests now
sweep every window size including the degenerate ones; the existing geometry tests all
passed throughout, because every one of them passes a plausible window.

Underneath it, a units bug that would have made the fixed viewer wrong: `GalleryTile`
reported `boundsInWindow()` in **pixels** into a type documented in **dp**, which
`HeroViewer` then rendered as dp — three times too large on a 3x phone. `onBounds` now
takes a `HeroGeometry.Rect` and converts, so the unit is in the signature rather than in a
comment nobody reads at the call site.

The viewer now pages. `HeroViewer` takes the grid's flattened order and a starting index,
and a `HorizontalPager` inside the hero frame swipes between items; closing lands on the
tile for whatever is on screen rather than the one first tapped. Paging is disabled until
the open animation finishes, because a horizontal drag during the transition fights it for
the same gesture, and no neighbouring page is composed ahead of time — every page is a
decode and some of them are players.

Video plays, through a `video` slot the Android host fills with ExoPlayer, for the same
reason `artwork` is a slot: the shared module depends on no player, and iOS will bind
`AVPlayer` to the same seam. Only the page in front holds a player. Backgrounding pauses
it. This is playback, not encoding — nothing here creates a codec, and `CodecFactory`
remains the only door to one.

### Crashes can now leave the phone

`CrashReports` installs an uncaught-exception handler before Koin and Coil — the two most
likely things to throw during startup — writes the trace with device model and API level
to app-private storage, keeps ten, and chains to the platform handler so the process still
dies properly. Nothing is uploaded, and nothing can be: rule 8 and the two guards that
enforce it are untouched.

`DiagnosticsButton` shares them through the export and FileProvider that milestone 13
already built. It appears only when a report exists, and sits on the first-run screen and
the empty grid — the places the app can still reach when it cannot reach a photo. It
belongs in Settings, which does not exist yet.

## The permanent link was the wrong one

`/releases/latest` skips pre-releases. The rolling release is marked pre-release on
purpose — a debug, never-field-tested APK should not present as a release — so that URL
redirects to the releases index rather than to the build, which is what README said for
one commit until someone tried it. Tested, rather than asserted this time:

```
/releases/download/latest/trim-gallery-debug.apk  ->  302, 109 MB, downloads
/releases/tag/latest                              ->  200
/releases/latest                                  ->  302 to /releases
```

README and the job's closing `echo` now use the asset and tag URLs, which do not care
about the pre-release flag. The flag stays.

The release itself published correctly on the first run: `latest`, marked pre-release, the
notes naming the commit and the run, and the APK attached and downloadable.

## A permanent link to the newest build

Every green run on `main` now republishes the debug APK to a rolling pre-release tagged
`latest`, so there is one URL that always serves the newest build:

    https://github.com/BigdataAytee/trim-gallery/releases/latest

The Actions artifact stays, and is still the way to get the APK from a *particular* commit.
It is just a poor way to hand someone a build: it needs a GitHub account, it arrives as a
zip, and it is deleted with the repository's retention period. A release asset has none of
those properties and does not expire, and `--clobber` replaces it in place so the download
link itself never changes.

A separate `release` job rather than another step in `apk`, for one reason: publishing
needs `contents: write`, and `apk` runs on every pull request. Keeping the write scope in a
job gated on `main` means a pull request's token never carries it. `needs: apk` makes the
precondition exact — a failed assemble or a failed DT_NEEDED check never satisfies `needs`,
so nothing unverified is published.

`gh`, preinstalled on the runner, rather than a third-party release action: this step
carries a credential, and one less action to trust is worth a few lines of shell. The tag is
force-moved before publishing, because `gh release edit` cannot repoint a tag and a release
that claims an old commit while serving a new APK is the kind of small lie that costs an
afternoon later.

## The review check is removed

`claude-code-review.yml` is deleted, with the `claude-code-action` row and the
add-it-to-your-workflows instruction in STACK.md. The entries below stay as they are: they
are the record of three distinct ways a green check can mean nothing, and that is worth
keeping whether or not the tool is.

The last diagnosis is the one that settles it. With `show_full_output: true` finally
printing what the action had been swallowing, the run on #11 said:

```json
"api_error_status": 400,
"terminal_reason": "api_error",
"result": "Credit balance is too low"
```

Not a rate limit, not a revoked key, and not quota burned by a rapid push cycle — which is
what I said the first time and was wrong about. The account has no credit, so no run since
has reached the model.

In its place: every pull request is read in-session before it merges, against BUILD.md, the
three skills, and the branch's declared scope in `.github/pr-scope/`, and the verdict goes
to the user in the conversation. That is weaker in one specific way and it is worth naming —
the same agent writes and reviews nearly every diff here. The mechanical checks are
untouched: build guards, merged-manifest scan, APK library check, emulator launch, ktlint
and detekt all still run, and none of them care who wrote the code.

`review` was never a required check, so nothing is blocked by its absence.

## The app had no screens

Installed the debug APK on a phone and it showed the words "Trim Gallery" centred on a
black page. Nothing was broken: that is what `MainActivity` drew. Every screen under
`shared/feature` had been written, unit tested and never mounted, and
`MainActivityLaunchTest` passed the whole time — reaching RESUMED proves the process came
up, not that anything is on screen. A placeholder reaches RESUMED.

Worse than the missing wiring: seven of the eight `shared/feature` modules contain only a
`build.gradle.kts`. Space, editor, settings, search, people, cleanup and compress have no
source. Their milestones are real but they are *logic* — `SpaceScreen.kt` is a state class
in `core/domain`, the editor is geometry and a save policy, search is a parser and a
ranker — and the screens were never written. `androidApp` depends on all eight modules,
which is why nothing ever complained.

`GalleryHost` now mounts the module that does have screens. Pick a folder with the system
picker, and the grid fills with what is in it: sticky date headers, pinch zoom between
day/month/year, the fast-scroll bar, thumbnails through Coil with `coil-video` registered
so a video tile shows a frame. The decisions it forced — the platform's persisted URI
permissions as the record of what is granted rather than our own tables, `FolderMode.KEEP`
until a settings screen can ask, read+write permission taken at grant time because SAF
will not widen it later, `mtime` as the date when EXIF has not been read yet — are in
PROJECT.md.

README's Status section said the gallery shell, the Space screen and the editor were
built. That was true of the logic and false of anything a user could open, and it has been
corrected rather than softened: the section now says plainly that seven feature modules are
empty and what the one working screen does.

Still not there: navigation, a database behind the grid, and any way to start the night
pass by tapping something.

## A green check that means nothing, closed off

The `review` check on #9 came back **green in thirteen seconds**. It had reviewed
nothing: the action refuses to run when `claude-code-review.yml` differs from the
default branch's copy, and it refuses by exiting zero.

That is the same failure this workflow already had once and that #6 was written to
end — a green check indistinguishable from a job that never ran — arriving by a
different route. #6 fixed the case where the reviewer ran and had nowhere to post.
It could not fix the case where the reviewer declines to start.

The condition is now tested before the action runs, and fails the job. Not by
matching the action's own message — a later step cannot see that output, and the
wording is not ours to depend on — but by testing the same fact the action tests:
whether this file is byte-identical to the default branch's copy. When it is not,
the job stops with an annotation on the file, prints the differing hunk, and says
what to do instead.

The consequence is deliberate and permanent: **every pull request that edits this
workflow now has a red `review`**, including the one that added the guard. The
reviewer genuinely cannot run on such a branch. A red check that names the reason is
worth more than a green one that means nothing, and the land-it-first-then-test-it
dance that was previously a comment for humans to remember is now enforced.

Both directions were tested before pushing: against a branch that edits the file, the
check fails; against `main`'s own copy, it passes.

Still not covered: a run that reaches the model and then posts nothing. #6 addresses
that by instruction rather than by mechanism, and closing it properly means asserting
the tracking comment gained a body — its own change, not this one.

## Temporarily unhiding the reviewer's own error

CI only, and meant to be reverted.

The `review` check has now failed three times in a row — PR #7 twice, PR #8 once —
with the same envelope: `is_error: true`, `num_turns: 1`, ~500 ms, `total_cost_usd: 0`,
`modelUsage: {}`. Zero cost with empty `modelUsage` means the API rejected the request
before any inference ran, so this is not the reviewer failing to review; it is the
reviewer never starting. The first failure looked like quota exhaustion from a burst of
eight invocations in forty minutes. That explanation did not survive: the next two came
an hour later, on two different pull requests, with one invocation between them.

What the log cannot say is *why*. The action prints "Running Claude Code via SDK (full
output hidden for security)" and emits only the result envelope, and exhausted credit, a
spend cap, a revoked key and a rate limit are indistinguishable inside it. The secret is
set and non-empty — `ANTHROPIC_API_KEY: ***` appears in the job env, where an unset
secret would leave the line blank — so the missing-secret cause that this workflow
already had once is ruled out.

`show_full_output: true` prints the SDK's error string. It stays only until the reason
is in hand: with it on, every review's full output is in the job log for anyone who can
read Actions.

Also worth recording, because it constrains how this gets tested: the action refuses to
run when this file differs from the default branch's copy, so the change cannot be
validated on its own pull request. It has to land on main first and be exercised by a
pull request that does not touch it.

## README brought in line with what CI now proves

Documentation only. The `Status` and `Building` sections still described the environment this
repository was written in rather than the one it is built in: "nothing Android, iOS or Compose
has been compiled", "the native layer is not built yet", compileSdk 36, and test counts two
milestones out of date. All of that has been false since the CI hardening pass and the AGP 9
upgrade landed — six required checks now compile the Android half, cross-compile every shared
module for both iOS targets, build the native tree from source, check the built APK's
`DT_NEEDED` list and launch the app on a managed Pixel 6.

Rewritten from CHANGELOG.md and main: what each of the six checks proves, the test-function
counts as counts of test functions rather than of a runtime tally, compileSdk 37, the native
prerequisites `assembleDebug` needs, and the fact that Google Maven resolves on GitHub's
runners but not in the sandbox — which is the reason the platform-free Kotlin split exists,
so it is worth stating plainly rather than as an apology.

The "not field-tested" caveat stays, and is now stated twice: a green pipeline says the app
builds, holds its rules and launches, and says nothing about a night pass on a real library.

A `Get the APK` section points at the `apk` artifact. The `Android APK + native` job already
uploaded it unconditionally — `if: always()`, no job-level gate — on every `main` run as well
as every pull request, so no workflow change was needed and none was made.

## Development guardrails, and a reviewer that was not reviewing

No product change. Three process failures turned into mechanisms, and one uncomfortable
finding.

`tools/checkall.sh` runs `./gradlew` and nothing else, refusing to start unless the
wrapper matches `gradle-wrapper.properties` — the previous harness called the system
`gradle`, a different version, so its green results meant nothing during the AGP 9
upgrade. `tools/branch.sh` starts each branch in its own worktree, because a `git
checkout` carries uncommitted edits across branches and once did. A `pre-push` hook
refuses a diff that reaches outside the scope declared in `.github/pr-scope/<branch>.txt`,
and its self-test replays the exact leak that motivated it.

Then the fixed reviewer read this branch and found a bug in it: `branch.sh` created only
`.github/pr-scope`, not the nested directory a slashed branch name needs, so it aborted
after making the worktree and left the branch with no scope file — failing into
no-guardrail. Three more real gaps came with it: scope globs matched across slashes,
`pre-push` inspected `HEAD` instead of the refs being pushed, and nothing ran the
self-tests. All four were fixed — and a second review found that two of the fixes did not work. The
scope file was read from the working tree rather than the pushed commit, so pushing a
branch from another worktree still went unchecked; and the bounded stdin read used
`timeout`, which does not exist on macOS, so every Mac would have silently run the
unfixed hook. Both failed open. Both are fixed, along with two bash-3.2 and BSD-grep
portability faults, and the self-test now covers the case that hid them: pushing a branch
that is not the one checked out.

The finding: the `review` check had never completed before today (no API key), and once
it could run, a deliberately planted software-encoder fallback — a violation of the one
rule this project treats as non-negotiable — passed it without a word. The bot ran 18
turns and posted nothing. A green `review` currently means the job exited zero, not that
anything was reviewed.

## The review check now posts its findings

`claude-code-review.yml` ran in agent mode with `track_progress` off and a prompt that
asked for a review but never said to post one, so the action had nowhere to publish the
result and no instruction to try. The job passed, silently, having reviewed nothing that
anyone could see — including a deliberately planted software-encoder fallback.

`track_progress: true` gives the agent a tracking comment to write into, and the prompt
now requires a comment on every run, findings or not, on the grounds that a silent pass
is indistinguishable from a job that never ran. The prompt also asks the reviewer to
judge what changed code *does* rather than where it sits, since the build guards already
enforce location and the planted violation lived inside the one file where creating a
codec is legal.

## AGP 9 / Compose Multiplatform 1.12

A version bump with no behaviour change, done as one commit because the pieces cannot
move apart.

androidx.compose 1.12.0 declares `minAgpVersion=9.1.0` and `minCompileSdk=37` in its AAR
metadata. That makes Compose Multiplatform 1.12.0, AGP 9.1.0, Gradle 9.7.1, compileSdk and
targetSdk 37, and coil 3.6.0 a single set — split them and `checkDebugAarMetadata` fails
one dependency at a time. The lifecycle pair (`org.jetbrains.androidx.lifecycle` and
`androidx.lifecycle`, both 2.10.0) is in the set for a subtler reason: the multiplatform
line resolves to the AndroidX one, which pulls androidx.compose to 1.12.0 from behind
Compose Multiplatform's back, so holding Compose back while that moved achieved nothing.

AGP 9 also refuses to apply `com.android.library` alongside `org.jetbrains.kotlin.multiplatform`
at all, which is every one of the 14 shared modules. They now use
`com.android.kotlin.multiplatform.library`: `androidTarget()` is replaced by an
`android { }` block inside `kotlin { }` carrying namespace, compileSdk and minSdk, and the
JVM level moved from `compileOptions` to a `jvmToolchain(17)`, because the new DSL has no
`compileOptions`. That plugin has no `ndk` block either, so the shared modules no longer
declare an ABI — they contain no native code, and the arm64-only guarantee was always
enforced by the app module's filter and by `tools/check-apk-libraries.sh` reading
`DT_NEEDED` from the built APK. Two comments that claimed otherwise are corrected.

AGP 9 also supplies Kotlin itself and *rejects* `org.jetbrains.kotlin.android` — "no
longer required for Kotlin support since AGP 9.0". It is gone from `androidApp`, from
`benchmark`, from the root plugin list and from the version catalogue.
`kotlin-multiplatform` is unaffected; the shared modules are not Android-plugin projects.

Compose 1.12 turned two plugin accessors into errors — `compose.ui` ("specify dependency
directly") and `compose.uiTooling` ("use org.jetbrains.compose.ui:ui-tooling module
instead"). Both are version-catalogue entries now, referencing the same
`composeMultiplatform` version so the build still has exactly one Compose version in it.
`compose.runtime`, `compose.foundation` and `compose.material3` were not deprecated and are
untouched.

## Hardening pass — CI, guard self-tests, thermal floor

No new features. Three things that were claims rather than facts, made into facts.

### CI now compiles what this environment cannot

A macOS job compiles every shared module for `iosArm64` and `iosSimulatorArm64`, and the
workflow runs on every push rather than only on `main`, so the feedback arrives on the
branch where the work is.

The first thing a real Gradle run found was that **the build had never configured at all**:

```
Conflicting configuration : 'arm64-v8a' in ndk abiFilters cannot be present
when splits abi filters are set : arm64-v8a
```

`androidApp` said "one ABI" twice, in two mutually exclusive ways. AGP rejects that during
*configuration*, so it failed the guards and shared-test jobs too — jobs that never touch
Android. Splits exist to produce one APK per ABI; with a single ABI there is nothing to
split, and `abiFilters` is what every library module already uses. Removed the split.

That was the first of five configuration-time faults, each of which hid every error behind
it. In order:

- **An eager `tasks.named("assembleDebug")` inside `androidComponents.onVariants`.**
  `onVariants` runs while AGP is still building the variant model, before it has registered
  the lifecycle tasks that model produces. Every job died in `:androidApp` — including the
  iOS job, on a Mac, which had asked for nothing Android: Gradle configures every project on
  every invocation.
- **`sharedTest` asking for `:shared:core:jvmTest`.** `include(":shared:core:model")` creates
  a project for every path segment, so `:shared:core` exists with no build file and no Kotlin
  plugin. The task list was built by path prefix; it now filters on `buildFile.exists()`.
- **Eight overrides in `core/data` that never compiled.** Every `Unit`-returning port —
  `UndoJournal.forget`, `NightRun.Checkpoint.save`, `TriageStep.Sink.insert` and the rest —
  was an expression body over a SQLDelight call, so its inferred type was `QueryResult<Long>`
  and none of them overrode anything. The module needs SQLDelight's generated interface to
  compile and had therefore never been compiled anywhere. Found by pointing the real
  SQLDelight plugin at the real `.sq` files: it comes from Maven Central, unlike AGP and
  Compose, so this half of the build can be run here after all.
- **Three syntax errors in files no compiler had ever seen.** A stray `}` in `TrimTheme.kt`,
  and `` `shared/feature/*` `` inside a KDoc in `MainActivity.kt` and `GalleryTile.kt` —
  Kotlin block comments nest, so that `/*` opened a comment the KDoc's own `*/` closed,
  leaving the rest of each file inside a comment that never ended. Found by ktlint's parser,
  which needs no dependencies, and is now the cheapest syntax check available here.

### The static analysis was not analysing anything

Every `:shared:*:detekt` task reported `NO-SOURCE`. Detekt's default source set is
`src/main/kotlin`, which no Kotlin Multiplatform module has, so analysis configured since
milestone 2 had never looked at a line of the shared layer. Pointed at the real source sets
it found 357 issues, and four of them were defects:

- `SettingSearch.bisect` and `bisectUpward` each took a `bounds` parameter neither read.
- `GalleryTile` took an `index` it never used.
- A test carried a fake encoder nothing constructed.
- `NightWorker` caught every exception from a whole night and returned `Result.retry()`
  without recording anything anywhere.

Those are fixed. The 37 that remain are shape rather than defect — long `when` chains,
six-parameter functions, a thirty-method repository — and are recorded in
`config/detekt/baseline.xml`, which is the mechanism for adopting a linter on existing code:
everything *not* in that file fails the build, and the list only shrinks.

`MagicNumber` is the one rule switched off rather than tuned. Its 220 hits were UUIDv7 bit
shifts, degree/radian conversions, EXIF orientation codes and the design-token tables. The
numbers it exists to catch — thresholds, gates, ratios — are already named constants carrying
the reason they hold that value.

There was also no `.editorconfig`, so ktlint had been formatting to `ktlint_official`, its
most opinionated style, and 3,185 violations. The style is now `intellij_idea` — the Kotlin
coding conventions — chosen on purpose rather than inherited by accident, and the tree is
formatted to it.

### What the guards caught once they could run

- **The merged manifest asked for INTERNET.** Coil declares it; WorkManager declares
  `ACCESS_NETWORK_STATE`; the manifest merger unions both into the APK. The app's own screen
  says it has no network access, so until this ran that claim was false about the artifact.
  Four `tools:node="remove"` lines delete them, and the scanner learned that a removal is not
  a request — otherwise it would have failed on the lines written to satisfy it.
- **`IosDatabase` had `journalMode` on the wrong object**, and claimed an Application Support
  path it never set — which would have put the index of someone's photo library under
  Documents.
- **The gallery keyed on a `Long` id** the model has never had.
- **`TriageStep` smart-cast across a module boundary**, which Kotlin does not allow.
- Two guards failed on the ordinary shape of a library module — no manifest, no sources — and
  now say where those are required instead.

### The version wall, read out of the artifacts

`checkDebugAarMetadata` refused 29 dependencies, then 25. Two requirements, both declared
inside the published `.aar` rather than in anyone's release notes:

- `androidx.compose.*:1.12.0` — `minAgpVersion=9.1.0`, `minCompileSdk=37`
- `io.coil-kt.coil3:*:3.6.0` — `minCompileSdk=37`

against a build on AGP 8.13.0 and compileSdk 36. Pinned down rather than jumped: the
androidx `compose-bom` out of `androidApp` (the one module mixing it with the Compose
Multiplatform plugin, so two Compose versions were in one build), Compose Multiplatform to
1.11.1, coil to 3.5.0 — whose `.aar` declares `minCompileSdk=36` where 3.6.0 declares 37,
downloaded and unzipped to check.

Compose alone did not move the number: `org.jetbrains.androidx.lifecycle:2.10.0` maps to
`androidx.lifecycle:2.10.0`, which pulls androidx.compose back up to 1.12.0 from behind
Compose Multiplatform's back. Set to 2.9.6 — what Compose Multiplatform 1.11.1 depends on
itself, and whose Android variant maps to the `androidx.lifecycle:2.9.4` already in the
catalogue, so the two lines agree instead of one overriding the other.

The first diagnosis was wrong and is corrected in PROJECT.md rather than quietly dropped:
it said detekt has no Gradle 9 release, so the AGP 9 upgrade would cost static analysis.
detekt 1.23.8 and ktlint-gradle 14.2.0 were then run against Gradle 9.7.1 and both work.
The upgrade is still deferred, for a duller reason — AGP, the SDK platform and androidx all
live on Google Maven, which this environment cannot reach, so it can only be tried in CI.

### The native build, which had never run anywhere

`shared/native` cross-compiles for android-arm64 in CI, and the APK now contains a
`libtrim_native.so`. Five failures stood between the first attempt and that, each hidden
behind the one before it, and none of them findable without a real NDK:

- **cargo-ndk was missing, then wanted the wrong artefact.** The CMake shells out to
  `cargo ndk`; the runner had no such subcommand. Installed — then `-o` asked for a cdylib,
  which `trim_oxipng` deliberately is not: it is a `staticlib`, archived into
  `libtrim_native.so`. Dropped `-o`.
- **AGP was building libjxl's and jpegli's fuzzers, benchmarks and command-line tools.**
  `EXCLUDE_FROM_ALL` does not stop it: AGP names every target in the graph on the ninja
  command line, and a named target is built whether or not it is in `all`. Scoped to
  `targets += "trim_native"`.
- **`-ljxl_extras-internal`, a target CMake had never heard of.** libjxl defines it only
  when `JPEGXL_ENABLE_TOOLS` or `BUILD_TESTING` is on; this build had both off, and CMake
  passes an unknown link name straight through to the linker. `ssim2_score.cc` loads pixels
  through `lib/extras/codec.h`, so it is genuinely needed — and the comment above the line
  had said so all along while the code said otherwise.
- **`NoMemoryManager()` used through its header and never compiled.** Same shape as
  `ssimulacra2.cc`, which was already compiled in for the same reason.
- **libvmaf's one C++ file built for the host.** The meson cross file carried `--target`
  and `--sysroot` in `c_args` and `c_link_args` only. libvmaf bundles libsvm, meson applies
  `c_args` to C alone, and `svm.cpp.o` archived cleanly into `libvmaf.a` before failing the
  arm64 link with `incompatible with aarch64linux`. `cpp_args` and `cpp_link_args` now match.

Two tooling changes came out of it, and both belong to the pass more than the bugs do.
`.github/failure-summary.sh` now matches ninja, clang and CMake diagnostics: a native
failure used to summarise to AGP's exception heading and a list of target names, with no
diagnostic anywhere in the annotations. And `tools/build-native-host.sh` builds
`test_metrics`, the one target that *links* `trim_native` into an executable — on the host
it is a static library, so building it alone proved only that every file compiled. That
change caught one of the five locally, in seconds, before CI saw it.

### One native library, and proof the app starts

`libtrim_native.so` was linking libjxl, libjxl_cms and brotli's three libraries as shared
objects, so six `.so` files had to reach the device or `System.loadLibrary` would throw the
first time a night pass touched a photo. They are static now — one self-contained library,
which removes the failure mode instead of checking for it. Every licence permits it (BSD-3,
BSD-2-Clause-Patent, MIT, Apache-2.0; nothing copyleft).

The check that was watching for that failure is still there and is now worth more: it reads
DT_NEEDED out of each packaged `.so` and requires every entry to be packaged or part of the
NDK's stable ABI, rather than comparing against six names that the static-link change would
have made stale on the spot. It has a self-test planting three violations, which found a
real bug on its first run — `unzip -Z1` writes "Empty zipfile." to stdout, so an APK with no
native libraries at all was being parsed as two filenames and passing.

`MainActivityLaunchTest` launches `MainActivity` on a Gradle-managed device and asserts it
reaches RESUMED and survives recreation — the check this repository has never had, that the
app runs rather than compiles. It runs on an x86_64 emulator under KVM on a Linux runner,
which took a `smoke` build type carrying arm64-v8a + x86_64: no hosted runner can virtualise
arm64 Android (macOS runners are themselves VMs and refuse with `HVF error: HV_UNSUPPORTED`),
and every runner that can boot an emulator is x86_64. `release` still ships one ABI, so
publishing x86_64 would take a deliberate build-file change.

Making that build work meant describing both ABIs honestly. `-march=armv8-a+simd` moved out
of Gradle, which applies `cFlags` to every ABI and would have handed an ARM architecture name
to the x86_64 compiler, and into CMake, which knows the ABI it is configuring; the meson
cross file's `cpu_family`, `cpu` and arch argument are substituted per ABI rather than
hardcoded to aarch64. The second cross-compile is cached.

### Two promises made safe

**Free undo retention is 30 days, not 7.** MONETIZATION.md gave free users 7 while BUILD.md
§ 6 promised a 30-day default, and the code resolved that by clamping — so a free user was
shown 30 and got 7. "Free space" mode's premise is that originals are recoverable for the
window the user was shown; shortening it behind a paywall deletes photographs three weeks
early. Free is now 30, Pro extends to 90, and both documents and the tests agree.

**iOS replace is behind a flag, off.** `SafeReplacerIos.commit` refuses before opening a
change block, and a shared test fails the build if the constant is flipped — so enabling it
requires reading why it was off and running the PhotoKit change-block atomicity procedure,
now written out step by step in PROJECT.md's device-required list. The sequence's rollback is
tested; PhotoKit's is not, and cannot be without a real photo library. Read paths, preflight,
encode and `saveCopy` are unaffected.

Both native jobs now cap at 90 minutes and cache `androidApp/.cxx` and oxipng's `target/`,
keyed on the CMake inputs and the exact submodule commits.

### Guard self-tests

Every other guard test checks a case somebody thought of. `GuardSelfTest` checks something
weaker and more important: **that each guard fires at all, in every language it claims to
police.** Each rule gets a planted violation and the honest version of the same file — a
rule that matches everything would otherwise pass the firing half while failing at its job.

The manifest and plist guards get the same treatment, including a test that every entry in
their forbidden lists is one they actually catch.

The binding part is the meta-test: a rule in `DEFAULT_RULES` with no planted violation for a
language it claims **fails the build**. That is not hypothetical — from milestone 4 to
milestone 15 the codec and replacer rules carried patterns for `VTCompressionSessionCreate`
and `PHAssetChangeRequest` while the harness globbed only `.kt`. They had never been run
against a line of Swift. They were not guards; they were comments written as regular
expressions, and nothing in the build could tell the difference. Verified by removing a
probe and watching the meta-test fail.

### A pause floor on the thermal gate

Milestone 15 recorded that iOS's four discrete thermal states leave the hysteresis nothing
to bite on, so an oscillating OS signal produced a pause and a resume per oscillation. The
fix is a floor in *time* rather than a second gate: once the pass has stood down it stays
down for at least a minute.

It works on both platforms because it does not care what shape the reading is. A phone that
is genuinely cooling loses at most that minute; a phone whose sensor is flapping stops
costing the user a "paused for heat 400×" line in their History. Six oscillations at the
five-second poll rate now produce one stand-down rather than six.

**The floor only ever delays resumption, never protection** — heat pauses on the reading
that reports it, always. `ThermalState.HELD_FAIR` stays the non-default alternative: with
the floor in place it is belt and braces rather than the only defence, so ARCHITECTURE.md
§ 6's "run at fair" can hold.

### iOS replace: everything a new asset does not inherit

`SafeReplacerIos` carried creation date, location, favourite and album membership. It did not
carry `isHidden` — the locked folder — so a replacement would have put a photograph the user
deliberately hid back into the main grid.

Beyond that, some state has no setter on a creation request at all: adjustment data (an
edited photo's original underneath it), a burst identifier, a smart album's membership,
somebody else's shared album. `ReplacePreflight` — shared, and tested on a JVM against a
value rather than a photo library — decides that from metadata **before the encode**, so such
a file is skipped with a reason (`WOULD_LOSE_STATE`) rather than replaced and quietly
diminished. Discovering it during the swap would mean aborting halfway through the user's
only copy.

### The iOS restore journey

`UndoLocation.SYSTEM_TRASH` fell through to `FromBin`, so iOS offered a one-tap restore the
platform does not have — PhotoKit has no API to restore from Recently Deleted, by design.
It is now its own state, with copy that says where the file is and until when, and an **Open
Photos** action. Keep and Offload stay one tap, because those originals are in the app's own
storage or on a volume the user picked. USER_JOURNEY.md § 5 gained the iOS variant.

### Two real defects the sweep found

- **`Predictor.bounds` could construct invalid bounds and throw.** A confident entry whose
  learned setting falls outside the fallback bracket produced `low > high`, which the
  constructor rejects — a crash in the night pass from a table row that was merely out of
  date. Reachable since milestone 12 made the fallback derive from the source's own bitrate.
  Found by a property test over settings, sample counts and variances, not by a case anybody
  thought of.
- **The undo sweeper did not check that the job succeeded.** An entry left by a night that
  died between the journal write and the job's status update would be swept thirty days
  later — deleting what may be the only copy of that file, and reporting the space as freed.
  The job's state is now a required argument rather than a defaulted one, because a default
  of "assume it succeeded" would put the hole back invisibly.

### What still needs a device

PROJECT.md gained a **Device-required verification** section: iOS thermal oscillation,
PhotoKit's change-block atomicity, whether `isHidden` can actually be set on a creation
request, smart-album re-derivation, encoder quirks, and reduce-motion/TalkBack. Each has the
exact procedure to run. None of it is faked in a test — a green suite asserting invented
platform behaviour is worse than an open question, because it looks like an answer.

### Numbers

906 shared JVM tests and 67 build-guard tests pass.

## Milestone 15 — the iOS port

BUILD.md § 13.15: *"iOS port: VideoToolbox, AVAssetWriter, BGProcessingTask, PhotoKit."*

**Nothing here has been compiled.** There is no Mac and no Xcode in this environment, so
Swift is written to documented behaviour and reviewed, not executed, and Kotlin/Native
targets are still declared only on a Mac. That makes this the least verifiable milestone in
the project — which is exactly why the work went first into the parts that *can* be verified
here, and only then into the Swift.

### Proving the shared layer is actually portable

The shared modules have only ever been compiled for the JVM. A stray `java.util` import in
`commonMain` would have passed every test and every CI run right up until the day someone
tried to build for Kotlin/Native and found the port blocked by a hundred small things.

There were none — the audit came back clean — but "we checked by hand" does not survive a
year of changes, so it is now a build guard with source-set path scoping: platform imports
in shared common code fail the build. Two things came out of writing it:

- **The first version flagged 196 correct lines.** `androidx` is not one thing:
  `androidx.compose.*` is Compose Multiplatform and compiles for Kotlin/Native, while
  `androidx.work` and `androidx.datastore` do not. That is the useful kind of false
  positive — a guard nobody can satisfy gets switched off, and then it guards nothing.
- **The allow-lists were language-dependent.** They name `SafeReplacerIos.kt`, written when
  every implementation was Kotlin; the component is Swift, because PhotoKit lives there.
  Comparison is now extension-insensitive, which is the right comparison anyway: the
  boundary is about the component, not the compiler. The guard's iOS patterns —
  `VTCompressionSessionCreate`, PhotoKit mutations — had also never been applied to any
  Swift, because the harness only globbed `.kt`. Both fixed; 189 files scan clean.

### The decisions the port exposed

iOS reports **four thermal states** where Android reports a continuous headroom. The
tempting move is a second gate for iOS; the result of that is two policies that drift, and a
user told "paused for heat" on one phone and not the other at the same temperature. So the
mapping is shared: one gate, one hysteresis, one set of thresholds, converted at the edge to
give exactly ARCHITECTURE.md § 6's *"run at nominal/fair, pause at serious/critical"*.

A consequence worth knowing is asserted rather than hoped: **iOS has no state between the
thresholds, so the hysteresis does nothing there.** An oscillating OS signal is a pause per
oscillation. The alternative — mapping fair *between* the thresholds so only nominal
resumes — is named, tested and ready if the field test finds flapping.

iOS also has **no alarm API at all**, so the whole deadline there is the user's own "stop by"
time. That path now has a test, because the port depends on it already working: had
`AlarmWindow.deadline` needed an alarm to produce an answer, the night pass on iOS would
simply never stop.

### The adapters

Written selectively — the ones whose contracts are subtle enough that getting them wrong
loses a user's file or a user's albums:

- **`SafeReplacerIos`.** There is no rename on iOS: "replace" is
  `creationRequestForAssetFromVideo` then `deleteAssets`, and both must be in **one** change
  block, or there is a window with neither file in it — on the user's only copy. Album
  membership does not follow a new asset, so the original's albums are read *before* the
  block and re-applied inside it; miss that and the photograph is still in the library, just
  not where they left it, and nothing surfaces it. The delete lands in system Recently
  Deleted for 30 days, which is exactly the FREE folder mode's retention, so on iOS the bin
  is the OS's.
- **`NightTask`.** `BGProcessingTask` with `requiresExternalPower` and
  `requiresNetworkConnectivity = false`. It re-submits *before* running, because iOS grants
  one window per submission and a night that forgot to ask for the next one is an app that
  optimises once and never again. The expiration handler is not clean-up bolted on — it is
  the same interruption the guards already produce when a phone is unplugged, and
  `NightRun` already checkpoints after every file.
- **`ThermalGuardIos`**, which observes rather than polls, and converts through the shared
  mapping.
- **`VideoToolboxFactory`**, the only place a codec is created on iOS.
  `RequireHardwareAcceleratedVideoEncoder`, not `Enable` — only the first fails rather than
  falling back, which is the behaviour BUILD.md rule 2 needs.

`IosDatabase` completes the storage side: the same schema, the native driver, foreign keys
on (SCHEMA.md's `ON DELETE CASCADE` is inert without the pragma, and an orphaned undo row is
an original nobody can restore), and the file in Application Support rather than Documents,
where a user browsing their own files would find the index of their photo library.

### Numbers

869 shared JVM tests and 59 build-guard tests pass; the guards scan 189 source files —
Kotlin and Swift — clean.

## Milestone 14 — Memories and the map (v1.1)

BUILD.md § 9 v1.1: *"Memories / On this day with music; Map view with offline tiles."*

### The constraint that shapes the whole milestone

PRD.md R8 is that this app never gets the `INTERNET` permission — it is displayed as a
feature and two build guards enforce it. So there is **no geocoder and no tile server**.
Everything a map or a memory could say about a place has to come from the coordinates in
the user's own photographs, and the basemap has to already be on the device.

That rules out place names entirely. A trip memory says *"5 days away · 1200 km away"*, not
"Barcelona", because "Barcelona" would be a guess dressed as a fact. Where a name is wanted,
the only honest source is the user typing one.

### The basemap

Put to the user as a decision rather than assumed, because it needed either a library that
is not in STACK.md or a deliberate choice not to have one. The answer: **the user supplies
an MBTiles pack**. An `.mbtiles` file is an ordinary SQLite database, so it needs no map
library and nothing new in the approved stack — the platform's own SQLite opens it read-only.

The cost is stated rather than hidden: the map is empty until a pack is added, which is a
worse first run than a map that just works and is the price of an app that cannot reach the
network. `MapTiles.available` exists so the screen can say so instead of showing a grey grid
that reads as broken, and a pack that cannot be used is refused *with a reason* — a vector
pack is a valid MBTiles file this app cannot draw, and saying so beats a map that silently
stays empty after the user went and found a file.

The one thing about the format everybody gets wrong is in shared code with a test: **MBTiles
counts tile rows from the bottom of the world and slippy tiles count from the top.** Get it
wrong and the map is not blank, it renders upside down by hemisphere — the sort of bug that
survives a demo.

### Geometry

Distance is haversine; the centroid of a set of points is averaged as unit vectors, because
averaging longitudes puts the centre of two photographs either side of the date line in the
middle of Africa. Latitudes are clamped to the Mercator limit, since photographs *are* taken
past 85° and a projection that runs to infinity there is a crash reachable from a user's own
library.

A test caught a real modelling slip: tile scale was using the mean Earth radius. Web Mercator
is defined on the WGS84 equatorial radius, and the two are not interchangeable — ground
distance wants the mean, tile scale wants the equatorial, and mixing them puts a scale bar a
tenth of a percent out and in disagreement with the tiles it is drawn over.

Clustering is greedy and seeded by recency, so the same library at the same zoom always
produces the same pins and panning away and back does not rearrange the map under the user's
finger. Cluster centres are not recomputed as members arrive: moving them lets a chain of
photographs a hundred metres apart drag one cluster across a whole city, which on a map looks
like a single pin swallowing a country.

### Home, and trips

"A weekend in Lisbon" cannot be found without knowing where home is — the same photographs,
taken by someone who lives there, are not a trip. Home is derived from nothing but the
coordinates already on the device, is never in the diagnostics export, and cannot become an
address because there is no geocoder.

Two rules came out of testing it:

- **A share test alone is not enough.** Five places holding a fifth of the library each all
  clear any threshold low enough to be useful, and the largest wins by rounding. Home now has
  to be twice the runner-up as well. Someone who genuinely splits their life between two
  cities has no home by this rule and is offered no trips — which is right, because measuring
  "away" against one of two homes would call half their ordinary life a holiday.
- **The gap that ends a trip is three days, not two.** Chosen by which mistake the user sees:
  a fortnight in Italy split into three memories looks broken, where two trips three days
  apart merging reads as one longer trip.

### Memories, and what is never in one

This is the one feature in the product where being *wrong* is worse than being absent. Every
gallery that has shipped Memories has hurt someone with it — the ex-partner on an
anniversary, the relative who died, the hospital corridor. So the exclusions are not a filter
bolted to the end of the selection: they are a value the caller passes in, they are applied
before anything is grouped or ranked, and there is no path through the code that skips them.
A person, a date, a place with a radius, and a dismissal that actually sticks.

The locked folder is excluded structurally rather than by mute, because hidden items are out
of every other view and a memory is a view. Face clustering being off means there are no
person memories at all — not computed and filtered, which is the same privacy mistake
`IndexStep` already refuses to make. A person the user has not named gets no memory, because
there is no source for a name but them and "Person 3" as a title is worse than nothing.

Selection drops near-duplicates using the hashes the index already computed — eleven frames
of the same plate of food is what makes a memory feel automatic — but never a favourite, on
the grounds that a user who marked two similar frames meant both. Over the length limit it
samples across the span rather than truncating: a memory of one morning of a week-long trip
is a memory of the wrong thing.

### Numbers

859 shared JVM tests and 47 build-guard tests pass; the guards scan 182 source files clean.

## Milestone 13 — the field test

BUILD.md § 13.13: *"Field test on 3+ devices; measure GB/hour and Wh/GB."*

**The run has not happened, and nothing here is presented as a field-test result.** It needs
three phones, a fortnight and a real library, none of which exist in this build environment.
What is written is everything the run needs and everything that can be got wrong quietly:
the metrics it collects, the arithmetic it reduces them to, the gate it is judged against,
the file it exports, and the fit that turns a sweep into a threshold. FIELD_TEST.md is the
procedure itself.

### Two metrics BUILD.md asks for were not being recorded

§ 14's per-night list includes *"files indexed, duplicates found"*, and neither `RunSession`
nor SCHEMA.md's `run_session` table had them. That is not only bookkeeping: BUILD.md § 7
runs indexing in the *same* pass as the optimisation, and MONETIZATION.md promises indexing
keeps going after the free cap is reached — so a night that optimised nothing because the
cap was spent but indexed four hundred files did exactly what the user was promised, and
with only `filesDone` to go on it looked like a night that did nothing at all.

### The arithmetic

`FieldMetrics` reduces the logged rows to the numbers LAUNCH.md wants published. Three
decisions in it are the ones a spreadsheet would get wrong:

- **GB per hour is per hour of *work*, not of wall clock.** A night plugged in for eight
  hours that worked for forty minutes freed its gigabytes in forty minutes; the other seven
  hours were the guards doing their job. Dividing by wall clock would make a well-behaved
  build look slow and reward one that ignored the thermal gate.
- **The saving is a median.** One 4K drone clip that compresses to a tenth pulls a mean up
  by several points on its own, and the gate is about what a typical file does.
- **Skipped and failed files contribute no saving rather than a zero.** Counting them would
  report a device as saving less the more carefully it declined to touch things.

Video and photo savings are reported apart, because LAUNCH.md's gate is about video and a
library of screenshots must not answer for it.

### The gate

`AlphaGate` is LAUNCH.md's private-alpha criteria as something that can be evaluated rather
than argued about: *≥ 30% median video saving, restore rate < 2%, zero thermal complaints*,
plus three devices and five nights each.

- **It is judged on the worst device, not the average.** The entire point of testing on
  three is to find the one that behaves differently, and PRD.md names the low-end chip as
  the risk. Pooling would let two good phones carry a bad one.
- **A criterion with no data fails.** Not "n/a" — the field test exists to produce the
  evidence, and a build that shipped because nobody measured the restore rate is the exact
  failure the gate is for.
- *"Zero thermal complaints"* is made measurable as thermal stand-downs per night. A
  complaint is a person and cannot be counted from a log; a phone pausing three times a
  night is getting hot whether or not anybody filed one.

Two reporting defects turned up while testing it, both of the kind that make a "no" useless:
a criterion failing for *missing* evidence reported the partial number and said FAILS, which
sends someone off to fix a build that was never the problem; and a passing 0.5% restore rate
formatted to "0", which reads as missing data in the one report where that difference
matters.

### Export diagnostics

**The only file this app ever produces that is meant to leave the device.** There is no
`INTERNET` permission and a build guard enforces it, so nothing can send it — the user
exports and shares it themselves. That makes the whole design question *what is a user
agreeing to when they tap this?*

The contents are built as an explicit list of permitted fields rather than by serialising
the rows the app already holds. That is the difference between a redaction that holds and
one that lasts until somebody adds a column. Never included: filenames, paths and SAF URIs
(a URI carries the folder and usually the filename, which between them can name a person or
an employer); locations; any timestamp but the export's own date (when a photo was taken
says where somebody was, when the night ran says when they sleep); content hashes, exact or
perceptual, which identify files and say nothing about compression; anything the index
produced; and row ids, because a UUIDv7 embeds the millisecond it was minted, so a list of
them is a timeline. Files are numbered from one. An error is a flag, never its message,
because an exception message quotes paths.

A test builds the report from an item whose every string is a distinctive sentinel and
asserts none of them appear anywhere in the output — which is what will catch the next field
added carelessly. The file's own header lists what it left out, so a user can read what they
are about to share.

The Android side writes into its own cache subdirectory, not the cache root: the root also
holds the encoder's temp files, which are copies of the user's originals mid-optimisation,
and a `FileProvider` pointed at the root would make one of those reachable.

### Fitting the threshold

PROJECT.md has carried this open since milestone 2 — the shipped XPSNR threshold comes from
software x265 on one 640×360 clip. `ThresholdFit` is the arithmetic that turns a measured
sweep into a per-bucket threshold, tested against the one real sweep that exists: it
reproduces the published 39.8 for VMAF 95 from milestone 2's table. It **refuses** to
extrapolate past the ends of a sweep, refuses a sweep that is not monotone (both metrics
measure the same thing badly and well, so one that does not rise is a broken measurement,
and fitting through it would bake the noise into a threshold governing the whole library),
and rounds the shipped value *up* — a tenth of a decibel too high costs a sliver of space,
a tenth too low costs quality on an app that sells "you will not see the difference".

### Numbers

763 shared JVM tests and 47 build-guard tests pass; the guards scan 167 source files clean.

## Milestone 12 — the AV1 path

BUILD.md § 10: *"HEVC via MediaCodec on all devices; AV1 where `MediaCodecList` reports a
hardware AV1 encoder."* The encoder side already spoke AV1 — `TransformerEncoder` has
mapped `VideoCodec.AV1` to its mime type since milestone 1. What was missing was everything
that decides *when* to use it, and two things that were quietly wrong once there were two
codecs to be wrong about.

### Two defects the second codec exposed

**`CodecCaps` carried one set of limits, read from the HEVC encoder, and applied them to
both.** Most phones that have an AV1 encoder at all top out below their HEVC ceiling —
commonly 4K30 against 4K60 — so a 4K60 clip passed a check against the wrong encoder's
capability and would have failed at encode time, after the whole probe and search. It is
now `EncoderCaps` per codec, and BUILD.md § 10's *"check `getSupportedPerformancePoints()`;
never request beyond advertised throughput"* is represented rather than aspirational. An
encoder that lists no points is treated as having said nothing, not as having said "no
limit": treating silence as permission is how a night ends up spending four hours on one
clip.

**`Predictor.Key` keyed on the source codec but not the output codec.** AV1 reaches the
same quality at roughly two thirds of HEVC's bitrate, so one family would have averaged the
two together and predicted a number too low for HEVC and too high for AV1 — worse than no
prediction at all, because a confident entry *narrows* the search bracket around it, and the
search would then spend its whole probe budget escaping a bitrate no file ever wanted. The
key and the `predictor` table now carry the output codec.

### When AV1 is used

`CodecChoice` is four rules, and only the first is about AV1 being good:

1. **An AV1 source is only ever re-encoded to AV1** — and skipped, with a reason, where AV1
   is unavailable. Triage counts AV1 above ~8 Mbps as a candidate (BUILD.md § 5), and taking
   such a file to HEVC would usually make it *larger* for the same picture: a night's
   battery spent to lose the user space. "HEVC is available" is not a consolation here.
2. **The device has to sustain it**, by its own ceiling and its own advertised performance
   points.
3. **It has to be fast enough to be worth the night.** BUILD.md § 6 caps the night in
   minutes, so an encoder at half real time turns a night that would have cleared four
   hours of video into one that clears one — a bigger saving per file and a smaller one per
   night. The guard uses what this device actually measured (`Job.realtimeMultiple`), needs
   five samples before it believes them, and never demotes an encoder it has not measured.
4. **The user has to have chosen it**, with Pro and with the setting on. Checked here as
   well as in `SettingsPolicy`, rather than trusted: a codec choice that silently depended
   on something else having sanitised first is one refactor from encoding a free user's
   library into a format they did not buy.

The Settings explanation now says what AV1 costs as well as what it saves — *"older phones,
TVs and cars may not be able to play them"*. The file stays perfectly playable on the phone
that made it; the cost only shows up when it is shared, which is exactly the kind of cost a
setting must not hide.

### Where the search starts

`CodecLadder` gives the fallback bracket a home. It was the caller's to invent, which was
survivable with one output codec: with two it is not, because a bracket built for HEVC opens
an AV1 search a third too high and converges downwards for every probe it has. The bracket
comes off the source's own bitrate rather than its resolution — the source bitrate is the
one number that already knows whether the footage is a talking head or a handheld shot of a
forest — and the queue's saving estimate and the search's opening bid are now the same
number, so what the user is promised and what the search goes looking for cannot drift.

The XPSNR thresholds are the **measured** ones from milestone 2's calibration sweep, not
invented constants: VMAF 95 interpolates to XPSNR y 39.8 and VMAF 90.035 was measured
directly at 36.0. AV1 returns the same values, and that is a placeholder rather than a
finding — XPSNR is a proxy for VMAF and the mapping depends on what the artefacts look
like, which AV1's and HEVC's do not do alike. The table is keyed by codec so the measurement
has somewhere to land, the calibration harness now sweeps either encoder, and PROJECT.md
records it as open. It needs an ffmpeg with SVT-AV1, which this environment does not have,
and properly it needs the device fleet from milestone 13.

### Numbers

710 shared JVM tests and 47 build-guard tests pass; the guards scan 160 source files clean.

## Milestone 11 — the editor

BUILD.md § 9: *"crop, rotate, straighten, light/colour sliders, a few filters, video trim.
Non-destructive; original kept."* ARCHITECTURE.md § 15 lists this milestone as Compose
with no platform work, which is true of the pixels and untrue of everything that decides
what happens to them — so the decisions are here, in shared Kotlin, with tests.

### Doing as little as possible

The editor's most valuable decision is not which pixels to change but **whether to touch
the pixels at all**, and three of the things a user does here need no encoder:

- **A rotate or a flip is a tag.** EXIF, HEIF and MP4 all carry an orientation, so turning
  a photograph — the most common edit in any gallery — is a few bytes written, instantly
  and losslessly.
- **A trim that starts on a keyframe is a container cut.** The original frames move into a
  new file untouched. Only the *start* matters: frames at the head of a cut that begins
  mid-group reference an I-frame that is no longer in the file, while the end may fall
  anywhere, because truncating the last group loses only frames the user asked to lose.
  When the start is close to a keyframe the editor offers the shift — *"starting 180 ms
  earlier keeps the original quality and saves the wait"* — and only ever offers it
  **backwards**, because snapping forward would drop footage the user chose to keep.
- **An edit undone back to nothing is nothing**, including four rotations, a filter at zero
  strength, and sliders the user cancelled by hand.

That matters more here than in most photo apps. Re-encoding a clip this app already
optimised puts a second generation of loss on the first, and two generations are visible.

### Geometry

`Orientation` is the dihedral group of eight — four quarter-turns, each optionally
mirrored — written as one closed set rather than a rotation field beside a mirror flag.
With two independent fields, "rotate right, then mirror, then rotate right" has whichever
answer the call site happened to compute; here it has an answer, and the tests assert
associativity, inverses, and that mirroring reverses the sense of a rotation. The EXIF tag
round-trips for all eight values, 5 and 7 included — get those backwards and a minority of
photographs come out upside down.

`CropGeometry` holds the straighten fit, which is the number that decides whether
straightening shows a grey corner. A centred W×H frame rotated by θ fits inside a w×h
picture exactly when its bounding box does, which gives two bounds on W and the smaller
wins. A property test asserts the crop fits for four source shapes × four crop shapes ×
five angles, and a second asserts it is the *largest* one that does — a crop 0.1% bigger
must not fit, or the editor is zooming further in than it has to.

An aspect-locked drag that overflows the picture shrinks about its own centre and slides
back inside. Clamping it edge-by-edge, which is what the free drag does, would crop one
side to the boundary and silently leave the lock the user chose behind.

### Adjustments and filters

Eight sliders, all running −1 to +1 with 0 meaning "leave it alone" rather than each
carrying its own natural units. That flattening makes "is this edit doing anything?" one
check, makes a filter a vector that can be scaled, and makes reset the same code
everywhere.

**A filter is a set of slider positions, not a look-up table.** Picking a filter and then
adjusting still works, because both live on the same eight sliders; strength 0 is exactly
the identity and 0.5 exactly half, by arithmetic rather than a second interpolation path
that could disagree with the first; and nothing has to ship a LUT. The cost — a filter
cannot do anything the sliders cannot — is the right trade for "a few filters", and is
written down rather than discovered later.

Writing the tests turned up a small real defect: a filter at 0.3 plus a slider at −0.1
landed on 0.19999999999999998, so two edits that should compare equal did not, and an
adjustment a user had cancelled by hand could fail `isNeutral` and write a file for an edit
that does nothing. Slider positions are now quantised to a millionth — far below anything a
user can express, and enough to make the type's equality mean something.

### Saving

Two of the optimiser's four gates are *wrong* for an edit, and dropping them is as
dangerous as keeping them:

- **Not "must be smaller".** A crop re-encoded may be larger than what it replaced.
  Refusing it would be refusing to do what the user asked.
- **Not VMAF ≥ 95.** There is no reference; the output is *meant* to differ.
- **Still openable, still the expected duration, and the original's size and mtime still
  where they were.** "The edit path skips verification" is one sentence away from "the edit
  path replaces a file with a truncated one".

A re-encoded save-over sets `optimisedAt`, which is literally what that column means —
*when this app last replaced this file* — so the night pass does not add a second
generation. No `Job` row is written and no saving is claimed, because none was measured. A
rotate or a keyframe cut moves the original bytes, so it leaves the optimisation state
exactly as it found it; marking those would cost the user the saving.

A save invalidates the index, but not uniformly. A rotation keeps its meaning — same faces,
same words, same labels, because every detector works in the upright frame — and loses only
its **hash**, which is built on a grid of pixels that a turn permutes. Everything else
changes the content: a crop can remove a face outright, a trim the frames a label came from.

A bug the tests caught: with the source duration unknown, a user's trim evaluated as *no
edit at all* and would have been silently discarded — on exactly the files whose metadata is
already unreliable. The safe reading of "I don't know" is the one that keeps what the user
asked for.

### `Replacer.saveCopy`

"Save (new copy, original kept)" writes a new file into a granted folder, and
ARCHITECTURE.md § 14 allows exactly one component in this app to write there. That method
did not exist — it was an open question from milestone 10, where "Keep both" needs the same
thing — so it is now on `Replacer` and implemented in `SafeReplacerAndroid`, inside the
file the build guard names. Giving an add its own path would have meant a second writer, and
an allow-list that grows to fit the code has stopped being a guard.

It deliberately takes none of the replace machinery: nothing is parked, nothing is
snapshotted, no undo row is written, because nothing is at risk. The one rule it owes is
that a failed write leaves nothing behind — a half-written photograph in the user's gallery
is worse than none, since they cannot tell it from a real one.

### Numbers

676 shared JVM tests and 47 build-guard tests pass; the guards scan 156 source files clean.

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
