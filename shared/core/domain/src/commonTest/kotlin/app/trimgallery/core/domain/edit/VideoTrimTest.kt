package app.trimgallery.core.domain.edit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoTrimTest {

    private val clipMs = 30_000L

    /** One keyframe every two seconds, which is what a phone camera writes. */
    private val keyframes = (0..15).map { it * 2_000L }

    @Test
    fun `a full trim is not a trim`() {
        assertTrue(VideoTrim.full(clipMs).isFull(clipMs))
        assertFalse(VideoTrim(1_000, clipMs).isFull(clipMs))
        assertFalse(VideoTrim(0, clipMs - 1).isFull(clipMs))
    }

    @Test
    fun `handles past the ends are pulled back in`() {
        val trim = VideoTrim.of(startMs = -5_000, endMs = 99_000, sourceDurationMs = clipMs)
        assertEquals(VideoTrim(0, clipMs), trim)
    }

    @Test
    fun `the handles cannot cross`() {
        val trim = VideoTrim.of(startMs = 10_000, endMs = 9_000, sourceDurationMs = clipMs)
        assertTrue(trim.durationMs >= VideoTrim.MIN_DURATION_MS)
        assertTrue(trim.startMs < trim.endMs)
    }

    @Test
    fun `a trim is never shorter than the handles can control`() {
        val trim = VideoTrim.of(startMs = 10_000, endMs = 10_010, sourceDurationMs = clipMs)
        assertEquals(VideoTrim.MIN_DURATION_MS, trim.durationMs)
    }

    // -------------------------------------------------------------- planning

    /**
     * The decision the whole class is for: cutting the container is instant, costs no
     * battery and is genuinely lossless, where a re-encode puts a second generation of loss
     * on an already-optimised clip.
     */
    @Test
    fun `a trim starting on a keyframe is a container cut`() {
        val trim = VideoTrim(4_000, 12_000)
        val plan = TrimPlanner.plan(trim, keyframes, clipMs)
        assertEquals(TrimPlanner.Plan.StreamCopy(trim), plan)
    }

    /** Only the start matters: truncating the last group loses only frames the user cut. */
    @Test
    fun `the end need not land on a keyframe`() {
        val trim = VideoTrim(4_000, 12_345)
        assertIs<TrimPlanner.Plan.StreamCopy>(TrimPlanner.plan(trim, keyframes, clipMs))
    }

    @Test
    fun `a tiny offset is snapped silently`() {
        val trim = VideoTrim(4_050, 12_000)
        val plan = assertIs<TrimPlanner.Plan.StreamCopy>(TrimPlanner.plan(trim, keyframes, clipMs))
        assertEquals(4_000, plan.trim.startMs)
        assertEquals(12_000, plan.trim.endMs, "the end should not have moved")
    }

    /**
     * Backwards only. Snapping forward would drop footage the user chose to keep; snapping
     * back keeps a fraction of a second they chose to lose, which nobody notices.
     */
    @Test
    fun `snapping only ever moves the start earlier`() {
        for (start in listOf(4_010L, 4_100L, 5_000L, 5_900L)) {
            val plan = TrimPlanner.plan(VideoTrim(start, 12_000), keyframes, clipMs)
            val resulting = when (plan) {
                is TrimPlanner.Plan.StreamCopy -> plan.trim.startMs
                is TrimPlanner.Plan.Reencode -> plan.losslessAlternative?.startMs ?: start
            }
            assertTrue(resulting <= start, "$start snapped forward to $resulting")
        }
    }

    @Test
    fun `a shift the user would notice is offered rather than taken`() {
        val trim = VideoTrim(5_000, 12_000)
        val plan = assertIs<TrimPlanner.Plan.Reencode>(TrimPlanner.plan(trim, keyframes, clipMs))
        assertEquals(trim, plan.trim, "the user's own cut is what gets encoded")
        assertEquals(4_000, plan.losslessAlternative?.startMs)
        assertTrue(TrimPlanner.explainAlternative(trim, plan.losslessAlternative!!).contains("1000 ms"))
    }

    @Test
    fun `a shift too large to be worth it is not offered at all`() {
        // Keyframes eight seconds apart; a cut at 7.9 s would have to give back most of it.
        val sparse = listOf(0L, 8_000L, 16_000L)
        val plan = assertIs<TrimPlanner.Plan.Reencode>(TrimPlanner.plan(VideoTrim(7_900, 12_000), sparse, clipMs))
        assertNull(plan.losslessAlternative)
    }

    /** Guessing that frame zero is a keyframe would be right for most files and fatal for the rest. */
    @Test
    fun `a container that lists no keyframes forces a re-encode`() {
        val plan = TrimPlanner.plan(VideoTrim(4_000, 12_000), emptyList(), clipMs)
        assertIs<TrimPlanner.Plan.Reencode>(plan)
        assertNull((plan as TrimPlanner.Plan.Reencode).losslessAlternative)
    }

    @Test
    fun `a trim before the first keyframe has nothing to snap back to`() {
        val plan = TrimPlanner.plan(VideoTrim(500, 12_000), listOf(2_000L, 4_000L), clipMs)
        assertIs<TrimPlanner.Plan.Reencode>(plan)
        assertNull((plan as TrimPlanner.Plan.Reencode).losslessAlternative)
    }

    @Test
    fun `keeping the whole clip needs no encode even without keyframes`() {
        assertIs<TrimPlanner.Plan.StreamCopy>(TrimPlanner.plan(VideoTrim.full(clipMs), emptyList(), clipMs))
    }

    /** Anything else in the recipe needs the pixels, so the keyframes stop mattering. */
    @Test
    fun `a trim with another edit is always a re-encode`() {
        val trim = VideoTrim(4_000, 12_000)
        val plan = TrimPlanner.plan(trim, keyframes, clipMs, otherEdits = true)
        assertEquals(TrimPlanner.Plan.Reencode(trim), plan)
    }
}
