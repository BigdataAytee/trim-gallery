package app.trimgallery.core.domain.compress

import app.trimgallery.core.domain.billing.Tier
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.model.MediaRef
import app.trimgallery.core.model.MediaStatus
import app.trimgallery.core.model.SkipReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The Optimise sheet's states, tested as the ways it could mislead someone.
 *
 * Each test below is a sentence the sheet must never be able to say: "Undo" when there is
 * nothing parked, a second encode because the first tap was slow to register, a size
 * summary for a run that did not change the file. None of those would throw; all of them
 * would be a screen telling a user something untrue about their own photographs.
 */
class OptimiseFlowTest {

    private fun item(size: Long = 380L * 1024 * 1024, skipReason: SkipReason? = null, optimisedAt: Long? = null) =
        MediaItem(
            id = "m1",
            platformRef = MediaRef("content://x"),
            name = "clip.mp4",
            kind = MediaKind.VIDEO,
            codec = "h264",
            width = 3840,
            height = 2160,
            fps = 30.0,
            bitrate = 40_000_000,
            size = size,
            duration = 120_000,
            takenAt = null,
            location = null,
            cameraModel = null,
            phash = null,
            sha256 = null,
            status = MediaStatus.CANDIDATE,
            skipReason = skipReason,
            mtime = 0,
            optimisedAt = optimisedAt,
        )

    private fun offered(item: MediaItem = item()): OptimiseFlow.State =
        OptimiseFlow.open(item, CompressNow.decide(item, Tier.FREE, usedToday = 0))

    @Test
    fun `a long press on an ordinary video offers the estimate`() {
        val state = offered()

        val open = assertIs<OptimiseFlow.State.Offered>(state)
        assertEquals(380L * 1024 * 1024, open.decision.estimate.originalSize)
    }

    @Test
    fun `a refusal opens a sheet that says why, rather than a disabled button`() {
        // A greyed-out menu item teaches the user nothing and reads as a bug. Every refusal
        // `CompressNow` can return has to be sayable.
        val state = OptimiseFlow.open(
            item(optimisedAt = 1),
            CompressNow.decide(item(optimisedAt = 1), Tier.FREE, usedToday = 0),
        )

        val refused = assertIs<OptimiseFlow.State.Refused>(state)
        assertEquals(CompressNow.Refusal.ALREADY_OPTIMISED, refused.decision.refusal)
        assertTrue(refused.decision.explanation.isNotEmpty())
        assertFalse(refused.decision.offerPro, "Pro cannot un-optimise a file")
    }

    @Test
    fun `progress starts as unknown rather than as zero`() {
        // A bar sitting at 0% and a bar that has not started look identical, and only one of
        // them is true.
        val working = assertIs<OptimiseFlow.State.Working>(OptimiseFlow.start(offered()))

        assertEquals(null, working.progress)
    }

    @Test
    fun `a second Start does not begin a second encode`() {
        // A double-tap on a slow phone, or a stale click landing after the run began.
        val working = OptimiseFlow.start(offered())
        val progressed = OptimiseFlow.progress(working, 0.4f)

        val again = OptimiseFlow.start(progressed)

        assertEquals(progressed, again)
    }

    @Test
    fun `a result arriving when nothing was running is ignored`() {
        val closed = OptimiseFlow.State.Closed

        val after = OptimiseFlow.finish(closed, OptimiseFlow.Finished.Optimised(100, 50, "bin://1"))

        assertEquals(OptimiseFlow.State.Closed, after)
    }

    @Test
    fun `Undo is offered only when an original was actually parked`() {
        // The rule this whole type exists for. `Replacer` parks the original and writes an
        // UndoEntry naming where it went; without that reference a restore has nowhere to
        // restore from, and the button would be one that cannot work.
        val parked = OptimiseFlow.finish(
            OptimiseFlow.start(offered()),
            OptimiseFlow.Finished.Optimised(wasBytes = 100, nowBytes = 50, undoRef = "bin://1"),
        )
        val unparked = OptimiseFlow.finish(
            OptimiseFlow.start(offered()),
            OptimiseFlow.Finished.Optimised(wasBytes = 100, nowBytes = 50, undoRef = null),
        )

        assertTrue(assertIs<OptimiseFlow.State.Done>(parked).mayUndo)
        assertFalse(assertIs<OptimiseFlow.State.Done>(unparked).mayUndo)
    }

    @Test
    fun `a skip and a failure never offer Undo, because nothing was replaced`() {
        val skipped = OptimiseFlow.finish(
            OptimiseFlow.start(offered()),
            OptimiseFlow.Finished.Skipped(SkipReason.COULD_NOT_REACH_QUALITY, "could not hold the quality"),
        )
        val failed = OptimiseFlow.finish(
            OptimiseFlow.start(offered()),
            OptimiseFlow.Finished.Failed("the encoder was reclaimed"),
        )

        val skippedDone = assertIs<OptimiseFlow.State.Done>(skipped)
        val failedDone = assertIs<OptimiseFlow.State.Done>(failed)
        assertFalse(skippedDone.mayUndo)
        assertFalse(failedDone.mayUndo)
        assertFalse(skippedDone.changedTheFile)
        assertFalse(failedDone.changedTheFile)
    }

    @Test
    fun `the summary is the measured sizes, in the words USER_JOURNEY asks for`() {
        val done = assertIs<OptimiseFlow.State.Done>(
            OptimiseFlow.finish(
                OptimiseFlow.start(offered()),
                OptimiseFlow.Finished.Optimised(
                    wasBytes = 380L * 1024 * 1024,
                    nowBytes = 165L * 1024 * 1024,
                    undoRef = "bin://1",
                ),
            ),
        )

        assertEquals("Now 165 MB (was 380 MB)", done.summary)
        assertEquals(215L * 1024 * 1024, (done.finished as OptimiseFlow.Finished.Optimised).savedBytes)
        assertTrue(done.changedTheFile)
    }

    @Test
    fun `a skip shows its own reason rather than a size that did not change`() {
        val done = assertIs<OptimiseFlow.State.Done>(
            OptimiseFlow.finish(
                OptimiseFlow.start(offered()),
                OptimiseFlow.Finished.Skipped(SkipReason.WOULD_NOT_SHRINK, "it would not get smaller"),
            ),
        )

        assertEquals("it would not get smaller", done.summary)
    }

    @Test
    fun `Undo is refused on a sheet that had nothing to undo`() {
        // Otherwise a mis-routed tap could report "restored" for a run that never replaced.
        val failed = OptimiseFlow.finish(
            OptimiseFlow.start(offered()),
            OptimiseFlow.Finished.Failed("the encoder was reclaimed"),
        )

        assertEquals(failed, OptimiseFlow.undone(failed))
    }

    @Test
    fun `Undo on a real replacement reports the original is back`() {
        val done = OptimiseFlow.finish(
            OptimiseFlow.start(offered()),
            OptimiseFlow.Finished.Optimised(100, 50, "bin://1"),
        )

        assertIs<OptimiseFlow.State.Undone>(OptimiseFlow.undone(done))
    }
}
