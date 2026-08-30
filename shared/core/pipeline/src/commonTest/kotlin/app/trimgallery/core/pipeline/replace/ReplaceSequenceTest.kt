package app.trimgallery.core.pipeline.replace

import app.trimgallery.core.model.FolderGrant
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaRef
import app.trimgallery.core.model.UndoEntry
import app.trimgallery.core.model.UndoLocation
import app.trimgallery.core.model.UndoState
import app.trimgallery.engine.LibraryStorage
import app.trimgallery.engine.MetadataCopier
import app.trimgallery.engine.ReplacePlan
import app.trimgallery.engine.ReplaceResult
import app.trimgallery.engine.Source
import app.trimgallery.engine.Stat
import app.trimgallery.engine.TempFile
import app.trimgallery.engine.UndoStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest

/**
 * ARCHITECTURE.md § 14 asks for exactly this: *"Replacer plan/rollback with fake
 * storage."*
 *
 * The fakes below model one thing — where the user's file currently is — and every test
 * asserts on that. It is the only property that matters: after any sequence of failures,
 * at any step, the original must still be somewhere the user can get it.
 */
class ReplaceSequenceTest {

    private val original = MediaRef("content://tree/DCIM/VID_0001.mp4")
    private val temp = TempFile("/data/app/tmp/out.mp4")

    private val plan = ReplacePlan(
        original = original,
        mediaId = "item-1",
        replacement = temp,
        expectedSize = 100_000_000,
        expectedMtime = 1_700_000_000_000,
        undoLocation = UndoLocation.BIN,
    )

    /** Where the original is right now, as far as the world can tell. */
    private enum class Where { LIBRARY, BIN, NOWHERE }

    private class World {
        var original = Where.LIBRARY
        var committed: MediaRef? = null
        var timestampsRestored = false
        var libraryNotified = false
        var undoRow: UndoEntry? = null
        var tempDiscarded = false
        val calls = mutableListOf<String>()
    }

    private class FakeStorage(private val world: World, private var stat: Stat) : LibraryStorage {
        override fun scan(grants: List<FolderGrant>): Flow<MediaItem> = emptyFlow()
        override suspend fun stat(ref: MediaRef): Stat = stat
        override suspend fun openRead(ref: MediaRef): Source = error("originals are read-only, not opened here")
        override suspend fun tempFile(): TempFile = TempFile("/data/app/tmp/x")
        override suspend fun discard(file: TempFile) { world.tempDiscarded = true }
    }

    private class FakeMetadata(private val world: World, private val fail: Boolean = false) : MetadataCopier {
        override suspend fun copy(from: MediaRef, to: TempFile) {
            world.calls += "metadata"
            if (fail) error("could not read EXIF")
        }
    }

    private class FakeUndoStore(
        private val world: World,
        private val failPark: Boolean = false,
        private val failRestore: Boolean = false,
    ) : UndoStore {
        override suspend fun park(ref: MediaRef, mode: UndoLocation): UndoEntry {
            world.calls += "park"
            if (failPark) error("the bin is full")
            world.original = Where.BIN
            return UndoEntry(
                id = "",
                mediaId = "",
                location = mode,
                ref = MediaRef("file:///data/app/bin/VID_0001.mp4"),
                originalSize = 100_000_000,
                expiresAt = null,
            )
        }

        override suspend fun restore(entry: UndoEntry) {
            world.calls += "restore"
            if (failRestore) error("the bin volume is gone")
            world.original = Where.LIBRARY
        }

        override suspend fun sweep(nowEpochMs: Long) = Unit
    }

    private class FakeOps(
        private val world: World,
        private val failAt: String? = null,
        private val failUncommit: Boolean = false,
    ) : ReplaceOps {
        override suspend fun commit(replacement: TempFile, under: MediaRef): Committed {
            world.calls += "commit"
            if (failAt == "commit") error("rename failed")
            world.committed = under
            return Committed(under, size = 40_000_000)
        }

        override suspend fun uncommit(committed: Committed) {
            world.calls += "uncommit"
            if (failUncommit) error("could not remove the replacement")
            world.committed = null
        }

        override suspend fun restoreTimestamps(committed: Committed, mtime: Long) {
            world.calls += "timestamps"
            if (failAt == "timestamps") error("could not set lastModified")
            world.timestampsRestored = true
        }

        override suspend fun notifyLibrary(committed: Committed) {
            world.calls += "notify"
            if (failAt == "notify") error("scanner refused")
            world.libraryNotified = true
        }
    }

    private class FakeJournal(private val world: World, private val fail: Boolean = false) : UndoJournal {
        override suspend fun record(entry: UndoEntry): UndoEntry {
            world.calls += "journal"
            if (fail) error("database is locked")
            val stored = entry.copy(id = "undo-1", state = UndoState.ACTIVE)
            world.undoRow = stored
            return stored
        }

        override suspend fun forget(entry: UndoEntry) { world.undoRow = null }

        override suspend fun expiring(nowEpochMs: Long): List<UndoEntry> = listOfNotNull(world.undoRow)

        override suspend fun setState(entry: UndoEntry, state: UndoState) {
            world.undoRow = entry.copy(state = state)
        }
    }

    private fun sequence(
        world: World,
        stat: Stat = Stat(100_000_000, 1_700_000_000_000, exists = true),
        metadata: MetadataCopier = FakeMetadata(world),
        undo: UndoStore = FakeUndoStore(world),
        journal: UndoJournal = FakeJournal(world),
        ops: ReplaceOps = FakeOps(world),
    ) = ReplaceSequence(FakeStorage(world, stat), metadata, undo, journal, ops)

    @Test
    fun `the contract runs in the order ARCHITECTURE section 7 fixes`() = runTest {
        val world = World()
        val result = sequence(world).replace(plan)

        assertIs<ReplaceResult.Replaced>(result)
        assertEquals(
            listOf("metadata", "park", "commit", "timestamps", "notify", "journal"),
            world.calls,
        )
    }

    @Test
    fun `a successful replace records the undo row and the new size`() = runTest {
        val world = World()
        val result = assertIs<ReplaceResult.Replaced>(sequence(world).replace(plan))

        assertEquals(40_000_000, result.newSize)
        assertEquals(MediaRef("file:///data/app/bin/VID_0001.mp4"), result.undoRef)
        assertEquals("item-1", world.undoRow?.mediaId, "the row must name the item it can restore")
        assertEquals(Where.BIN, world.original, "the original is parked, never deleted")
        assertTrue(world.timestampsRestored)
        assertTrue(world.libraryNotified)
    }

    @Test
    fun `the undo row is written last, after the library already sees the swap`() = runTest {
        // The safe-replace skill: the row must exist before the user can see the space as
        // freed — and nothing reports freed space until this call returns.
        val world = World()
        sequence(world).replace(plan)
        assertEquals("journal", world.calls.last())
    }

    @Test
    fun `a source edited since the plan was made is refused untouched`() = runTest {
        val world = World()
        val result = sequence(world, stat = Stat(90_000_000, 1_700_000_000_000, exists = true)).replace(plan)

        assertIs<ReplaceResult.SourceChanged>(result)
        assertEquals(Where.LIBRARY, world.original)
        assertTrue(world.calls.isEmpty(), "nothing may be mutated once the snapshot is stale")
        assertTrue(world.tempDiscarded)
    }

    @Test
    fun `a source whose mtime moved is refused untouched`() = runTest {
        val world = World()
        val result = sequence(world, stat = Stat(100_000_000, 1_700_000_999_999, exists = true)).replace(plan)
        assertIs<ReplaceResult.SourceChanged>(result)
        assertEquals(Where.LIBRARY, world.original)
    }

    @Test
    fun `a metadata failure costs nothing, because the original has not moved`() = runTest {
        val world = World()
        val result = sequence(world, metadata = FakeMetadata(world, fail = true)).replace(plan)

        assertIs<ReplaceResult.RolledBack>(result)
        assertEquals(Where.LIBRARY, world.original)
        assertEquals(listOf("metadata"), world.calls)
    }

    @Test
    fun `a park failure leaves the original in the library`() = runTest {
        val world = World()
        val result = sequence(world, undo = FakeUndoStore(world, failPark = true)).replace(plan)

        assertIs<ReplaceResult.RolledBack>(result)
        assertEquals(Where.LIBRARY, world.original)
    }

    @Test
    fun `a commit failure un-parks the original`() = runTest {
        val world = World()
        val result = sequence(world, ops = FakeOps(world, failAt = "commit")).replace(plan)

        assertIs<ReplaceResult.RolledBack>(result)
        assertEquals(Where.LIBRARY, world.original)
        assertEquals(listOf("metadata", "park", "commit", "restore"), world.calls)
    }

    @Test
    fun `a timestamp failure unwinds the commit and then the park, in that order`() = runTest {
        val world = World()
        val result = sequence(world, ops = FakeOps(world, failAt = "timestamps")).replace(plan)

        assertIs<ReplaceResult.RolledBack>(result)
        assertEquals(Where.LIBRARY, world.original)
        assertEquals(null, world.committed)
        // Reverse order matters: the identity has to be free before the original can
        // take it back.
        assertEquals(
            listOf("metadata", "park", "commit", "timestamps", "uncommit", "restore"),
            world.calls,
        )
    }

    @Test
    fun `a notify failure unwinds everything`() = runTest {
        val world = World()
        val result = sequence(world, ops = FakeOps(world, failAt = "notify")).replace(plan)

        assertIs<ReplaceResult.RolledBack>(result)
        assertEquals(Where.LIBRARY, world.original)
        assertEquals(null, world.committed)
    }

    @Test
    fun `a journal failure unwinds the swap rather than freeing space with no undo`() = runTest {
        // An optimised file with no undo row is unrecoverable by the UI. Better to keep
        // the original and lose the saving.
        val world = World()
        val result = sequence(world, journal = FakeJournal(world, fail = true)).replace(plan)

        assertIs<ReplaceResult.RolledBack>(result)
        assertEquals(Where.LIBRARY, world.original)
        assertEquals(null, world.undoRow)
    }

    @Test
    fun `a failed uncommit still un-parks the original`() = runTest {
        // Untidy loses to lost: a stray replacement is a swept file, an unreachable
        // original is a deleted photograph.
        val world = World()
        val result = sequence(
            world,
            ops = FakeOps(world, failAt = "timestamps", failUncommit = true),
        ).replace(plan)

        val rolled = assertIs<ReplaceResult.RolledBack>(result)
        assertEquals(Where.LIBRARY, world.original)
        assertTrue(rolled.reason.contains("undo commit"), rolled.reason)
    }

    @Test
    fun `a failed restore is reported loudly, and the original is still in the bin`() = runTest {
        val world = World()
        val result = sequence(
            world,
            undo = FakeUndoStore(world, failRestore = true),
            ops = FakeOps(world, failAt = "notify"),
        ).replace(plan)

        val rolled = assertIs<ReplaceResult.RolledBack>(result)
        assertTrue(rolled.reason.contains("undo park"), rolled.reason)
        // Not lost — parked. That distinction is the entire point of never deleting.
        assertEquals(Where.BIN, world.original)
    }

    @Test
    fun `cancellation mid-sequence rolls back before the cancellation propagates`() = runTest {
        // A night pass is cancelled the instant the user unplugs. Unwinding inside a
        // cancelled coroutine would abandon the swap exactly halfway.
        val world = World()
        val ops = object : ReplaceOps {
            override suspend fun commit(replacement: TempFile, under: MediaRef): Committed {
                world.calls += "commit"
                throw CancellationException("unplugged")
            }
            override suspend fun uncommit(committed: Committed) { world.calls += "uncommit" }
            override suspend fun restoreTimestamps(committed: Committed, mtime: Long) = Unit
            override suspend fun notifyLibrary(committed: Committed) = Unit
        }

        var thrown = false
        try {
            sequence(world, ops = ops).replace(plan)
        } catch (e: CancellationException) {
            thrown = true
        }

        assertTrue(thrown, "the cancellation must not be swallowed")
        assertEquals(Where.LIBRARY, world.original, "an unplugged phone must not eat the file")
        assertEquals(listOf("metadata", "park", "commit", "restore"), world.calls)
    }

    @Test
    fun `the temp file is discarded on every terminal path`() = runTest {
        listOf("commit", "timestamps", "notify").forEach { failAt ->
            val world = World()
            sequence(world, ops = FakeOps(world, failAt = failAt)).replace(plan)
            assertTrue(world.tempDiscarded, "temp left behind after a failure at $failAt")
        }

        val stale = World()
        sequence(stale, stat = Stat(1, 1, exists = true)).replace(plan)
        assertTrue(stale.tempDiscarded)

        val ok = World()
        sequence(ok).replace(plan)
        assertTrue(!ok.tempDiscarded, "a committed replacement is not a temp file any more")
    }
}
