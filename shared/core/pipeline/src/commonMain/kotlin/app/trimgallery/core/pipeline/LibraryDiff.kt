package app.trimgallery.core.pipeline

import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaRef
import app.trimgallery.core.model.MediaStatus

/**
 * What changed in the user's library since the last scan.
 *
 * ARCHITECTURE.md § 7 opens the night with `storage.scan(grants) → DB diff (new/changed/
 * removed)`, and ARCHITECTURE.md § 9 gives the rule that makes it matter:
 * `DONE/SKIPPED/FAILED → NEW when the file changes`. Without that, a video the user
 * re-edited would keep its old verdict forever; with it applied too eagerly, the app
 * re-optimises its own output every night and the picture degrades one generation at a
 * time.
 *
 * Pure arithmetic over two lists, so both of those can be tested rather than hoped for.
 */
object LibraryDiff {

    /** One item's fate. */
    sealed interface Change {
        val stored: MediaItem?

        /** Not in the database. Enters at `NEW` and goes through triage tonight. */
        data class Added(val scanned: MediaItem) : Change {
            override val stored: MediaItem? get() = null
        }

        /**
         * Size or mtime moved. The old verdict describes a file that no longer exists.
         *
         * Carries both sides: the caller keeps the stored row's identity and index data,
         * and takes the new size and mtime from the scan.
         */
        data class Modified(override val stored: MediaItem, val scanned: MediaItem) : Change

        /** Gone from every granted folder that was scanned. */
        data class Removed(override val stored: MediaItem) : Change

        /** Present and identical. Most of the library, most nights. */
        data class Unchanged(override val stored: MediaItem, val scanned: MediaItem) : Change
    }

    data class Result(
        val added: List<MediaItem>,
        val modified: List<Change.Modified>,
        val removed: List<MediaItem>,
        val unchanged: List<Change.Unchanged>,
    ) {
        /** Everything that needs re-triaging tonight. */
        val needsTriage: List<MediaItem>
            get() = added + modified.map { it.scanned }

        val isEmpty: Boolean get() = added.isEmpty() && modified.isEmpty() && removed.isEmpty()
    }

    /**
     * @param stored every row the database holds.
     * @param scanned everything the scan found.
     * @param scannedGrants the grants this scan actually covered.
     *
     * [scannedGrants] is not optional bookkeeping. A user with two granted folders whose SD
     * card is out has only one of them scanned, and a diff that did not know which would
     * report every photo on the card as removed and delete their index, their labels and
     * their faces. Only rows belonging to a grant that was scanned may be reported
     * [Change.Removed]; anything else is simply absent from this diff.
     */
    fun diff(stored: List<MediaItem>, scanned: List<MediaItem>, scannedGrants: Set<String>): Result {
        val storedByRef: Map<MediaRef, MediaItem> = stored.associateBy { it.platformRef }
        val seen = mutableSetOf<MediaRef>()

        val added = mutableListOf<MediaItem>()
        val modified = mutableListOf<Change.Modified>()
        val unchanged = mutableListOf<Change.Unchanged>()

        for (item in scanned) {
            seen += item.platformRef
            val existing = storedByRef[item.platformRef]
            when {
                existing == null -> added += item
                changed(existing, item) -> modified += Change.Modified(existing, item)
                else -> unchanged += Change.Unchanged(existing, item)
            }
        }

        val removed = stored.filter { row ->
            row.platformRef !in seen && row.folderGrantId != null && row.folderGrantId in scannedGrants
        }

        return Result(added, modified, removed, unchanged)
    }

    /**
     * Whether the bytes behind a row moved.
     *
     * Size **or** mtime, because either alone is defeatable: an edit that happens to
     * preserve the length changes only the timestamp, and a filesystem that rounds
     * timestamps to two seconds hides a quick edit but not a size change.
     *
     * This is also the check that must *not* fire after our own replace. The pipeline
     * writes the new size and mtime back to the row as part of recording the result, so the
     * next scan compares like with like. `MediaItem.optimisedAt` is the second line of
     * defence for when a provider rewrites a timestamp behind us.
     */
    private fun changed(stored: MediaItem, scanned: MediaItem): Boolean =
        stored.size != scanned.size || stored.mtime != scanned.mtime

    /**
     * The row to write for a modified file.
     *
     * The direction matters and is easy to get backwards. **The scan is the truth about the
     * file; the database is the truth about our bookkeeping.** So everything describing the
     * bytes — codec, resolution, frame rate, bitrate, duration, size, timestamps — comes
     * from the scan, and only identity and the user's own decisions survive from the row.
     *
     * The first version of this kept the stored codec and bitrate and a test caught it: a
     * clip re-exported from an editor is a different file in the same place, and triaging
     * it on the old container's numbers gives a verdict about a file that no longer exists.
     *
     * Index-derived fields are cleared, because they describe pixels that have changed:
     * keeping a perceptual hash from the previous version would put the file in the wrong
     * duplicate group and, worse, keep it out of the right one.
     */
    fun merge(stored: MediaItem, scanned: MediaItem, nowMs: Long): MediaItem = scanned.copy(
        // Identity, so the file keeps its place in the user's albums and its history.
        id = stored.id,
        folderGrantId = stored.folderGrantId ?: scanned.folderGrantId,
        createdAt = stored.createdAt,

        // The user's own decisions are not properties of the bytes: a re-edited photo must
        // stay in the locked folder and stay favourited. Everything else in the mask
        // describes the container, so it comes from the scan.
        flags = scanned.flags.copy(
            favourite = stored.flags.favourite,
            hidden = stored.flags.hidden,
        ),

        // The verdict described a file that no longer exists.
        status = MediaStatus.NEW,
        skipReason = null,
        estSaving = null,

        // These describe pixels that have changed.
        phash = null,
        sha256 = null,

        // Whatever we did to the old file, the file the user has now is not ours.
        optimisedAt = null,

        updatedAt = nowMs,
    )
}
