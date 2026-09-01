package app.trimgallery.core.data

import app.trimgallery.core.data.db.TrimDatabase
import app.trimgallery.core.domain.billing.Tier
import app.trimgallery.core.model.FolderGrant
import app.trimgallery.core.model.FolderMode
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.model.MediaRef
import app.trimgallery.core.model.MediaStatus
import app.trimgallery.core.model.Settings
import app.trimgallery.core.pipeline.LibraryDiff
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * Granting a folder and scanning it, which is what crashed on a phone and looped forever.
 *
 * `media_item.folder_grant_id` references `folder_grant(id)`. Nothing wrote a `folder_grant`
 * row when a folder was granted — only Settings did, when somebody chose a mode — so the
 * first scan inserted media rows pointing at an empty table and SQLite refused. The grant
 * was already persisted by then, so every launch read it, rescanned, and died the same way.
 *
 * These tests run with `PRAGMA foreign_keys = ON`, as the phone does. Without that they all
 * pass against the bug, which is exactly what happened.
 */
class GrantedFolderScanTest {

    @Test
    fun grantingAFolderThenScanningItDoesNotThrow() = runTest {
        // The whole crash, in three lines. Before `recordGrants` existed, the third threw
        // `FOREIGN KEY constraint failed` and took the app with it.
        val repository = repository()
        repository.recordGrants(listOf(grant()))
        repository.applyScan(added(item("a")))

        assertEquals(listOf("a"), repository.gallery().map { it.id })
    }

    @Test
    fun scanningWithoutRecordingTheGrantIsRefused() = runTest {
        // The bug itself, asserted rather than described. If this ever stops throwing, the
        // foreign key has been turned off somewhere and the test above proves nothing.
        val repository = repository()

        assertFailsWith<Exception> { repository.applyScan(added(item("a"))) }
    }

    @Test
    fun theGrantRowIsFoundByTheTreeUriTheScanUses() = runTest {
        // One identity, not two. `GrantedFolders` uses the tree URI as the grant's id and
        // `SafStorage.scan` copies it onto every item; a row keyed on a minted UUID could
        // never match it, however correct it looked in isolation.
        val repository = repository()
        repository.recordGrants(listOf(grant()))

        assertNotNull(repository.folderGrant(TREE))
        assertEquals(TREE, repository.folderGrant(TREE)?.platformRef?.value)
    }

    @Test
    fun recordingTheSameGrantEveryLaunchKeepsTheModeTheUserChose() = runTest {
        // This runs on every launch, so it must not undo Settings. A folder set to Free
        // that quietly reverts to Keep overnight is a promise broken in the safe direction,
        // which is still a promise broken.
        val repository = repository()
        repository.recordGrants(listOf(grant()))
        repository.saveFolderGrant(grant().copy(mode = FolderMode.FREE))

        repository.recordGrants(listOf(grant()))

        assertEquals(FolderMode.FREE, repository.folderGrant(TREE)?.mode)
    }

    @Test
    fun asecondLaunchOverTheSameLibraryAddsNothing() = runTest {
        // The crash loop's happy twin: launch, scan, launch again. The second scan finds
        // the same files and must leave one row each, not two.
        val repository = repository()
        repository.recordGrants(listOf(grant()))
        repository.applyScan(added(item("a"), item("b")))

        val stored = repository.stored(listOf(grant()))
        repository.applyScan(LibraryDiff.diff(stored, stored, setOf(TREE)))

        assertEquals(2, repository.gallery().size)
    }

    private fun added(vararg items: MediaItem) = LibraryDiff.Result(
        added = items.toList(),
        modified = emptyList(),
        removed = emptyList(),
        unchanged = emptyList(),
    )

    /** As `GrantedFolders.grants()` builds it: the tree URI is both the id and the ref. */
    private fun grant() = FolderGrant(
        id = TREE,
        platformRef = MediaRef(TREE),
        mode = FolderMode.KEEP,
        displayName = "Camera",
    )

    /** As `SafStorage.scan` emits it: `folderGrantId` is the grant's id. */
    private fun item(id: String) = MediaItem(
        id = id,
        platformRef = MediaRef("content://$id"),
        name = "$id.mp4",
        kind = MediaKind.VIDEO,
        codec = null,
        width = 0,
        height = 0,
        fps = null,
        bitrate = null,
        size = 100,
        duration = null,
        takenAt = null,
        location = null,
        cameraModel = null,
        phash = null,
        sha256 = null,
        status = MediaStatus.NEW,
        mtime = 1,
        folderGrantId = TREE,
    )

    private fun repository(): TrimRepository {
        var minted = 0
        return TrimRepository(
            db = TrimDatabase(testDriver()),
            io = Dispatchers.Unconfined,
            newId = { "id-${minted++}" },
            nowMs = { 0L },
            readSettings = { Settings() },
            readTier = { Tier.FREE },
            monthStartMs = { 0L },
        )
    }

    private companion object {
        const val TREE = "content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FCamera"
    }
}
