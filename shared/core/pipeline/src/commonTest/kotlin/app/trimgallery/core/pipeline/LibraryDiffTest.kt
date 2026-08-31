package app.trimgallery.core.pipeline

import app.trimgallery.core.model.MediaFlags
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.model.MediaRef
import app.trimgallery.core.model.MediaStatus
import app.trimgallery.core.model.SkipReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ARCHITECTURE.md § 7: `storage.scan(grants) → DB diff (new/changed/removed)`, and § 9's
 * `DONE/SKIPPED/FAILED → NEW when the file changes`.
 *
 * Both halves of that rule have a way to go wrong, and both are here: applied too weakly a
 * re-edited video keeps a verdict that describes a file that is gone; applied too eagerly
 * the app re-optimises its own output and the picture degrades one generation a night.
 */
class LibraryDiffTest {

    private fun item(
        id: String,
        ref: String = "content://tree/$id",
        size: Long = 100_000_000,
        mtime: Long = 1_700_000_000_000,
        grant: String? = "grant-a",
        status: MediaStatus = MediaStatus.DONE,
    ) = MediaItem(
        id = id,
        platformRef = MediaRef(ref),
        folderGrantId = grant,
        name = "$id.mp4",
        kind = MediaKind.VIDEO,
        codec = "avc1",
        width = 1920,
        height = 1080,
        fps = 30.0,
        bitrate = 20_000_000,
        size = size,
        duration = 60_000,
        takenAt = null,
        location = null,
        cameraModel = null,
        phash = 42,
        sha256 = "abc",
        status = status,
        mtime = mtime,
    )

    private val grants = setOf("grant-a")

    @Test
    fun `a file the database has never seen is added`() {
        val result = LibraryDiff.diff(stored = emptyList(), scanned = listOf(item("a")), scannedGrants = grants)
        assertEquals(listOf("a"), result.added.map { it.id })
        assertTrue(result.modified.isEmpty() && result.removed.isEmpty())
    }

    @Test
    fun `an untouched file is left entirely alone`() {
        val stored = item("a")
        val result = LibraryDiff.diff(listOf(stored), listOf(stored), grants)
        assertEquals(1, result.unchanged.size)
        assertTrue(result.isEmpty)
        assertTrue(result.needsTriage.isEmpty())
    }

    @Test
    fun `a changed size or a changed mtime both count`() {
        // Either alone is defeatable: an edit that preserves the length changes only the
        // timestamp, and a filesystem that rounds timestamps hides a quick edit but not a
        // size change.
        val stored = item("a")
        assertEquals(
            1,
            LibraryDiff.diff(listOf(stored), listOf(stored.copy(size = 90_000_000)), grants).modified.size,
        )
        assertEquals(
            1,
            LibraryDiff.diff(listOf(stored), listOf(stored.copy(mtime = 1_700_000_999_999)), grants).modified.size,
        )
    }

    @Test
    fun `our own replace is not seen as a user edit`() {
        // The pipeline writes the new size and mtime back to the row as part of recording
        // the result. Without that write-back the next scan reports the file as changed,
        // resets it to NEW, and optimises it again — measuring VMAF 95 against an
        // already-lossy copy.
        val before = item("a", size = 400_000_000)
        val afterReplace = before.copy(size = 165_000_000, optimisedAt = 1_700_000_500_000)

        val result = LibraryDiff.diff(
            stored = listOf(afterReplace),
            scanned = listOf(item("a", size = 165_000_000)),
            scannedGrants = grants,
        )
        assertTrue(result.modified.isEmpty(), "the app's own output must not look edited")
        assertEquals(1, result.unchanged.size)
    }

    @Test
    fun `a file gone from a scanned folder is removed`() {
        val result = LibraryDiff.diff(listOf(item("a"), item("b")), listOf(item("a")), grants)
        assertEquals(listOf("b"), result.removed.map { it.id })
    }

    @Test
    fun `a folder that was not scanned never loses its rows`() {
        // The commonest real case: two granted folders and the SD card is out. A diff that
        // did not know which grants were covered would report every photo on the card as
        // removed and delete its index, its labels and its faces.
        val onCard = item("b", grant = "grant-sd")
        val result = LibraryDiff.diff(
            stored = listOf(item("a"), onCard),
            scanned = listOf(item("a")),
            scannedGrants = setOf("grant-a"),
        )
        assertTrue(result.removed.isEmpty(), "rows outside the scan must be left alone")
    }

    @Test
    fun `a row with no grant is never removed`() {
        // It cannot be proved absent, because nothing knows where it was meant to be.
        val orphan = item("b", grant = null)
        val result = LibraryDiff.diff(listOf(orphan), emptyList(), grants)
        assertTrue(result.removed.isEmpty())
    }

    @Test
    fun `everything added or modified needs triage, and nothing else does`() {
        val result = LibraryDiff.diff(
            stored = listOf(item("a"), item("b")),
            scanned = listOf(item("a"), item("b", size = 1), item("c")),
            scannedGrants = grants,
        )
        assertEquals(setOf("b", "c"), result.needsTriage.map { it.id }.toSet())
    }

    // -------------------------------------------------------------------- merge

    @Test
    fun `a modified file keeps its identity and loses its verdict`() {
        val stored = item("a", status = MediaStatus.SKIPPED).copy(
            skipReason = SkipReason.ALREADY_EFFICIENT,
            estSaving = 0,
            optimisedAt = 1_700_000_500_000,
        )
        val scanned = item("a", size = 250_000_000, mtime = 1_800_000_000_000).copy(id = "scan-temp")

        val merged = LibraryDiff.merge(stored, scanned, nowMs = 1_800_000_000_001)

        // Identity and index membership survive: the file is still in the user's albums.
        assertEquals("a", merged.id)
        assertEquals("grant-a", merged.folderGrantId)
        // The bytes are the scan's.
        assertEquals(250_000_000, merged.size)
        assertEquals(1_800_000_000_000, merged.mtime)
        // The verdict described a file that no longer exists.
        assertEquals(MediaStatus.NEW, merged.status)
        assertNull(merged.skipReason)
        assertNull(merged.estSaving)
        // Whatever we did to the old file, the user's file now is not ours.
        assertNull(merged.optimisedAt)
    }

    @Test
    fun `a modified file loses its hashes, because they describe pixels that changed`() {
        // Keeping a perceptual hash from the previous version would put the file in the
        // wrong duplicate group and, worse, keep it out of the right one.
        val merged = LibraryDiff.merge(item("a"), item("a", size = 1), nowMs = 0)
        assertNull(merged.phash)
        assertNull(merged.sha256)
    }

    @Test
    fun `the container facts come from the scan, not from the stale row`() {
        // A clip re-exported from an editor is a different file in the same place.
        // Triaging it on the old container's numbers gives a verdict about a file that no
        // longer exists — which is exactly the bug the first version of merge had.
        val stored = item("a").copy(codec = "hevc", bitrate = 8_000_000, width = 1280, height = 720)
        val scanned = item("a", size = 900_000_000).copy(
            codec = "avc1",
            bitrate = 45_000_000,
            width = 3840,
            height = 2160,
            fps = 60.0,
            duration = 120_000,
        )

        val merged = LibraryDiff.merge(stored, scanned, nowMs = 0)

        assertEquals("avc1", merged.codec)
        assertEquals(45_000_000, merged.bitrate)
        assertEquals(3840, merged.width)
        assertEquals(2160, merged.height)
        assertEquals(60.0, merged.fps)
        assertEquals(120_000, merged.duration)
    }

    @Test
    fun `the user's own decisions survive an edit`() {
        // favourite and hidden live in the same bitmask as the container flags (SCHEMA.md),
        // but they are not properties of the bytes. A re-edited photo must not fall out of
        // the locked folder.
        val stored = item("a").copy(flags = MediaFlags(favourite = true, hidden = true))
        val scanned = item("a", size = 1).copy(flags = MediaFlags(hdr = true))

        val merged = LibraryDiff.merge(stored, scanned, nowMs = 0)

        assertTrue(merged.favourite, "a favourite must stay a favourite")
        assertTrue(merged.hidden, "an edit must not leak a photo out of the locked folder")
        // The container flags are the scan's.
        assertTrue(merged.flags.hdr)
    }
}
