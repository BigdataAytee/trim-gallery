package app.trimgallery.core.data

import app.trimgallery.core.data.db.TrimDatabase
import app.trimgallery.core.domain.billing.Tier
import app.trimgallery.core.model.FolderGrant
import app.trimgallery.core.model.FolderMode
import app.trimgallery.core.model.MediaFlags
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.model.MediaRef
import app.trimgallery.core.model.MediaStatus
import app.trimgallery.core.model.Settings
import app.trimgallery.core.model.UndoEntry
import app.trimgallery.core.model.UndoLocation
import app.trimgallery.core.pipeline.LibraryDiff
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The library the grid is drawn from, and the scan that keeps it current.
 *
 * This is what makes a start fast, so what it must never do is get the library *wrong* in
 * exchange: a diff that dropped a row would delete a photograph from the user's grid, and
 * one that dropped a removal would leave a file in it that is no longer there.
 */
class LibraryPersistenceTest {

    @Test
    fun theGridComesBackInDateOrderWithoutAnyIndexing() = runTest {
        // The case a first run is entirely made of: nothing has been indexed, so every row
        // is undated and only `mtime` can order them. Ordering on `taken_at` alone would put
        // the whole library in one block.
        val repository = repository()
        repository.applyScan(added(item("a", mtime = 100), item("b", mtime = 300), item("c", mtime = 200)))

        assertEquals(listOf("b", "c", "a"), repository.gallery().map { it.id })
    }

    @Test
    fun arealTakenDateWinsOverTheFallback() = runTest {
        val repository = repository()
        repository.applyScan(
            added(
                item("old-file-new-photo", mtime = 100).copy(takenAt = Instant.fromEpochMilliseconds(900)),
                item("new-file", mtime = 500),
            ),
        )

        assertEquals(listOf("old-file-new-photo", "new-file"), repository.gallery().map { it.id })
    }

    @Test
    fun hiddenItemsNeverReachTheGrid() = runTest {
        val repository = repository()
        repository.applyScan(
            added(item("shown", mtime = 100), item("locked", mtime = 200).copy(flags = MediaFlags(hidden = true))),
        )

        assertEquals(listOf("shown"), repository.gallery().map { it.id })
    }

    @Test
    fun asecondScanThatFoundNothingNewChangesNothing() = runTest {
        // Most nights, for most of the library. `unchanged` is deliberately not written.
        val repository = repository()
        val first = item("a", mtime = 100)
        repository.applyScan(added(first))

        val diff = LibraryDiff.diff(
            stored = repository.stored(listOf(grant())),
            scanned = listOf(first),
            scannedGrants = setOf(GRANT),
        )

        assertTrue(diff.isEmpty, "nothing moved, so there is nothing to write")
        assertEquals(1, repository.gallery().size)
    }

    @Test
    fun afileWhoseBytesMovedKeepsItsIdentity() = runTest {
        val repository = repository()
        repository.applyScan(added(item("a", mtime = 100)))
        val stored = repository.gallery().single()

        // The same file, edited. Note what stays and what changes: the **platformRef is the
        // same**, because it is the same file in the same place, and that is what the diff
        // matches on. Only the id differs, because the scan mints a fresh one for everything
        // it finds — and the merge has to keep the stored one or the photo loses its albums
        // and its history.
        //
        // Getting this wrong is how the test first failed: changing the ref too made the
        // diff report a removal and an addition, which is the correct answer to a different
        // question.
        val rescanned = item("a", mtime = 400).copy(id = "freshly-minted-id", size = 999)
        val diff = LibraryDiff.diff(listOf(stored), listOf(rescanned), setOf(GRANT))
        repository.applyScan(diff)

        val after = repository.gallery().single()
        assertEquals(stored.id, after.id)
        assertEquals(400, after.mtime)
        assertEquals(MediaStatus.NEW, after.status, "the old verdict described a file that is gone")
    }

    @Test
    fun afileThatIsGoneLeavesTheGrid() = runTest {
        val repository = repository()
        repository.applyScan(added(item("a", mtime = 100), item("b", mtime = 200)))
        val stored = repository.gallery()

        val diff = LibraryDiff.diff(stored, listOf(stored.first { it.id == "a" }), setOf(GRANT))
        repository.applyScan(diff)

        assertEquals(listOf("a"), repository.gallery().map { it.id })
    }

    @Test
    fun afileWithAnOriginalInTheBinKeepsItsRow() = runTest {
        // The rule that matters most here. That row is the only handle on an original still
        // sitting in the bin; deleting it because the optimised copy is gone would lose a
        // file the user can still restore.
        val repository = repository()
        repository.applyScan(added(item("a", mtime = 100)))
        val stored = repository.gallery().single()
        repository.record(
            UndoEntry(
                id = "",
                mediaId = stored.id,
                location = UndoLocation.BIN,
                ref = MediaRef("content://bin/a"),
                expiresAt = null,
            ),
        )

        repository.applyScan(LibraryDiff.diff(listOf(stored), emptyList(), setOf(GRANT)))

        assertEquals(listOf(stored.id), repository.gallery().map { it.id })
    }

    private fun added(vararg items: MediaItem) = LibraryDiff.Result(
        added = items.toList(),
        modified = emptyList(),
        removed = emptyList(),
        unchanged = emptyList(),
    )

    private fun grant() = FolderGrant(id = GRANT, platformRef = MediaRef("content://tree"), mode = FolderMode.KEEP)

    private fun item(id: String, mtime: Long) = MediaItem(
        id = id,
        platformRef = MediaRef("content://$id"),
        name = "$id.mp4",
        kind = MediaKind.VIDEO,
        codec = "hevc",
        width = 1920,
        height = 1080,
        fps = 30.0,
        bitrate = 10_000_000,
        size = 100_000_000,
        duration = 60_000,
        takenAt = null,
        location = null,
        cameraModel = null,
        phash = null,
        sha256 = null,
        status = MediaStatus.CANDIDATE,
        mtime = mtime,
        folderGrantId = GRANT,
    )

    /**
     * A repository over a folder that has been granted, which is the only state the app is
     * ever in when it scans.
     *
     * The `recordGrants` call is not scaffolding. Every item below carries
     * `folderGrantId = GRANT`, `media_item.folder_grant_id` references `folder_grant(id)`,
     * and foreign keys are on here now as they always were on the phone — so without it
     * every one of these tests inserts a row pointing at a table with nothing in it, and
     * SQLite refuses. That is exactly the crash, and these seven tests passed straight
     * through it for as long as the driver they ran on left the constraint off.
     */
    private suspend fun repository(): TrimRepository {
        var minted = 0
        val driver = testDriver()
        return TrimRepository(
            db = TrimDatabase(driver),
            io = Dispatchers.Unconfined,
            newId = { "id-${minted++}" },
            nowMs = { 0L },
            readSettings = { Settings() },
            readTier = { Tier.FREE },
            monthStartMs = { 0L },
        ).apply { recordGrants(listOf(grant())) }
    }

    private companion object {
        const val GRANT = "grant-1"
    }
}
