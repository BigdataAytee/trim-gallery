package app.trimgallery.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
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

        // The same file, edited: the scan mints a fresh id for everything it finds, and the
        // merge has to keep the stored one or the photo loses its albums and its history.
        val rescanned = item("freshly-minted-id", mtime = 400).copy(size = 999)
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

    private fun repository(): TrimRepository {
        var minted = 0
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { TrimDatabase.Schema.create(it) }
        return TrimRepository(
            db = TrimDatabase(driver),
            io = Dispatchers.Unconfined,
            newId = { "id-${minted++}" },
            nowMs = { 0L },
            readSettings = { Settings() },
            readTier = { Tier.FREE },
            monthStartMs = { 0L },
        )
    }

    private companion object {
        const val GRANT = "grant-1"
    }
}
