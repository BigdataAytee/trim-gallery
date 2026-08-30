package app.trimgallery.core.pipeline

import app.trimgallery.core.model.FolderGrant
import app.trimgallery.core.model.FolderMode
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.model.MediaRef
import app.trimgallery.core.model.MediaStatus
import app.trimgallery.core.model.SkipReason
import app.trimgallery.engine.CodecCaps
import app.trimgallery.engine.ContainerFacts
import app.trimgallery.engine.ContainerReader
import app.trimgallery.engine.LibraryStorage
import app.trimgallery.engine.Source
import app.trimgallery.engine.Stat
import app.trimgallery.engine.TempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest

class TriageStepTest {

    private val grant = FolderGrant(id = "grant-a", platformRef = MediaRef("tree"), mode = FolderMode.FREE)

    private fun video(
        id: String,
        codec: String = "avc1",
        bitrate: Long = 20_000_000,
        size: Long = 400_000_000,
        status: MediaStatus = MediaStatus.NEW,
    ) = MediaItem(
        id = id,
        platformRef = MediaRef("content://tree/$id"),
        folderGrantId = "grant-a",
        name = "$id.mp4",
        kind = MediaKind.VIDEO,
        codec = codec,
        width = 1920,
        height = 1080,
        fps = 30.0,
        bitrate = bitrate,
        size = size,
        duration = 60_000,
        takenAt = null,
        location = null,
        cameraModel = null,
        phash = null,
        sha256 = null,
        status = status,
        mtime = 1_700_000_000_000,
    )

    private class FakeStorage(private val items: List<MediaItem>) : LibraryStorage {
        var scannedGrants: List<FolderGrant> = emptyList()
        override fun scan(grants: List<FolderGrant>): Flow<MediaItem> {
            scannedGrants = grants
            return items.asFlow()
        }
        override suspend fun stat(ref: MediaRef): Stat = Stat(0, 0, false)
        override suspend fun openRead(ref: MediaRef): Source = error("triage never opens a file")
        override suspend fun tempFile(): TempFile = error("triage never writes")
        override suspend fun discard(file: TempFile) = Unit
    }

    /** Answers whatever the test put in [facts]; null means "cannot parse". */
    private class FakeContainers(private val facts: Map<String, ContainerFacts> = emptyMap()) : ContainerReader {
        val read = mutableListOf<String>()
        override suspend fun read(ref: MediaRef): ContainerFacts? {
            read += ref.value
            return facts[ref.value]
        }
    }

    private class FakeSink(private val rows: List<MediaItem>) : TriageStep.Sink {
        val inserted = mutableListOf<MediaItem>()
        val updated = mutableListOf<MediaItem>()
        val removed = mutableListOf<MediaItem>()
        val verdicts = mutableListOf<Triple<String, MediaStatus, SkipReason?>>()
        val savings = mutableMapOf<String, Long?>()

        override suspend fun stored(grants: List<FolderGrant>): List<MediaItem> = rows
        override suspend fun insert(item: MediaItem) { inserted += item }
        override suspend fun update(item: MediaItem) { updated += item }
        override suspend fun remove(item: MediaItem) { removed += item }
        override suspend fun recordVerdict(
            item: MediaItem,
            status: MediaStatus,
            reason: SkipReason?,
            estSaving: Long?,
        ) {
            verdicts += Triple(item.id, status, reason)
            savings[item.id] = estSaving
        }
    }

    @Test
    fun `a new file is inserted and triaged`() = runTest {
        val sink = FakeSink(rows = emptyList())
        val report = TriageStep(FakeStorage(listOf(video("a"))), FakeContainers(), sink, nowMs = { 1L }).run(listOf(grant))

        assertEquals(listOf("a"), sink.inserted.map { it.id })
        assertEquals(listOf(Triple<String, MediaStatus, SkipReason?>("a", MediaStatus.CANDIDATE, null)), sink.verdicts)
        assertTrue((sink.savings["a"] ?: 0) > 0, "a candidate needs an ordering key")
        assertEquals(1, report.candidates)
    }

    @Test
    fun `an unchanged file is not triaged again`() = runTest {
        // The same answer for everything that did not move, at a cost that grows with the
        // library. BUILD.md § 5 is emphatic that triage is the cheap step.
        val stored = video("a", status = MediaStatus.SKIPPED)
        val sink = FakeSink(rows = listOf(stored))
        val report = TriageStep(FakeStorage(listOf(stored)), FakeContainers(), sink, nowMs = { 1L }).run(listOf(grant))

        assertTrue(sink.verdicts.isEmpty())
        assertTrue(sink.inserted.isEmpty() && sink.updated.isEmpty() && sink.removed.isEmpty())
        assertEquals(1, report.scanned)
    }

    @Test
    fun `a changed file is re-triaged from scratch`() = runTest {
        // ARCHITECTURE.md § 9: DONE/SKIPPED/FAILED → NEW when the file changes.
        val stored = video("a", codec = "hevc", bitrate = 8_000_000, status = MediaStatus.SKIPPED)
            .copy(skipReason = SkipReason.ALREADY_EFFICIENT)
        // The user re-exported it from an editor: now a fat H.264 file.
        val scanned = video("a", size = 900_000_000)
        val sink = FakeSink(rows = listOf(stored))

        TriageStep(FakeStorage(listOf(scanned)), FakeContainers(), sink, nowMs = { 99L }).run(listOf(grant))

        assertEquals(1, sink.updated.size)
        assertEquals(MediaStatus.NEW, sink.updated.single().status)
        assertEquals(listOf(Triple<String, MediaStatus, SkipReason?>("a", MediaStatus.CANDIDATE, null)), sink.verdicts)
    }

    @Test
    fun `a deleted file has its row removed`() = runTest {
        val sink = FakeSink(rows = listOf(video("a"), video("b")))
        TriageStep(FakeStorage(listOf(video("a"))), FakeContainers(), sink, nowMs = { 1L }).run(listOf(grant))
        assertEquals(listOf("b"), sink.removed.map { it.id })
    }

    @Test
    fun `a disabled grant is neither scanned nor diffed against`() = runTest {
        // Scanning a folder the user turned off would report every file in it as removed.
        val storage = FakeStorage(emptyList())
        val sink = FakeSink(rows = listOf(video("a")))
        TriageStep(storage, FakeContainers(), sink, nowMs = { 1L }).run(listOf(grant.copy(enabled = false)))

        assertTrue(storage.scannedGrants.isEmpty())
        assertTrue(sink.removed.isEmpty(), "a folder that was not scanned cannot lose rows")
    }

    @Test
    fun `skips are counted by reason, for the Skipped screen`() = runTest {
        val sink = FakeSink(rows = emptyList())
        val report = TriageStep(
            FakeStorage(
                listOf(
                    video("a", codec = "hevc", bitrate = 6_000_000),
                    video("b", codec = "hevc", bitrate = 6_000_000),
                    video("c", codec = "vp9"),
                ),
            ),
            FakeContainers(),
            sink,
            nowMs = { 1L },
        ).run(listOf(grant))

        assertEquals(2, report.skipped[SkipReason.ALREADY_EFFICIENT])
        assertEquals(1, report.skipped[SkipReason.UNSUPPORTED_CODEC])
        assertEquals(0, report.candidates)
        assertTrue(report.nothingToDo)
    }

    @Test
    fun `device capabilities reach the triage rules`() = runTest {
        val sink = FakeSink(rows = emptyList())
        val caps = CodecCaps(
            hardwareHevc = true, hardwareAv1 = false, cqSupported = true,
            maxWidth = 1280, maxHeight = 720, maxFps = 30.0,
        )
        TriageStep(FakeStorage(listOf(video("a"))), FakeContainers(), sink, nowMs = { 1L }).run(listOf(grant), caps)

        assertEquals(
            listOf(Triple<String, MediaStatus, SkipReason?>("a", MediaStatus.SKIPPED, SkipReason.NO_HARDWARE_ENCODER)),
            sink.verdicts,
        )
    }

    @Test
    fun `the container is read only for files that changed`() = runTest {
        // The scan is one cursor query over thousands of rows; opening every one of those
        // files to read its header would turn a second into a minute on a large library.
        val unchanged = video("a")
        val containers = FakeContainers()
        val sink = FakeSink(rows = listOf(unchanged))

        TriageStep(
            FakeStorage(listOf(unchanged, video("b"))),
            containers,
            sink,
            nowMs = { 1L },
        ).run(listOf(grant))

        assertEquals(listOf("content://tree/b"), containers.read)
    }

    @Test
    fun `container facts decide the verdict, not what the cursor could see`() = runTest {
        // SAF reports a name, a size and an mtime. Whether the clip is HDR — which
        // BUILD.md § 2.5 requires be left alone — is only in the header.
        val sink = FakeSink(rows = emptyList())
        val containers = FakeContainers(
            mapOf(
                "content://tree/a" to ContainerFacts(
                    codec = "hevc",
                    width = 3840,
                    height = 2160,
                    fps = 30.0,
                    bitrate = 60_000_000,
                    durationMs = 60_000,
                    flags = app.trimgallery.core.model.MediaFlags(hdr = true),
                ),
            ),
        )
        TriageStep(FakeStorage(listOf(video("a"))), containers, sink, nowMs = { 1L }).run(listOf(grant))

        assertEquals(
            listOf(Triple<String, MediaStatus, SkipReason?>("a", MediaStatus.SKIPPED, SkipReason.HDR)),
            sink.verdicts,
        )
    }

    @Test
    fun `a file the reader cannot parse still gets a verdict`() = runTest {
        // Returning null must not drop the file silently: the user is owed a reason.
        val sink = FakeSink(rows = emptyList())
        val unparseable = video("a").copy(codec = null, bitrate = null, duration = null)
        TriageStep(FakeStorage(listOf(unparseable)), FakeContainers(), sink, nowMs = { 1L }).run(listOf(grant))

        assertEquals(
            listOf(Triple<String, MediaStatus, SkipReason?>("a", MediaStatus.SKIPPED, SkipReason.UNSUPPORTED_CODEC)),
            sink.verdicts,
        )
    }

    @Test
    fun `triage never opens a file`() = runTest {
        // BUILD.md § 5: metadata only, no decode. FakeStorage throws from openRead and
        // tempFile, so this passing is the assertion.
        val sink = FakeSink(rows = emptyList())
        TriageStep(FakeStorage(listOf(video("a"), video("b"))), FakeContainers(), sink, nowMs = { 1L }).run(listOf(grant))
        assertEquals(2, sink.verdicts.size)
    }
}
