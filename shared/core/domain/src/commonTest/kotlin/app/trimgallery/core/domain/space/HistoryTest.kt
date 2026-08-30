package app.trimgallery.core.domain.space

import app.trimgallery.core.model.Job
import app.trimgallery.core.model.JobState
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.model.MediaRef
import app.trimgallery.core.model.UndoEntry
import app.trimgallery.core.model.UndoLocation
import app.trimgallery.core.model.UndoState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Where a user goes when they suspect a photo looks worse. The two things it must never do
 * are claim a file can be restored when it cannot, and fail to say where an original is.
 */
class HistoryTest {

    private val now = Instant.parse("2026-08-30T12:00:00Z")

    private fun item(id: String) = MediaItem(
        id = id,
        platformRef = MediaRef("ref-$id"),
        name = "$id.mp4",
        kind = MediaKind.VIDEO,
        codec = "hevc",
        width = 1920,
        height = 1080,
        fps = 30.0,
        bitrate = 8_000_000,
        size = 165_000_000,
        duration = 60_000,
        takenAt = null,
        location = null,
        cameraModel = null,
        phash = null,
        sha256 = null,
        mtime = 0,
    )

    private fun job(
        id: String,
        mediaId: String,
        state: JobState = JobState.SUCCEEDED,
        before: Long? = 380_000_000,
        after: Long? = 165_000_000,
        finishedAt: String? = "2026-08-30T03:00:00Z",
    ) = Job(
        id = id,
        mediaId = mediaId,
        state = state,
        finishedAt = finishedAt?.let(Instant::parse),
        originalSize = before,
        newSize = after,
    )

    private fun undo(
        mediaId: String,
        location: UndoLocation = UndoLocation.BIN,
        state: UndoState = UndoState.ACTIVE,
        expiresAt: String? = "2026-09-29T00:00:00Z",
    ) = UndoEntry(
        id = "u-$mediaId",
        mediaId = mediaId,
        location = location,
        ref = MediaRef("bin/$mediaId"),
        expiresAt = expiresAt?.let(Instant::parse),
        state = state,
    )

    @Test
    fun `a successful job becomes a row with before and after`() {
        val rows = History.rows(
            listOf(job("j", "a")),
            mapOf("a" to item("a")),
            mapOf("a" to undo("a")),
            now,
        )
        val row = rows.single()
        assertEquals(380_000_000, row.originalSize)
        assertEquals(165_000_000, row.newSize)
        assertEquals(215_000_000, row.saved)
        assertEquals(2.3, row.factor!!, 0.05)
    }

    @Test
    fun `a failed job is not history, it is a skip`() {
        // Listing it here would tell the user something changed when nothing did.
        listOf(JobState.FAILED, JobState.CANCELLED, JobState.QUEUED).forEach { state ->
            val rows = History.rows(
                listOf(job("j", "a", state = state)),
                mapOf("a" to item("a")),
                emptyMap(),
                now,
            )
            assertTrue(rows.isEmpty(), "$state appeared in history")
        }
    }

    @Test
    fun `newest first`() {
        val rows = History.rows(
            listOf(
                job("old", "a", finishedAt = "2026-08-01T03:00:00Z"),
                job("new", "b", finishedAt = "2026-08-29T03:00:00Z"),
            ),
            mapOf("a" to item("a"), "b" to item("b")),
            emptyMap(),
            now,
        )
        assertEquals(listOf("b", "a"), rows.map { it.item.id })
    }

    @Test
    fun `a job whose media row is gone is dropped rather than shown blank`() {
        assertTrue(History.rows(listOf(job("j", "missing")), emptyMap(), emptyMap(), now).isEmpty())
    }

    // ------------------------------------------------------------------ restore

    @Test
    fun `an original in the bin can be restored, and the sheet says until when`() {
        val restorable = assertIs<History.Restorable.FromBin>(History.restorable(undo("a"), now))
        assertTrue(History.restoreExplanation(restorable) { "29 September" }.contains("29 September"))
    }

    @Test
    fun `an original that never expires says so`() {
        val restorable = History.restorable(undo("a", expiresAt = null), now)
        val text = History.restoreExplanation(restorable) { "" }
        assertTrue(text.contains("until you empty the bin"), text)
    }

    @Test
    fun `an offloaded original is named as such, not offered as one tap`() {
        // A file on a card in a drawer is not "ready to restore", and saying so up front is
        // better than a failure afterwards.
        val restorable = History.restorable(undo("a", location = UndoLocation.OFFLOAD, expiresAt = null), now)
        assertEquals(History.Restorable.FromExternal, restorable)
        assertTrue(History.restoreExplanation(restorable) { "" }.contains("external storage"))
    }

    @Test
    fun `an expired original says when it went`() {
        // USER_JOURNEY.md § 5, rather than leaving the user to guess whether the app ever
        // kept one.
        val expired = undo("a", expiresAt = "2026-07-01T00:00:00Z")
        val restorable = assertIs<History.Restorable.Expired>(History.restorable(expired, now))
        assertEquals(Instant.parse("2026-07-01T00:00:00Z"), restorable.removedAt)
        assertTrue(History.restoreExplanation(restorable) { "1 July" }.contains("1 July"))
    }

    @Test
    fun `a row whose original is gone does not offer Restore`() {
        val rows = History.rows(
            listOf(job("j", "a")),
            mapOf("a" to item("a")),
            mapOf("a" to undo("a", expiresAt = "2026-07-01T00:00:00Z")),
            now,
        )
        assertTrue(!rows.single().canRestore)
    }

    @Test
    fun `an already-restored original is reported, not re-offered`() {
        val restorable = History.restorable(undo("a", state = UndoState.RESTORED), now)
        assertEquals(History.Restorable.AlreadyRestored, restorable)
    }

    @Test
    fun `a file with no undo entry was not changed`() {
        assertEquals(History.Restorable.NotApplicable, History.restorable(null, now))
    }

    @Test
    fun `the copy never says compress`() {
        // DESIGN_SYSTEM.md § Copy tone.
        listOf(
            History.Restorable.FromBin(null),
            History.Restorable.FromBin(now),
            History.Restorable.FromExternal,
            History.Restorable.Expired(now),
            History.Restorable.AlreadyRestored,
            History.Restorable.NotApplicable,
        ).forEach { restorable ->
            val text = History.restoreExplanation(restorable) { "a date" }.lowercase()
            assertTrue(!text.contains("compress") && !text.contains("shrink"), text)
            assertTrue(text.isNotBlank() && text.trim().endsWith("."), text)
        }
    }

    @Test
    fun `the morning card totals what the night actually saved`() {
        val rows = History.rows(
            listOf(
                job("a", "a", before = 300_000_000, after = 100_000_000),
                job("b", "b", before = 200_000_000, after = 150_000_000),
            ),
            mapOf("a" to item("a"), "b" to item("b")),
            emptyMap(),
            now,
        )
        assertEquals(250_000_000, History.freedIn(rows))
    }
}
