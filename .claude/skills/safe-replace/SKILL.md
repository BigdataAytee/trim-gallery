---
name: safe-replace
description: The Replacer contract in Trim Gallery — the single component allowed to write to a user's library, on Android (SAF rename) and iOS (PhotoKit change request). Covers the ordered replace steps, reverse rollback, size+mtime snapshot checks, verification gates, undo bin / offload / system trash, and metadata preservation. Use whenever writing or reviewing code that writes, moves, renames or deletes a user's media.
---

# Safe replace

The user handed this app their only copy of their photos. Every rule here exists
because breaking it loses something irreplaceable.

**The invariant (ARCHITECTURE.md § 2.2): originals are read-only until the single
atomic replace in the platform `Replacer`.** No in-place truncation, no "write then fix
up", no partial writes to the original. If the process dies at any instant, either the
original is intact or the verified replacement is fully in place. Nothing between those
two states exists.

## One door out: `Replacer`

```kotlin
interface Replacer { suspend fun replace(plan: ReplacePlan): ReplaceResult }   // the only writer
```

`Replacer` is the **only** component in the app that writes to a granted folder or the
photo library. A build guard enforces it (ARCHITECTURE.md § 14): writes to a granted
tree from anywhere but `SafeReplacerAndroid` / `SafeReplacerIos` fail the build.

Everything else — pipeline steps, indexers, thumbnailers, the UI — gets read-only
access through `LibraryStorage.openRead`, and writes temporary work through
`LibraryStorage.tempFile()`, which is app-private. A helper that "just" fixes up a
filename in the user's folder is exactly the bug this guard is for.

## The contract, in order

ARCHITECTURE.md § 7 fixes the sequence. It is not negotiable and not reorderable:

> copy metadata → park original → commit replacement under original identity →
> restore timestamps → notify library → write UndoEntry.
> Any failure rolls back in reverse; the original is never lost.

Around it, the pipeline (`VideoOptimiseStep`) is responsible for the gates:

1. **Snapshot** `size` and `mtime` before the encode starts.
2. **Encode to a temp file** in app-private storage. Never beside the original.
3. **Verify.** VMAF ≥ 95 on three 5-second windows (start, middle, end),
   `vmaf_v0.6.1`, 1080p, `n_subsample=10`; output opens and reports the **full
   duration**. Below threshold: step up one notch and re-encode, **at most twice**,
   then `FAILED` / `SKIPPED("could not reach quality")`. Photos gate on
   SSIMULACRA2 ≥ 85–90. Lossless paths (PNG repack, JPEG XL recompress) need no gate.
   *A file that fails verification is never replaced. Not "probably fine".*
4. **Confirm it is smaller.** Never replace a file with a larger one; discard and mark
   skipped.
5. **Re-check the snapshot.** If `size` or `mtime` moved while the encode ran, the user
   edited the file: discard the temp, set the item back to `NEW`, requeue. (On iOS also
   re-check `PHAsset.modificationDate`.)
6. **Then, and only then,** `Replacer.replace(plan)`.

If any step inside the Replacer fails, **roll back in reverse order** — un-park the
original, delete the temp, record the failure, surface it in Space. The original is
never lost.

## Platform mechanics

| | Android | iOS |
|---|---|---|
| Commit | `DocumentsContract.renameDocument` over the original path — same name, same directory | `PHAssetChangeRequest.creationRequestForAssetFromVideo(at:)` then `deleteAssets` of the original, in one transactional change block |
| Park original | app-owned bin dir, or offload to SD/USB | system Recently Deleted (FREE) · app Documents (KEEP) · `UIDocumentPicker` volume (OFFLOAD) |
| Identity carried | creation time, GPS, rotation, colour info; EXIF/XMP wholesale | creationDate, location, favorite, **album membership** |
| Timestamps | reset `lastModified` to the original's value | `creationDate` on the change request |
| Notify | `MediaScannerConnection.scanFile` | PhotoKit does it |

Android: the move to the bin is a **same-volume rename**, so it is instant. Offload
crosses volumes and is therefore *copy → verify the copy → then* remove the source; a
cross-volume "move" that deletes before confirming the destination write is a
data-loss bug.

Renaming over the original path usually preserves the MediaStore row (PROJECT.md
§ Codec facts); the rescan makes it certain.

iOS: PhotoKit has no rename. "Replace" is add-then-delete inside one change block, and
the deleted original lands in Recently Deleted, which *is* the undo bin for 30 days.
Album membership has to be re-applied explicitly or the file leaves the user's albums.

## SAF specifics (Android)

- Grants come from `ACTION_OPEN_DOCUMENT_TREE`, with **persistable** read/write URI
  permission taken and re-taken on boot.
- A `DocumentFile` goes stale. Re-resolve the document URI immediately before the
  rename rather than trusting one captured minutes earlier during the encode.
- Never assume a filesystem path exists; some grants have no usable `File`.

## Undo

`UndoEntry(id, mediaId, location{BIN,OFFLOAD,SYSTEM_TRASH}, ref, expiresAt, state)`,
states `ACTIVE → RESTORED | EXPIRED | OFFLOADED`. Write it **before** the user can see
the space as freed. Folder modes (BUILD.md § 6): *Keep originals* (never expires),
*Offload originals* (default where external storage exists), *Free space* (N days,
default 30, warning shown once).

## Things that must never appear

- A write, rename, move or delete on a granted folder outside `Replacer`.
- Opening an original with any write mode (`"w"`, `"rw"`, `"wa"`).
- Deleting an original outright — it is parked, never deleted, at replace time.
- Replacing before verification, or after a verification failure.
- Writing a temp file into the user's folder.
- Overwriting a file whose size or mtime changed during the encode.
- Losing EXIF/XMP, container metadata or album membership; leaving `lastModified` at
  "now".

## Reversibility, stated honestly

Compression here is **visually lossless, not lossless** — data is discarded and the app
must never claim otherwise (PROJECT.md § Quality and reversibility). Reversibility comes
entirely from keeping the original. That is why the bin is not a nicety, and why "Free
space" mode shows its warning.

## Review checklist

- [ ] The write goes through `Replacer` and nothing else
- [ ] Original opened read-only; one atomic commit under the original identity
- [ ] size + mtime snapshotted before, re-checked after
- [ ] Verification passes before any park or commit; failures never replace
- [ ] Output confirmed smaller and openable at full duration
- [ ] Metadata copied; timestamps restored; library notified
- [ ] Original parked (same-volume rename) or copied-then-verified (offload)
- [ ] Rollback reverses every completed step on failure
- [ ] `UndoEntry` written before the space is reported as freed
