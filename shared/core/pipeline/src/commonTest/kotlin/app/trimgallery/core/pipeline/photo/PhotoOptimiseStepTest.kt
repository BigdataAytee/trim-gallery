package app.trimgallery.core.pipeline.photo

import app.trimgallery.core.model.FolderGrant
import app.trimgallery.core.model.MediaFlags
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.model.MediaRef
import app.trimgallery.core.model.PhotoFormat
import app.trimgallery.core.model.QualityTarget
import app.trimgallery.core.model.Settings
import app.trimgallery.core.model.SkipReason
import app.trimgallery.core.model.UndoLocation
import app.trimgallery.engine.Image
import app.trimgallery.engine.LibraryStorage
import app.trimgallery.engine.PhotoCodec
import app.trimgallery.engine.QualityScorer
import app.trimgallery.engine.Source
import app.trimgallery.engine.Stat
import app.trimgallery.engine.TempFile
import app.trimgallery.engine.YuvWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest

/**
 * BUILD.md rule 3 applies to photographs exactly as it does to video, and photographs are
 * the files people are least willing to lose.
 */
class PhotoOptimiseStepTest {

    private val ref = MediaRef("content://tree/DCIM/IMG_0001.jpg")

    private fun item(
        codec: String = "jpeg",
        kind: MediaKind = MediaKind.PHOTO,
        size: Long = 4_000_000,
        flags: MediaFlags = MediaFlags(),
    ) = MediaItem(
        id = "item-1",
        platformRef = ref,
        name = "IMG_0001.jpg",
        kind = kind,
        codec = codec,
        width = 4032,
        height = 3024,
        fps = null,
        bitrate = null,
        size = size,
        duration = null,
        takenAt = null,
        location = null,
        cameraModel = null,
        flags = flags,
        phash = null,
        sha256 = null,
        mtime = 1_700_000_000_000,
    )

    private class FakeStorage(var stat: Stat, private val bytes: ByteArray = ByteArray(4_000_000)) : LibraryStorage {
        var written: ByteArray? = null
        override fun scan(grants: List<FolderGrant>): Flow<MediaItem> = emptyFlow()
        override suspend fun stat(ref: MediaRef): Stat = stat
        override suspend fun openRead(ref: MediaRef): Source = ArraySource(bytes)
        override suspend fun tempFile(): TempFile = TempFile("/tmp/x")
        override suspend fun writeTemp(bytes: ByteArray): TempFile {
            written = bytes
            return TempFile("/tmp/out.jpg")
        }
        override suspend fun discard(file: TempFile) = Unit
    }

    private class ArraySource(private val bytes: ByteArray) : Source {
        private var offset = 0
        override fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (this.offset >= bytes.size) return -1
            val n = minOf(length, bytes.size - this.offset)
            bytes.copyInto(into, offset, this.offset, this.offset + n)
            this.offset += n
            return n
        }
        override fun close() = Unit
    }

    /** Size and score both rise with quality, which is what the search assumes. */
    private class FakeCodec(
        private val crossover: Int = 80,
        private val bytesAtQuality: (Int) -> Int = { q -> 10_000 * q },
        private val alpha: Byte = 0xFF.toByte(),
        private val decodes: Boolean = true,
    ) : PhotoCodec {
        val jpegliCalls = mutableListOf<Int>()
        val heicCalls = mutableListOf<Int>()
        var pngCalls = 0
        var jxlCalls = 0

        override suspend fun decode(src: ByteArray): Image? {
            if (!decodes) return null
            // Two pixels is enough: the step only inspects the alpha channel.
            return Image(1, 2, byteArrayOf(1, 2, 3, alpha, 4, 5, 6, alpha))
        }

        override suspend fun jpegli(src: ByteArray, q: Int): ByteArray {
            jpegliCalls += q
            return ByteArray(bytesAtQuality(q)) { q.toByte() }
        }

        override suspend fun heic(src: Image, q: Int): ByteArray {
            heicCalls += q
            return ByteArray(bytesAtQuality(q) / 2) { q.toByte() }
        }

        override suspend fun jxlRecompress(src: ByteArray): ByteArray {
            jxlCalls += 1
            return ByteArray(src.size * 4 / 5)
        }

        override suspend fun pngOptimise(src: ByteArray): ByteArray {
            pngCalls += 1
            return ByteArray(src.size * 3 / 4)
        }

        /** Reads the quality back out of the encoded bytes and scores it. */
        fun scorer() = object : QualityScorer {
            override suspend fun xpsnr(a: YuvWindow, b: YuvWindow) = 0.0
            override suspend fun vmaf(a: YuvWindow, b: YuvWindow, subsample: Int) = 0.0
            override suspend fun ssim2(a: Image, b: Image): Double {
                val quality = (jpegliCalls + heicCalls).last()
                return 90.0 + (quality - crossover) * 0.5
            }
        }
    }

    private suspend fun run(
        storage: FakeStorage,
        codec: FakeCodec,
        item: MediaItem = item(),
        settings: Settings = Settings(),
    ) = PhotoOptimiseStep(storage, codec, codec.scorer())
        .run(item, settings, UndoLocation.BIN)

    @Test
    fun `a jpeg is searched, gated and turned into a plan`() = runTest {
        val storage = FakeStorage(Stat(4_000_000, 1_700_000_000_000, exists = true))
        val codec = FakeCodec(crossover = 80)

        val ready = assertIs<PhotoOptimiseStep.Result.Ready>(run(storage, codec))

        assertEquals(PhotoRoute.JPEGLI, ready.route)
        assertEquals("item-1", ready.plan.mediaId)
        assertEquals(ref, ready.plan.original)
        assertEquals(4_000_000, ready.plan.expectedSize)
        assertTrue(ready.ssim2!! >= 90.0, "the winner must clear the gate")
        assertTrue(ready.newSize < 4_000_000)
        assertTrue(codec.jpegliCalls.isNotEmpty())
    }

    @Test
    fun `Compact mode accepts a lower score, and therefore a lower quality`() = runTest {
        val storage = FakeStorage(Stat(4_000_000, 1_700_000_000_000, exists = true))
        val standard = FakeCodec(crossover = 80)
        val compact = FakeCodec(crossover = 80)

        val a = assertIs<PhotoOptimiseStep.Result.Ready>(run(storage, standard))
        val b = assertIs<PhotoOptimiseStep.Result.Ready>(
            run(storage, compact, settings = Settings(qualityTarget = QualityTarget.COMPACT)),
        )
        assertTrue(b.quality!! < a.quality!!, "Compact settled at ${b.quality}, Standard at ${a.quality}")
    }

    @Test
    fun `a photo that cannot clear the gate is left alone`() = runTest {
        // The one outcome that must never happen is replacing a photograph with a copy the
        // user can see is worse.
        val storage = FakeStorage(Stat(4_000_000, 1_700_000_000_000, exists = true))
        val skipped = assertIs<PhotoOptimiseStep.Result.Skipped>(
            run(storage, FakeCodec(crossover = 200)),
        )
        assertEquals(SkipReason.COULD_NOT_REACH_QUALITY, skipped.reason)
        assertEquals(null, storage.written, "nothing may be staged for a file that failed")
    }

    @Test
    fun `an output that is not smaller is never committed`() = runTest {
        val storage = FakeStorage(Stat(4_000_000, 1_700_000_000_000, exists = true))
        val codec = FakeCodec(crossover = 60, bytesAtQuality = { 9_000_000 })
        val skipped = assertIs<PhotoOptimiseStep.Result.Skipped>(run(storage, codec))
        assertEquals(SkipReason.WOULD_NOT_SHRINK, skipped.reason)
    }

    @Test
    fun `a transparent image never takes the lossy path`() = runTest {
        // JPEG has no alpha, so transparency would be flattened against whatever happened
        // to be behind it — a visible change the gate cannot see, because it would compare
        // a flattened result against a flattened reference and call it perfect.
        val storage = FakeStorage(Stat(4_000_000, 1_700_000_000_000, exists = true))
        val codec = FakeCodec(crossover = 60, alpha = 0x80.toByte())
        val skipped = assertIs<PhotoOptimiseStep.Result.Skipped>(run(storage, codec))
        assertTrue(skipped.detail.contains("transparency"), skipped.detail)
    }

    @Test
    fun `a file edited while it was being optimised is requeued, not replaced`() = runTest {
        val storage = object : LibraryStorage by FakeStorage(Stat(4_000_000, 1_700_000_000_000, true)) {
            private var calls = 0
            override suspend fun stat(ref: MediaRef): Stat {
                calls += 1
                // The snapshot before the work, then a different file after it.
                return if (calls == 1) {
                    Stat(4_000_000, 1_700_000_000_000, exists = true)
                } else {
                    Stat(3_000_000, 1_700_000_999_999, exists = true)
                }
            }
        }
        val codec = FakeCodec(crossover = 60)
        val result = PhotoOptimiseStep(storage, codec, codec.scorer())
            .run(item(), Settings(), UndoLocation.BIN)
        assertIs<PhotoOptimiseStep.Result.SourceChanged>(result)
    }

    // ------------------------------------------------------------ lossless paths

    @Test
    fun `a screenshot is repacked with no quality gate at all`() = runTest {
        val storage = FakeStorage(Stat(700_000, 1_700_000_000_000, exists = true), ByteArray(700_000))
        val codec = FakeCodec()
        val png = item(codec = "png", kind = MediaKind.PNG, size = 700_000).copy(width = 1080, height = 2400)

        val ready = assertIs<PhotoOptimiseStep.Result.Ready>(run(storage, codec, png))

        assertEquals(PhotoRoute.PNG_REPACK, ready.route)
        assertEquals(1, codec.pngCalls)
        assertEquals(null, ready.ssim2, "a lossless repack has no score to report")
        assertEquals(0, ready.probes)
        assertTrue(codec.jpegliCalls.isEmpty(), "no lossy encode may happen on this path")
    }

    @Test
    fun `reversible mode recompresses to JPEG XL without a gate`() = runTest {
        val storage = FakeStorage(Stat(4_000_000, 1_700_000_000_000, exists = true))
        val codec = FakeCodec()
        val ready = assertIs<PhotoOptimiseStep.Result.Ready>(
            run(storage, codec, settings = Settings(photoReversible = true)),
        )
        assertEquals(PhotoRoute.JXL_LOSSLESS, ready.route)
        assertEquals(1, codec.jxlCalls)
        assertEquals(null, ready.ssim2)
    }

    @Test
    fun `the HEIC setting uses the platform writer`() = runTest {
        val storage = FakeStorage(Stat(4_000_000, 1_700_000_000_000, exists = true))
        val codec = FakeCodec(crossover = 80)
        val ready = assertIs<PhotoOptimiseStep.Result.Ready>(
            run(storage, codec, settings = Settings(photoFormat = PhotoFormat.HEIC)),
        )
        assertEquals(PhotoRoute.HEIC, ready.route)
        assertTrue(codec.heicCalls.isNotEmpty())
        assertTrue(codec.jpegliCalls.isEmpty())
    }

    @Test
    fun `a file that will not decode is skipped, not guessed at`() = runTest {
        val storage = FakeStorage(Stat(4_000_000, 1_700_000_000_000, exists = true))
        val skipped = assertIs<PhotoOptimiseStep.Result.Skipped>(
            run(storage, FakeCodec(decodes = false)),
        )
        assertEquals(SkipReason.UNSUPPORTED_CODEC, skipped.reason)
    }

    @Test
    fun `an original that vanished is never replaced`() = runTest {
        val storage = FakeStorage(Stat(0, 0, exists = false))
        assertIs<PhotoOptimiseStep.Result.SourceChanged>(run(storage, FakeCodec()))
    }
}
