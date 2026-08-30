package app.trimgallery.core.pipeline.replace

import app.trimgallery.core.model.UndoEntry
import app.trimgallery.engine.LibraryStorage
import app.trimgallery.engine.MetadataCopier
import app.trimgallery.engine.NewCopyPlan
import app.trimgallery.engine.NewCopyResult
import app.trimgallery.engine.ReplacePlan
import app.trimgallery.engine.ReplaceResult
import app.trimgallery.engine.Replacer
import app.trimgallery.engine.UndoStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The one implementation of the ARCHITECTURE.md § 7 replace contract.
 *
 * > copy metadata → park original → commit replacement under original identity →
 * > restore timestamps → notify library → write UndoEntry.
 * > Any failure rolls back in reverse; the original is never lost.
 *
 * The ordering is the whole point, so it is written once, here, in shared code, and both
 * `SafeReplacerAndroid` and `SafeReplacerIos` delegate to it rather than re-implementing
 * it. That is what makes ARCHITECTURE.md § 14's *"Replacer plan/rollback with fake
 * storage"* a JVM unit test instead of a device test nobody runs.
 *
 * Two properties this class exists to guarantee:
 *
 * 1. **The original is never lost.** It is parked, never deleted, and every step after
 *    the park has an inverse that runs on failure, in reverse order.
 * 2. **Rollback is uncancellable.** A night pass can be cancelled at any instant
 *    (ARCHITECTURE.md § 13, and iOS may kill the task outright). Unwinding inside a
 *    cancelled coroutine would abandon the sequence exactly halfway — original in the bin,
 *    nothing in the library — so the unwind runs under [NonCancellable] and the
 *    cancellation is rethrown afterwards.
 */
class ReplaceSequence(
    private val storage: LibraryStorage,
    private val metadata: MetadataCopier,
    private val undo: UndoStore,
    private val journal: UndoJournal,
    private val ops: ReplaceOps,
) : Replacer {

    /** One completed step and how to take it back. */
    private class Step(val name: String, val undo: suspend () -> Unit)

    /**
     * Adding a file, which has no ordering to enforce and so is a straight delegation.
     *
     * It passes through here rather than around here because `Replacer` is the interface the
     * build guard is written against: one component writes to a granted folder, whatever
     * kind of write it is. There is nothing to unwind — a failed add leaves the folder as it
     * was, and the platform owes only that it leaves no partial file behind.
     */
    override suspend fun saveCopy(plan: NewCopyPlan): NewCopyResult = ops.saveCopy(plan)

    @Suppress("ReturnCount")
    override suspend fun replace(plan: ReplacePlan): ReplaceResult {
        // The last possible moment to notice the file moved under us. The pipeline checks
        // this too (safe-replace skill, step 5), but between that check and this one lies
        // the whole of the caller's own bookkeeping; only a check taken here, immediately
        // before the first mutation, closes the window.
        val current = try {
            storage.stat(plan.original)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            return ReplaceResult.RolledBack("could not read the original: ${e.message}")
        }

        if (!current.exists || current.size != plan.expectedSize || current.mtime != plan.expectedMtime) {
            // Nothing has been touched, so there is nothing to unwind — but the temp file
            // describes a file that no longer exists and must not be left on disk.
            discardQuietly(plan)
            return ReplaceResult.SourceChanged
        }

        val done = ArrayList<Step>()

        try {
            // 1. Metadata onto the replacement, while it is still only a temp file.
            //    Doing this before the park means a metadata failure costs nothing: the
            //    original has not moved and there is nothing to put back.
            metadata.copy(plan.original, plan.replacement)

            // 2. Park the original. From here on the library has a hole in it, and every
            //    failure below must fill it again.
            val parked: UndoEntry = undo.park(plan.original, plan.undoLocation)
                .copy(mediaId = plan.mediaId)
            done += Step("park") { undo.restore(parked) }

            // 3. Commit the replacement under the identity the original just vacated.
            val committed = ops.commit(plan.replacement, plan.original)
            done += Step("commit") { ops.uncommit(committed) }

            // 4. Timestamps, so the library does not resort itself overnight.
            ops.restoreTimestamps(committed, plan.expectedMtime)

            // 5. Tell the OS media database, so the swap is visible to every other app.
            ops.notifyLibrary(committed)

            // 6. The undo row, last — and therefore before any caller can report the
            //    space as freed, because nothing here returns until it is written.
            val recorded = journal.record(parked)

            return ReplaceResult.Replaced(undoRef = recorded.ref, newSize = committed.size)
        } catch (e: CancellationException) {
            unwind(done, plan)
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            val failures = unwind(done, plan)
            val cause = e.message ?: e::class.simpleName ?: "unknown failure"
            return ReplaceResult.RolledBack(
                if (failures.isEmpty()) cause else "$cause; ${failures.joinToString("; ")}",
            )
        }
    }

    /**
     * Takes back every completed step, most recent first.
     *
     * Each inverse is attempted even if an earlier one failed: a failed `uncommit` leaves
     * a stray file, which is untidy, while skipping the `park` inverse because of it would
     * leave the user's photo in a bin they were never told about. Untidy loses to lost.
     *
     * @return a description of every inverse that itself failed, for the result message.
     */
    private suspend fun unwind(done: List<Step>, plan: ReplacePlan): List<String> = withContext(NonCancellable) {
        val failures = ArrayList<String>()
        for (step in done.asReversed()) {
            try {
                step.undo()
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                failures += "could not undo ${step.name}: ${e.message}"
            }
        }
        discardQuietly(plan)
        failures
    }

    /**
     * Throws away the temp file. Best effort by design: a leftover temp is swept later and
     * is never a reason to report a rollback as having failed.
     */
    private suspend fun discardQuietly(plan: ReplacePlan) {
        try {
            storage.discard(plan.replacement)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("SwallowedException", "TooGenericExceptionCaught") e: Exception) {
            // Intentionally ignored; see above.
        }
    }
}
