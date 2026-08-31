package app.trimgallery.core.pipeline.index

import app.trimgallery.core.model.BoundingBox
import app.trimgallery.core.model.FaceEmbedding
import app.trimgallery.core.model.FolderGrant
import app.trimgallery.core.model.Label
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.model.MediaRef
import app.trimgallery.core.model.Settings
import app.trimgallery.core.model.TextBlock
import app.trimgallery.engine.Image
import app.trimgallery.engine.Indexer
import app.trimgallery.engine.LibraryStorage
import app.trimgallery.engine.PhotoCodec
import app.trimgallery.engine.Source
import app.trimgallery.engine.Stat
import app.trimgallery.engine.TempFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IndexStepTest {

    private fun item(id: String = "a", kind: MediaKind = MediaKind.PHOTO) = MediaItem(
        id = id,
        platformRef = MediaRef("ref-$id"),
        name = "$id.jpg",
        kind = kind,
        codec = "jpeg",
        width = 64,
        height = 64,
        fps = null,
        bitrate = null,
        size = 100_000,
        duration = null,
        takenAt = null,
        location = null,
        cameraModel = null,
        phash = null,
        sha256 = null,
        mtime = 0,
    )

    private class FakeIndexer(
        val labelsResult: List<Label> = listOf(Label("a", "dog", 0.9f)),
        val facesResult: List<FaceEmbedding> = listOf(
            FaceEmbedding("a", FloatArray(8) { 0.3f }, BoundingBox(0f, 0f, 1f, 1f)),
        ),
        val textResult: List<TextBlock> = listOf(TextBlock("a", "receipt", BoundingBox(0f, 0f, 1f, 1f))),
        val failOn: String? = null,
    ) : Indexer {
        var facesCalled = false
        override suspend fun labels(ref: MediaRef): List<Label> {
            if (failOn == "labels") error("labeller crashed")
            return labelsResult
        }
        override suspend fun faces(ref: MediaRef): List<FaceEmbedding> {
            facesCalled = true
            if (failOn == "faces") error("detector crashed")
            return facesResult
        }
        override suspend fun text(ref: MediaRef): List<TextBlock> {
            if (failOn == "text") error("ocr crashed")
            return textResult
        }
    }

    private class FakeStorage(private val bytes: ByteArray = ByteArray(64 * 64 * 4)) : LibraryStorage {
        override fun scan(grants: List<FolderGrant>): Flow<MediaItem> = emptyFlow()
        override suspend fun stat(ref: MediaRef): Stat = Stat(0, 0, false)
        override suspend fun openRead(ref: MediaRef): Source = object : Source {
            private var offset = 0
            override fun read(into: ByteArray, o: Int, length: Int): Int {
                if (offset >= bytes.size) return -1
                val n = minOf(length, bytes.size - offset)
                bytes.copyInto(into, o, offset, offset + n)
                offset += n
                return n
            }
            override fun close() = Unit
        }
        override suspend fun tempFile(): TempFile = TempFile("/tmp/x")
        override suspend fun writeTemp(bytes: ByteArray): TempFile = TempFile("/tmp/x")
        override suspend fun discard(file: TempFile) = Unit
    }

    private class FakeCodec(private val decodes: Boolean = true) : PhotoCodec {
        override suspend fun decode(src: ByteArray): Image? =
            if (decodes) Image(64, 64, ByteArray(64 * 64 * 4) { (it % 251).toByte() }) else null
        override suspend fun jpegli(src: ByteArray, q: Int) = src
        override suspend fun heic(src: Image, q: Int) = ByteArray(0)
        override suspend fun jxlRecompress(src: ByteArray) = src
        override suspend fun pngOptimise(src: ByteArray) = src
    }

    private class FakeSink : IndexStep.Sink {
        val labels = mutableListOf<Label>()
        val faces = mutableListOf<FaceEmbedding>()
        val text = mutableListOf<TextBlock>()
        var phash: Long? = null
        var sha: String? = null
        var markedIndexed = false

        override suspend fun labels(item: MediaItem, labels: List<Label>) {
            this.labels += labels
        }
        override suspend fun faces(item: MediaItem, faces: List<FaceEmbedding>) {
            this.faces += faces
        }
        override suspend fun text(item: MediaItem, blocks: List<TextBlock>) {
            this.text += blocks
        }
        override suspend fun hashes(item: MediaItem, phash: Long?, sha256: String?) {
            this.phash = phash
            this.sha = sha256
        }
        override suspend fun indexed(item: MediaItem) {
            markedIndexed = true
        }
    }

    private fun step(indexer: Indexer, sink: FakeSink, codec: PhotoCodec = FakeCodec()) =
        IndexStep(indexer, FakeStorage(), codec, sink)

    @Test
    fun `one file yields labels, faces, text and hashes`() = runTest {
        val sink = FakeSink()
        val report = step(FakeIndexer(), sink).run(item(), Settings()) { "abc123" }

        assertEquals(1, report.labels)
        assertEquals(1, report.faces)
        assertEquals(1, report.textBlocks)
        assertTrue(report.hashed)
        assertTrue(sink.markedIndexed)
        assertEquals("abc123", sink.sha)
        assertTrue(sink.phash != null)
    }

    @Test
    fun `turning face clustering off means no embedding is computed at all`() {
        // Not computed and discarded, not computed and hidden. The only way to be sure a
        // thing never leaves the device is not to make it (USER_JOURNEY.md § 8).
        runTest {
            val indexer = FakeIndexer()
            val sink = FakeSink()
            step(indexer, sink).run(item(), Settings(faceClusteringEnabled = false)) { null }

            assertTrue(!indexer.facesCalled, "the detector ran despite the privacy switch")
            assertTrue(sink.faces.isEmpty())
        }
    }

    @Test
    fun `one failing stage does not cost the others`() = runTest {
        // A hundred thousand files guarantees unusual ones; losing every kind of search on
        // an image because its OCR threw would be the wrong trade.
        val sink = FakeSink()
        val report = step(FakeIndexer(failOn = "text"), sink).run(item(), Settings()) { "sha" }

        assertEquals(1, report.labels)
        assertEquals(1, report.faces)
        assertEquals(0, report.textBlocks)
        assertEquals(1, report.failures.size)
        assertTrue(report.failures.single().startsWith("text:"), report.failures.toString())
        assertTrue(sink.markedIndexed, "the file is still indexed; the stage failed, not the file")
    }

    @Test
    fun `failures do not follow the next file`() {
        // They were an instance field once, on an object DI makes a singleton, so one bad
        // file's failures trailed every file indexed after it for the rest of the night.
        runTest {
            val shared = step(FakeIndexer(failOn = "labels"), FakeSink())
            val first = shared.run(item("bad"), Settings()) { "sha" }
            assertEquals(1, first.failures.size)

            val good = IndexStep(FakeIndexer(), FakeStorage(), FakeCodec(), FakeSink())
            assertTrue(good.run(item("fine"), Settings()) { "sha" }.failures.isEmpty())

            // And the same instance, run twice, does not accumulate.
            val again = shared.run(item("bad2"), Settings()) { "sha" }
            assertEquals(1, again.failures.size, "failures accumulated across files")
        }
    }

    @Test
    fun `a video is not perceptually hashed here`() = runTest {
        val sink = FakeSink()
        step(FakeIndexer(), sink).run(item("v", MediaKind.VIDEO), Settings()) { "sha" }
        assertNull(sink.phash)
        assertEquals("sha", sink.sha, "the content hash still applies to video")
    }

    @Test
    fun `an undecodable image still gets its content hash`() = runTest {
        val sink = FakeSink()
        val report = step(FakeIndexer(), sink, FakeCodec(decodes = false)).run(item(), Settings()) { "sha" }
        assertNull(sink.phash)
        assertEquals("sha", sink.sha)
        assertTrue(report.hashed)
    }

    @Test
    fun `the perceptual hash is computed here, not by the platform`() = runTest {
        // Two devices computing it differently would dissolve a user's duplicate groups the
        // moment their library moved (ARCHITECTURE.md § 6).
        val a = FakeSink()
        val b = FakeSink()
        step(FakeIndexer(), a).run(item(), Settings()) { null }
        step(FakeIndexer(), b).run(item(), Settings()) { null }
        assertEquals(a.phash, b.phash)
    }
}
