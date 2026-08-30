---
name: safe-replace
description: The rules for replacing an original photo or video with an optimised one in trim-gallery — originals stay read-only until one final rename, size+mtime change detection, verification before replace, undo bin / offload, metadata and lastModified preservation, and MediaStore rescan. Use whenever writing or reviewing code that writes, moves, renames or deletes a user's media file.
---

# Safe replace

The user handed this app their only copy of their photos. Every rule here exists because breaking it loses something irreplaceable.

**The invariant: an original file is opened read-only, and the only write that ever touches its path is the single final rename.** No in-place truncation, no "write then fix up", no partial writes to the original path. If the process dies at any instant, either the original is intact or the verified replacement is fully in place. Nothing in between exists.

## The order, and it is not negotiable

1. **Record** source `size` and `lastModified` before the encode starts, plus the container metadata to be carried over.
2. **Encode to a temp file in app storage.** Never next to the original, never in the user's folder.
3. **Re-check size and mtime.** If either changed while the encode ran, the user edited the file — discard the temp file and requeue the job. (PROJECT.md § Files in use.)
4. **Verify.** VMAF ≥ 95 on three 5-second windows (start, middle, end), `vmaf_v0.6.1`, 1080p, `n_subsample=10`; and the output opens and reports the **full duration**. Below threshold: step up one notch and re-encode, at most twice, then log as failed and skip permanently. Photos gate on SSIMULACRA2 ≥ 85–90. Lossless paths (PNG repack, JPEG XL recompress) need no gate.
   *A file that fails verification is never replaced. Not "probably fine", not "close enough".*
5. **Sanity-check size.** If the output is not meaningfully smaller than the original, there is nothing to gain — discard and mark skipped. Never replace a file with a larger one.
6. **Copy metadata onto the temp file:** creation time, location/GPS, rotation, colour info; EXIF and XMP wholesale for photos. BUILD.md § 2 rule 4: date, GPS, rotation, HDR flags, file name and on-disk location all survive.
7. **Move the original out of the way** — same volume, so it is an instant rename, not a copy — into the undo bin or the offload target per the folder's mode. The original is *moved*, never deleted, at this step.
8. **Rename the temp file onto the original path.** This is the one write to that path. Same name, same directory (BUILD.md § 2 rule 4).
9. **Reset `lastModified`** on the new file to the original's value. Sort order in every gallery, ours included, depends on it.
10. **`MediaScannerConnection.scanFile`.** Replacing a file by rename to the same path usually preserves the MediaStore row (PROJECT.md § Codec facts); the rescan makes it certain.
11. **Record the result**: `Job` row (sizes, xpsnr, vmaf, engine, setting, timings), `UndoEntry` (bin path or offload URI, `expiresAt`), and update the `Predictor` table.

If any step after 7 fails, **roll back**: move the original back to its path from the bin/offload target, delete the temp file, log the failure.

## Folder modes (BUILD.md § 6)

| Mode | Where the original goes | When space is freed |
|---|---|---|
| Keep originals | Undo bin, never expires | Only when the user empties the bin |
| Offload originals | SD card / USB via SAF, when present | Immediately — default where external storage exists |
| Free space | Undo bin for N days (default 30) | On expiry — default otherwise; show the warning once |

Offload is a real copy across volumes, so it is *copy → verify the copy → then* remove the source. A cross-volume "move" that deletes before confirming the destination write is a data-loss bug.

## SAF specifics

- Grants come from `ACTION_OPEN_DOCUMENT_TREE` with **persistable** read/write URI permission taken and re-taken on boot.
- Use `DocumentsContract.renameDocument` / `moveDocument` for the rename and the move; `SimpleStorage` wraps the ugly parts.
- A `DocumentFile` can go stale. Re-resolve the document URI immediately before the rename rather than trusting one captured minutes earlier during the encode.
- Never assume a filesystem path. Some grants have no usable `File`.

## Things that must never appear in this code

- `File.delete()` / `DocumentsContract.deleteDocument` on an original, anywhere outside undo-bin expiry and the user's own explicit delete action.
- Opening an original with any write mode (`"w"`, `"rw"`, `"wa"`).
- Replacing before verification, or on a verification failure.
- Writing the temp file into the user's folder.
- Overwriting a file whose size or mtime changed during the encode.
- Losing EXIF/XMP or container metadata, or leaving `lastModified` at "now".

## Reversibility, stated honestly

Compression here is **visually lossless, not lossless** — data is discarded and the app must never claim otherwise (PROJECT.md § Quality and reversibility). Reversibility comes entirely from keeping the original: undo bin, offload, or a delete-after-window. That is why the bin is not a nicety and why "Free space" mode shows its warning.

## Review checklist

- [ ] Original opened read-only; exactly one write to its path, and it is a rename
- [ ] size + mtime captured before, re-checked after
- [ ] Verification passes before any move or rename; failures never replace
- [ ] Output confirmed smaller and openable at full duration
- [ ] Metadata copied; `lastModified` reset; `scanFile` called
- [ ] Original moved (same volume) or copied-then-verified (offload) — never deleted outright
- [ ] Rollback path restores the original on any failure after the move
- [ ] `UndoEntry` written before the user can see the space as freed
