package app.trimgallery.core.pipeline.replace

import app.trimgallery.core.model.MediaRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Moving an original to another volume, in the only order that cannot lose it.
 *
 * From the safe-replace skill: *"Offload crosses volumes and is therefore copy → verify
 * the copy → then remove the source; a cross-volume 'move' that deletes before confirming
 * the destination write is a data-loss bug."*
 *
 * Every platform has a convenient `move` that does not do this — a rename that silently
 * degrades to copy-and-delete across a mount point, with no way to tell whether the
 * destination write completed. So the order lives here, in shared code, where a test can
 * assert it: the source delete is unreachable except through a verification that returned
 * true.
 *
 * SD cards are exactly the population this matters for. They are removed mid-write, they
 * are counterfeit and silently drop data past their real capacity, and they fill up. An
 * offload that trusted the copy would turn each of those into a deleted photograph.
 */
class OffloadMove(private val ops: Ops) {

    /** The platform half. On Android this is SAF on both volumes; on iOS a file provider. */
    interface Ops {
        /**
         * Copies [source] into [destination], returning where it landed.
         *
         * Must not remove or modify [source]. Implementations that can should write under
         * a temporary name and rename on completion, so that an interrupted copy cannot be
         * mistaken for a finished one by anything that later scans the card.
         */
        suspend fun copy(source: MediaRef, destination: MediaRef): MediaRef

        /**
         * Confirms [copy] is a faithful reproduction of [source].
         *
         * Size at minimum; a content digest where the platform can afford one. Returning
         * true here is what authorises the delete below, so a cheap implementation that
         * always returns true is the bug this class exists to prevent.
         */
        suspend fun verify(source: MediaRef, copy: MediaRef): Boolean

        /** Removes a copy that failed verification, so the card does not fill with rubble. */
        suspend fun removeCopy(copy: MediaRef)

        /** Removes the source. Reached only after [verify] returned true. */
        suspend fun removeSource(source: MediaRef)
    }

    sealed interface Outcome {
        /** The original now lives at [copy] and only there. */
        data class Moved(val copy: MediaRef) : Outcome

        /**
         * Nothing was moved and **the source is untouched**.
         *
         * That guarantee is the whole contract: every path that reaches this result either
         * never deleted the source or never got as far as trying.
         */
        data class Failed(val reason: String) : Outcome
    }

    suspend fun move(source: MediaRef, destination: MediaRef): Outcome {
        val copy = try {
            ops.copy(source, destination)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            return Outcome.Failed("copy to the offload volume failed: ${e.message}")
        }

        val good = try {
            ops.verify(source, copy)
        } catch (e: CancellationException) {
            // A cancelled verification is an unverified one. Clean up under
            // NonCancellable so the card is not left holding a half-written file, then
            // let the cancellation continue — the source has not been touched.
            removeQuietly(copy)
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            removeQuietly(copy)
            return Outcome.Failed("could not verify the offloaded copy: ${e.message}")
        }

        if (!good) {
            removeQuietly(copy)
            return Outcome.Failed("the offloaded copy does not match the original")
        }

        // Only here, and only here, is the original allowed to go.
        return try {
            ops.removeSource(source)
            Outcome.Moved(copy)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // The copy is good, so nothing is lost — there are simply two of it now. Left
            // in place deliberately: deleting the verified copy to "tidy up" would throw
            // away the one thing that is known to be intact.
            Outcome.Failed("the copy is safe on the offload volume but the original could not be removed: ${e.message}")
        }
    }

    private suspend fun removeQuietly(copy: MediaRef) = withContext(NonCancellable) {
        try {
            ops.removeCopy(copy)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("SwallowedException", "TooGenericExceptionCaught") e: Exception) {
            // Best effort: a stray copy is untidy, never dangerous.
        }
    }
}
