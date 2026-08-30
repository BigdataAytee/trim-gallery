package app.trimgallery.core.domain.trash

import app.trimgallery.core.model.FolderMode
import app.trimgallery.core.model.JobState
import app.trimgallery.core.model.MediaRef
import app.trimgallery.core.model.UndoEntry
import app.trimgallery.core.model.UndoLocation
import app.trimgallery.core.model.UndoState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class TrashPolicyTest {

    private val now = Instant.parse("2026-08-30T12:00:00Z")

    /** Comfortably before and after [now], for the sweep tests. */
    private val past = now - 1.days
    private val future = now + 1.days

    private fun entry(
        expiresAt: Instant?,
        state: UndoState = UndoState.ACTIVE,
        location: UndoLocation = UndoLocation.BIN,
        mediaId: String = "1",
    ) = UndoEntry(
        id = "u-$mediaId",
        mediaId = mediaId,
        location = location,
        ref = MediaRef("ref"),
        expiresAt = expiresAt,
        state = state,
    )

    @Test
    fun `only Free space mode expires`() {
        assertEquals(now + 30.days, TrashPolicy.expiresAt(FolderMode.FREE, now))
        assertNull(TrashPolicy.expiresAt(FolderMode.KEEP, now))
        assertNull(TrashPolicy.expiresAt(FolderMode.OFFLOAD, now))
    }

    @Test
    fun `retention is configurable`() {
        assertEquals(now + 7.days, TrashPolicy.expiresAt(FolderMode.FREE, now, retentionDays = 7))
    }

    @Test
    fun `days left rounds up so nothing recoverable ever shows zero`() {
        // Eleven hours left is "1 day", not "0 days": the user can still save it.
        assertEquals(1, TrashPolicy.daysLeft(entry(now + 11.hours), now))
        assertEquals(1, TrashPolicy.daysLeft(entry(now + 24.hours), now))
        assertEquals(2, TrashPolicy.daysLeft(entry(now + 25.hours), now))
        assertEquals(30, TrashPolicy.daysLeft(entry(now + 30.days), now))
    }

    @Test
    fun `an entry past its expiry has no days left`() {
        assertEquals(0, TrashPolicy.daysLeft(entry(now - 1.hours), now))
    }

    @Test
    fun `an entry that never expires has no countdown`() {
        assertNull(TrashPolicy.daysLeft(entry(null), now))
    }

    @Test
    fun `the sweep only takes active, expired entries`() {
        assertTrue(TrashPolicy.isExpired(entry(now - 1.hours), now))
        assertFalse(TrashPolicy.isExpired(entry(now + 1.hours), now))
        assertFalse(TrashPolicy.isExpired(entry(null), now))
        // A restored entry has already been rescued and must not be swept.
        assertFalse(TrashPolicy.isExpired(entry(now - 1.hours, state = UndoState.RESTORED), now))
    }

    @Test
    fun `expiry is inclusive at the boundary`() {
        assertTrue(TrashPolicy.isExpired(entry(now), now))
    }

    @Test
    fun `expired returns exactly the sweepable entries`() {
        val entries = listOf(
            entry(now - 1.days),
            entry(now + 1.days),
            entry(null),
            entry(now - 1.days, state = UndoState.EXPIRED),
        )
        assertEquals(1, TrashPolicy.expired(entries, now) { JobState.SUCCEEDED }.size)
    }

    @Test
    fun `the subtitle tells the truth about where the original actually is`() {
        // "Kept" would imply it is on the phone; an offloaded original is on a card that
        // may not be inserted.
        assertEquals("On external storage", TrashPolicy.subtitle(entry(null, location = UndoLocation.OFFLOAD), now))
        assertEquals("Kept until you empty the bin", TrashPolicy.subtitle(entry(null), now))
        assertEquals("Restored", TrashPolicy.subtitle(entry(now + 1.days, state = UndoState.RESTORED), now))
        assertEquals("Deleted", TrashPolicy.subtitle(entry(now - 1.days, state = UndoState.EXPIRED), now))
    }

    @Test
    fun `the countdown is singular on the last day`() {
        assertEquals("Deletes today", TrashPolicy.subtitle(entry(now), now))
        assertEquals("1 day left", TrashPolicy.subtitle(entry(now + 20.hours), now))
        assertEquals("5 days left", TrashPolicy.subtitle(entry(now + (4.days + 3.hours)), now))
    }

    @Test
    fun `entries about to expire are flagged`() {
        assertTrue(TrashPolicy.isExpiringSoon(entry(now + 2.days), now))
        assertFalse(TrashPolicy.isExpiringSoon(entry(now + 10.days), now))
        assertFalse(TrashPolicy.isExpiringSoon(entry(null), now))
    }

    @Test
    fun `restorable lists the most urgent first and excludes expired ones`() {
        val soon = entry(now + 1.days)
        val later = entry(now + 20.days)
        val never = entry(null)
        val gone = entry(now - 1.days)
        val result = TrashPolicy.restorable(listOf(later, never, soon, gone), now)
        assertEquals(listOf(soon, later, never), result)
    }

    // ----------------------------------------------- the job-state condition

    /**
     * The condition this sweep exists to enforce.
     *
     * An `UndoEntry` is written last in the § 7 contract, so its existence normally implies
     * the swap completed — and "normally" is not good enough when being wrong deletes the
     * only copy of a photograph. A process killed between the journal write and the job's
     * status update, or a rollback that could not reach the row, leaves an entry whose
     * original is all the user has left.
     */
    @Test
    fun `an entry whose job did not succeed is never swept`() {
        val due = entry(expiresAt = past)
        for (state in JobState.entries.filterNot { it == JobState.SUCCEEDED }) {
            assertTrue(
                TrashPolicy.expired(listOf(due), now) { state }.isEmpty(),
                "swept an original whose job was $state",
            )
        }
        assertEquals(listOf(due), TrashPolicy.expired(listOf(due), now) { JobState.SUCCEEDED })
    }

    /** An entry whose job cannot be found is an entry nothing can vouch for. */
    @Test
    fun `an entry with no job row is never swept`() {
        val due = entry(expiresAt = past)
        assertTrue(TrashPolicy.expired(listOf(due), now) { null }.isEmpty())
    }

    /**
     * Held back rather than silently skipped: an original kept past its window because a
     * night went wrong is something an operator should be able to see, and a user should not
     * be told the space was freed.
     */
    @Test
    fun `entries held back are reported, not dropped on the floor`() {
        val due = entry(expiresAt = past)
        assertEquals(listOf(due), TrashPolicy.heldBack(listOf(due), now) { JobState.FAILED })
        assertTrue(TrashPolicy.heldBack(listOf(due), now) { JobState.SUCCEEDED }.isEmpty())
    }

    @Test
    fun `swept and held-back together account for every due entry, and overlap in none`() {
        val entries = listOf(
            entry(mediaId = "ok", expiresAt = past),
            entry(mediaId = "failed", expiresAt = past),
            entry(mediaId = "future", expiresAt = future),
        )
        val states = mapOf("ok" to JobState.SUCCEEDED, "failed" to JobState.FAILED)
        val swept = TrashPolicy.expired(entries, now) { states[it.mediaId] }
        val held = TrashPolicy.heldBack(entries, now) { states[it.mediaId] }

        assertEquals(listOf("ok"), swept.map { it.mediaId })
        assertEquals(listOf("failed"), held.map { it.mediaId })
        assertTrue((swept.map { it.id }.toSet() intersect held.map { it.id }.toSet()).isEmpty())
    }
}
