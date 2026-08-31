package app.trimgallery.core.domain.skip

import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.model.MediaRef
import app.trimgallery.core.model.MediaStatus
import app.trimgallery.core.model.SkipReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * This screen is the app explaining itself. BUILD.md § 9 requires it; DESIGN_SYSTEM.md
 * § Copy tone constrains how it reads.
 */
class SkipListTest {

    private var counter = 0

    private fun item(status: MediaStatus, reason: SkipReason? = null, kind: MediaKind = MediaKind.VIDEO) = MediaItem(
        id = "item-${counter++}",
        platformRef = MediaRef("ref-$counter"),
        name = "clip.mp4",
        kind = kind,
        codec = "avc1",
        width = 1920,
        height = 1080,
        fps = 30.0,
        bitrate = 20_000_000,
        size = 100_000_000,
        duration = 60_000,
        takenAt = null,
        location = null,
        cameraModel = null,
        phash = null,
        sha256 = null,
        status = status,
        skipReason = reason,
        mtime = 0,
    )

    @Test
    fun `every reason has an explanation, and none of them is an enum name`() {
        (SkipReason.entries.map { it as SkipReason? } + listOf(null)).forEach { reason ->
            val text = SkipList.explain(reason)
            assertTrue(text.isNotBlank(), "no explanation for $reason")
            assertTrue(text.first().isUpperCase(), "not a sentence: $text")
            assertTrue(text.last() in ".!", "not a sentence: $text")
            assertFalse(text.contains('_'), "reads like an enum: $text")
            assertTrue(SkipList.heading(reason).isNotBlank(), "no heading for $reason")
        }
    }

    @Test
    fun `the copy never says compress or shrink`() {
        // DESIGN_SYSTEM.md § Copy tone: "optimise", "freed", "smaller".
        val banned = listOf("compress", "shrink")
        (SkipReason.entries.map { it as SkipReason? } + listOf(null)).forEach { reason ->
            val text = (SkipList.explain(reason) + " " + SkipList.heading(reason)).lowercase()
            banned.forEach { word -> assertFalse(text.contains(word), "$reason says '$word': $text") }
        }
        assertFalse(SkipList.NOTHING_TO_DO.lowercase().contains("compress"))
    }

    @Test
    fun `Try again appears only where trying again could work`() {
        // A button that does nothing teaches the user not to believe the screen.
        assertTrue(SkipList.isRetryable(null), "a failure is worth another go")
        assertTrue(SkipList.isRetryable(SkipReason.IN_CLOUD_ONLY), "downloading it changes the answer")

        listOf(
            SkipReason.HDR, SkipReason.MOTION_PHOTO, SkipReason.ULTRA_HDR, SkipReason.LIVE_PHOTO,
            SkipReason.RAW, SkipReason.TOO_SMALL, SkipReason.ALREADY_EFFICIENT,
            SkipReason.WOULD_NOT_SHRINK, SkipReason.UNSUPPORTED_CODEC, SkipReason.NO_HARDWARE_ENCODER,
        ).forEach { assertFalse(SkipList.isRetryable(it), "$it would give the same answer forever") }
    }

    @Test
    fun `a quality failure is permanent, because the search is deterministic`() {
        // BUILD.md § 5: skipped permanently after two step-ups. A second run would spend
        // three more encodes reaching the same conclusion.
        assertFalse(SkipList.isRetryable(SkipReason.COULD_NOT_REACH_QUALITY))
    }

    @Test
    fun `only skipped and failed items appear`() {
        assertNull(SkipList.row(item(MediaStatus.DONE)))
        assertNull(SkipList.row(item(MediaStatus.CANDIDATE)))
        assertNull(SkipList.row(item(MediaStatus.NEW)))
        assertTrue(SkipList.row(item(MediaStatus.SKIPPED, SkipReason.HDR)) != null)
        assertTrue(SkipList.row(item(MediaStatus.FAILED)) != null)
    }

    @Test
    fun `a failed file is listed under Skipped with a reason and Try again`() {
        // USER_JOURNEY.md § 14, verbatim. The user does not care which internal state it
        // is in, only that it was not done.
        val row = SkipList.row(item(MediaStatus.FAILED))!!
        assertNull(row.reason)
        assertTrue(row.retryable)
        assertTrue(row.explanation.contains("try this one again"), row.explanation)
    }

    @Test
    fun `a failed item never borrows a stale skip reason`() {
        // The pipeline records a SkipReason only for a deliberate decision. A file that
        // failed after previously being skipped must not be explained by the old verdict.
        val stale = item(MediaStatus.FAILED, SkipReason.ALREADY_EFFICIENT)
        assertNull(SkipList.row(stale)!!.reason)
    }

    @Test
    fun `groups put the actionable rows first, then the largest`() {
        // A screen that buries its one actionable item under four hundred already-efficient
        // photos is a screen nobody scrolls.
        val items = buildList {
            repeat(400) { add(item(MediaStatus.SKIPPED, SkipReason.ALREADY_EFFICIENT)) }
            repeat(3) { add(item(MediaStatus.SKIPPED, SkipReason.HDR)) }
            add(item(MediaStatus.SKIPPED, SkipReason.IN_CLOUD_ONLY))
            add(item(MediaStatus.FAILED))
        }

        val groups = SkipList.groups(items)
        assertEquals(4, groups.size)
        assertTrue(groups[0].retryable && groups[1].retryable, "actionable groups lead")
        assertEquals(SkipReason.ALREADY_EFFICIENT, groups[2].reason, "then the biggest")
        assertEquals(400, groups[2].count)
        assertEquals(SkipReason.HDR, groups[3].reason)
    }

    @Test
    fun `grouping is stable when two groups are the same size`() {
        val items = listOf(
            item(MediaStatus.SKIPPED, SkipReason.RAW),
            item(MediaStatus.SKIPPED, SkipReason.HDR),
        )
        assertEquals(SkipList.groups(items).map { it.heading }, listOf("HDR video", "RAW"))
    }

    @Test
    fun `retryable returns exactly the items a Try again would re-queue`() {
        val items = listOf(
            item(MediaStatus.SKIPPED, SkipReason.HDR),
            item(MediaStatus.SKIPPED, SkipReason.IN_CLOUD_ONLY),
            item(MediaStatus.FAILED),
            item(MediaStatus.DONE),
        )
        assertEquals(2, SkipList.retryable(items).size)
    }

    @Test
    fun `an empty library produces no groups at all, not an empty group`() {
        assertTrue(SkipList.groups(emptyList()).isEmpty())
    }
}
