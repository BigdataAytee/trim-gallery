package app.trimgallery.core.pipeline.replace

import app.trimgallery.core.model.MediaRef
import app.trimgallery.core.model.UndoEntry
import app.trimgallery.core.model.UndoState
import app.trimgallery.engine.TempFile

/**
 * The steps of the ARCHITECTURE.md § 7 replace contract that § 5 has no interface for.
 *
 * Copying metadata is `MetadataCopier`, parking the original is `UndoStore`, reading is
 * `LibraryStorage` — those already exist. What is left is the commit itself and the
 * tidying either side of it, and it lives here so that [ReplaceSequence] can be a single
 * shared implementation of the ordering, tested on a JVM with fakes (ARCHITECTURE.md
 * § 14) rather than written twice and tested on neither.
 *
 * **Implementations of this interface are inside the write boundary.** On Android every
 * method below is implemented in `SafeReplacerAndroid.kt`, which is one of the four files
 * the `verifySourceBoundaries` guard permits to write to a granted tree.
 */
interface ReplaceOps {

    /**
     * Puts [replacement] into the library under the identity [under] just vacated —
     * same directory, same name.
     *
     * The original must already be parked when this is called; committing first would
     * mean two files claiming one identity, and on Android a rename onto an occupied name
     * either fails or silently invents `foo (1).mp4`.
     */
    suspend fun commit(replacement: TempFile, under: MediaRef): Committed

    /** Removes a committed replacement again, freeing the identity for the original. */
    suspend fun uncommit(committed: Committed)

    /**
     * Sets the committed file's modification time back to the original's.
     *
     * Skipping this sorts the user's whole library to "just now" the morning after the
     * first night — the single most visible way this app could damage a photo collection
     * without losing a byte.
     */
    suspend fun restoreTimestamps(committed: Committed, mtime: Long)

    /** `MediaScannerConnection.scanFile` on Android; PhotoKit does it itself on iOS. */
    suspend fun notifyLibrary(committed: Committed)
}

/** A replacement now living in the library under the original's identity. */
data class Committed(val ref: MediaRef, val size: Long)

/**
 * Where `UndoEntry` rows are made durable.
 *
 * Separate from `UndoStore`, which moves bytes: § 7 parks the original in the middle of
 * the sequence but writes the row at the *end*, and the safe-replace skill is explicit
 * that the row must exist "before the user can see the space as freed". Two collaborators
 * make that ordering something the code states rather than something a comment claims.
 */
interface UndoJournal {
    /**
     * Persists [entry] and returns it with whatever the store assigned.
     *
     * The last step of the § 7 contract. Nothing may report space as freed before this
     * returns, which is why `ReplaceSequence` unwinds the whole swap if it throws: an
     * optimised file with no undo row is one the user cannot get back.
     */
    suspend fun record(entry: UndoEntry): UndoEntry

    /** Removes a row entirely. */
    suspend fun forget(entry: UndoEntry)

    /**
     * Rows the sweep should consider: `ACTIVE`, with a deadline, at or past [nowEpochMs].
     *
     * `TrashPolicy` still has the final say on each one — this is the query, not the
     * decision.
     */
    suspend fun expiring(nowEpochMs: Long): List<UndoEntry>

    /** Moves a row through `ACTIVE → RESTORED | EXPIRED | OFFLOADED`. */
    suspend fun setState(entry: UndoEntry, state: UndoState)
}

/**
 * Finds the library identity a parked original belongs to.
 *
 * Restoring means putting the file back under the name and directory it came from, and
 * after a successful replace that identity is exactly `media_item.platform_ref`: the
 * replacement took the original's place, so the reference still names the slot. Reading it
 * from the database rather than remembering it in memory is what lets a restore work after
 * the process has been killed, which on a night pass is the normal case rather than the
 * exception.
 */
fun interface OriginalLocator {
    /** Where [mediaId] lives in the user's library, or null if the row is gone. */
    suspend fun refFor(mediaId: String): MediaRef?
}
