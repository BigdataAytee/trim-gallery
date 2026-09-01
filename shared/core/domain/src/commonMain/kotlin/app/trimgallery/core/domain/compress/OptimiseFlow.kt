package app.trimgallery.core.domain.compress

import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.SkipReason

/**
 * The states a single "Optimise" goes through, from the long-press to the undo.
 *
 * `CompressNow` decides *whether* a tap may proceed and what the sheet should say before it
 * starts. This is what happens after: the sheet has to be somewhere at every moment, and
 * every one of those moments is a thing the user is being told.
 *
 * Here rather than in the composable because it is the part with rules in it, and because a
 * screen whose states live in `remember { mutableStateOf(...) }` can only be tested by
 * driving a device. These transitions are unit tested on the JVM; the composable that draws
 * them adds nothing of its own.
 *
 * ## The rule this type exists to enforce
 *
 * **Undo is offered only when there is something to undo.** `VideoOptimiseStep` replaces
 * through `Replacer`, which parks the original and writes an `UndoEntry` naming where it
 * went; that reference is what a restore needs. A sheet that showed "Undo" without one
 * would be a button that cannot work, on the screen where the user is deciding whether to
 * trust the app with the rest of their library. [Finished.undoRef] is therefore not
 * optional decoration — its absence changes what the sheet may offer.
 */
object OptimiseFlow {

    /** What the step reported, in the flow's own words. */
    sealed interface Finished {
        /** Where the original is parked now, or null when nothing was parked. */
        val undoRef: String?

        /** Replaced. Both sizes are measured, not estimated. */
        data class Optimised(val wasBytes: Long, val nowBytes: Long, override val undoRef: String?) : Finished {
            val savedBytes: Long get() = (wasBytes - nowBytes).coerceAtLeast(0)
        }

        /** Nothing was done, for a reason the user can read. The original is untouched. */
        data class Skipped(val reason: SkipReason, val detail: String) : Finished {
            override val undoRef: String? get() = null
        }

        /** Something went wrong. The original is untouched — that is the invariant. */
        data class Failed(val detail: String) : Finished {
            override val undoRef: String? get() = null
        }
    }

    sealed interface State {
        /** The item this sheet is about, or null once it is over. */
        val item: MediaItem?

        /** The sheet before the user commits: the estimate, and Start. */
        data class Offered(override val item: MediaItem, val decision: CompressNow.Decision.Allowed) : State

        /** The tap is not allowed. Shows why, and a Pro offer only where Pro would help. */
        data class Refused(override val item: MediaItem, val decision: CompressNow.Decision.Refused) : State

        /**
         * Running.
         *
         * [progress] is null until the encoder reports something rather than 0f, because
         * a bar sitting at zero and a bar that has not started look identical to the user
         * and only one of them is honest.
         */
        data class Working(override val item: MediaItem, val progress: Float? = null) : State

        /** Done, however it went. [mayUndo] is the whole reason this type is tested. */
        data class Done(override val item: MediaItem, val finished: Finished) : State {
            val mayUndo: Boolean get() = finished.undoRef != null

            /** "Now 165 MB (was 380 MB)" — USER_JOURNEY.md § 6, on measured numbers. */
            val summary: String
                get() = when (finished) {
                    is Finished.Optimised -> CompressNow.describeResult(finished.wasBytes, finished.nowBytes)
                    is Finished.Skipped -> finished.detail
                    is Finished.Failed -> finished.detail
                }

            /** Whether the file actually changed. Drives the tone, not just the text. */
            val changedTheFile: Boolean get() = finished is Finished.Optimised
        }

        /** The user pressed Undo and the original is back. */
        data class Undone(override val item: MediaItem) : State

        /** Nothing open. */
        data object Closed : State {
            override val item: MediaItem? get() = null
        }
    }

    /**
     * What a long-press should open.
     *
     * Every refusal `CompressNow` can return lands on a sheet that says why, rather than on
     * a menu item that is greyed out with no explanation. A user who is told "Trim already
     * optimised this one" has learned something about the app; a disabled button teaches
     * them nothing and reads as a bug.
     */
    fun open(item: MediaItem, decision: CompressNow.Decision): State = when (decision) {
        is CompressNow.Decision.Allowed -> State.Offered(item, decision)
        is CompressNow.Decision.Refused -> State.Refused(item, decision)
    }

    /**
     * Start, from an offer.
     *
     * Only from [State.Offered]: a Start arriving in any other state is a second tap on a
     * sheet that has already moved on — a double-tap on a slow phone, or a stale click
     * landing after the run finished — and starting a second encode of the same file
     * because the first tap was slow to register is exactly the bug worth refusing here.
     */
    fun start(state: State): State = when (state) {
        is State.Offered -> State.Working(state.item)
        else -> state
    }

    /** Progress, ignored unless something is actually running. */
    fun progress(state: State, fraction: Float): State = when (state) {
        is State.Working -> state.copy(progress = fraction.coerceIn(0f, 1f))
        else -> state
    }

    /** The result, ignored unless something was running to produce it. */
    fun finish(state: State, finished: Finished): State = when (state) {
        is State.Working -> State.Done(state.item, finished)
        else -> state
    }

    /**
     * The original is back.
     *
     * Refused unless the sheet was actually offering an undo, so that a restore cannot be
     * reported for a run that skipped, failed, or had nothing parked.
     */
    fun undone(state: State): State = when {
        state is State.Done && state.mayUndo -> State.Undone(state.item)
        else -> state
    }
}
