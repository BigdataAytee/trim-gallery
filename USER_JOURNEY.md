# USER_JOURNEY.md — Screens, states and flows

Five screens and a share-sheet entry. The user's own gallery is the front end; this app is
the thing that makes their files smaller.

## 1. First run
1. **Welcome**: one sentence — "Trim shrinks big videos and photos on your phone. Nothing leaves it."
2. **Choose a folder** → SAF picker (`ACTION_OPEN_DOCUMENT_TREE`), defaults to `DCIM/Camera`. One system dialog. No media permission is requested: the grant *is* the access.
3. **Originals choice** sheet: Keep / Offload (if SD/USB detected) / Free after 30 days. Default preselected, one line of explanation each.
4. **Home**, with "Find big files" ready and "Tonight, when you're charging, Trim will start."

Edge: picker cancelled → Home shows its empty state with "Choose a folder", and does not nag.

## 2. Home
Primary action **Find big files**. Below it: total freed to date, next scheduled run, and the master on/off toggle. After a night run, the morning result sits here — "Freed 6.2 GB · 14 videos, 210 photos" — dismissible.

This screen is designed to be visited rarely. A user who never opens it after setup is a success, not a churn risk.

## 3. Big files
The scan result, largest estimated saving first. Each row: name, current size, estimated new size, saving. **Trim one**, **trim selected**, **trim all**.

Second section: **Large but can't be trimmed**. Big files that will not shrink, each with its reason — already efficient, HDR, Motion Photo, RAW, document, APK. The user still finds out where their storage went, so a scan that compresses nothing is still worth having run.

Progress is per-file and in place. Result on each row: "Now 165 MB (was 380 MB)" with Keep and Undo.

## 4. Folders
Add and remove granted folders. Per-folder mode: Keep originals / Offload originals / Free space after N days, each explained in one line. This is also where **Scan my whole phone** lives: tapping it opens a plain explanation of All files access — what it grants, why the app wants it, and that everything already works without it — before any system dialog appears. Declining leaves the app fully functional.

## 5. The night (no UI)
Charging + idle (+ full) → work starts. Unplug → stops within seconds. Morning notification: "Freed 6.2 GB · 14 videos, 210 photos". Work pauses whenever the app is in the foreground.

## 6. History
Every run, with before and after sizes and what was freed. **Restore** while inside the undo window, with the original's location named (bin / SD card). If expired: "The original was removed on <date> after the 30-day window", with a link to change retention. The **Skipped** list with reasons sits at the bottom, and "Try again" where a retry makes sense.

**On iOS, "Free space" folders restore differently, and the UI has to say so.** There is no
rename in PhotoKit, so a replacement is add-then-delete and the original lands in the
system's own Recently Deleted for 30 days — which is the undo bin, and is also the one place
this app cannot reach: there is no API to restore from it, by design. So for those files:

- The History row shows the state distinctly — not as a Restore button that would fail.
- Tapping it opens an explanatory sheet: *"Your original is in Photos, under Recently
  Deleted, until <date>. Restore it from there."*
- The sheet's action is **Open Photos** (`photos-redirect://`). There is no deep link to
  Recently Deleted itself, which is why the copy names the album rather than only the app.

**Keep originals and Offload are one tap on iOS as on Android**, because those originals are
in the app's own storage or on a volume the user picked — not in the system bin. The
difference is the folder mode, not the platform.

## 7. Share sheet — trim one video from any gallery
The user is in their own gallery, picks a video, taps Share, chooses Trim. A single screen:
estimated size → **Trim** → progress → "Now 165 MB (was 380 MB)" with **Save a copy** and
**Share**.

**The original is never touched.** The file arrived as a `content://` URI owned by another
app, so there is no safe replace to be had and none is offered — the result is a new file,
and the screen says so in a line the user cannot miss. Allowed on battery, because it is an
explicit tap (BUILD.md § 2 rule 1); "Uses battery" is shown once.

## 8. Settings
Quality (Standard 95 / Compact 90 with warning, photo format) · Schedule (start when full, stop by time, nightly cap, keep working while using) · Undo retention · About (version and commit SHA) · Export diagnostics · the "no internet permission" explainer, stated as a feature · Pro.

## 9. Free-tier cap
Home shows "3 GB of 3 GB freed this month · resets in 12 days". When hit: a card with the Pro offer; background work pauses. Never blocks restore, and never blocks the scan — finding out what is big stays free.

## 10. Error and empty states
No folders granted: "Choose a folder to get started." · No candidates: "Everything's already efficient — nothing to trim." (with the can't-be-trimmed list still shown, because that is the useful half) · Storage low: "Need 2 GB free to work safely." · Thermal: shown only in History as "Paused for heat 3× last night". · Failed file: listed under Skipped with reason and "Try again".

## 11. Uninstall / data
Uninstall keeps the user's files in place; undo bin contents in app storage are lost — warn once in Settings and offer "Empty bin to originals first".
