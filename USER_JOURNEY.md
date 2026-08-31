# USER_JOURNEY.md — Screens, states and flows

## 1. First run
1. Splash → **Welcome**: one sentence, "Continue".
2. **Permission**: READ_MEDIA_VIDEO/IMAGES. Rationale line: "Trim needs to see your photos to show them. Nothing leaves your phone — the app has no internet permission."
3. **Grid appears immediately** (from MediaStore) so value is instant.
4. **Card at top**: "Want Trim to free up space overnight? Choose a folder." → Folder picker (SAF) defaults to DCIM/Camera → one system dialog.
5. **Originals choice** sheet: Keep / Offload (if SD/USB detected) / Free after 30 days. Default preselected. Explanation in one line each.
6. **Done card**: "Tonight, when you're charging, Trim will start. You'll see the result in the morning." Toggle "Also start now while plugged in" if currently charging.
Edge: permission denied → grid empty state with "Allow access". Folder picker cancelled → card stays, not nagged.

## 2. Daily use (gallery)
- Open → Photos grid (day view). Scroll, pinch to month/year, tap → Viewer (shared-element).
- Viewer: swipe, zoom, swipe-down to close, tap for chrome, swipe-up for Info (date, map, camera, "Optimised · was 380 MB, now 165 MB · Restore").
- Long-press thumbnail → select mode → share / delete / favourite / add to album / Compress now (video).
- Videos autoplay muted in grid on dwell; Motion Photos animate on long-press.
- Bottom bar: Photos · Albums · Search · Space.

## 3. The night (no UI)
Charging + idle (+ full) → work starts. Thumbnail progress rings visible if the user happens to open the app (work pauses while open). Unplug → stops within seconds. Morning notification: "Freed 6.2 GB · 14 videos, 210 photos".

## 4. Morning
Open → **Result card** at top of grid: numbers, energy estimate, "See what changed" → History list with before/after and Restore. Swipe to dismiss.

## 5. Restore
History or Viewer Info → Restore → confirmation sheet showing where the original is (bin / SD card) → restored in place, card "Original restored". If expired: "The original was removed on <date> after the 30-day window" with link to change retention.

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

## 6. Compress now
Viewer or long-press → Compress now → sheet with estimated size and time → Start. Options: "Watch while it works" (play-to-compress) or progress bar. End: "Now 165 MB (was 380 MB)" with Share / Replace original / Keep both. On battery: this is allowed; note "Uses battery" shown once.

## 7. Search
Search tab → type "beach", "dog", "receipt", "Mum", "2023", "Lagos" → results grid with chips for people/places/dates/text. Tap a person chip → People screen.

## 8. People & pets
Auto-clustered faces → grid of people. Tap → name it → merges/suggestions. Hide a person. Toggle off entirely in Settings > Privacy (deletes embeddings).

## 9. Cleanup
Space → Cleanup: **Duplicates** (groups, best copy pre-selected, "Keep this, move others to bin") and **Chat media** (per-app folder, sorted by size/age, bulk select, to bin). Both use undo bin.

## 10. Locked folder
Albums → Locked → biometric → hidden items. Move in from viewer menu. Hidden from grid, search and people.

## 11. Editor
Viewer → Edit → crop/rotate/straighten, light/colour, filters, video trim → Save (new copy, original kept) or Save over (goes to undo bin).

## 12. Free-tier cap
Space screen shows "3 GB of 3 GB freed this month · resets in 12 days". When hit: card in grid with Pro offer; background work pauses optimisation but indexing continues. Never blocks restore.

## 13. Settings
Folders (add/remove, mode per folder) · Quality (Standard/Compact, photo format, reversible mode) · Schedule (start when full, stop by time, nightly cap, keep working while using) · Privacy (face clustering, export diagnostics, "no internet permission" explainer) · Pro.

## 14. Error and empty states
No candidates: "Everything's already efficient — nothing to do tonight." · Storage low: "Need 2 GB free to work safely." · Thermal: shown only in History as "Paused for heat 3× last night". · Failed file: listed under Skipped with reason and "Try again".

## 15. Uninstall / data
Uninstall keeps the user's files in place; undo bin contents in app storage are lost — warn once in Settings > Privacy and offer "Empty bin to originals first".
