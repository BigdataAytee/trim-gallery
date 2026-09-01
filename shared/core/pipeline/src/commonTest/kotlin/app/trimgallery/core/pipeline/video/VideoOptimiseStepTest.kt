package app.trimgallery.core.pipeline.video

import app.trimgallery.core.domain.billing.Tier
import app.trimgallery.core.model.FolderGrant
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.model.MediaRef
import app.trimgallery.core.model.MediaStatus
import app.trimgallery.core.model.Settings
import app.trimgallery.core.model.SkipReason
import app.trimgallery.core.model.UndoLocation
import app.trimgallery.core.pipeline.CodecChoice
import app.trimgallery.core.pipeline.Predictor
import app.trimgallery.core.pipeline.ProbeAndSearch
import app.trimgallery.core.pipeline.night.NightRun
import app.trimgallery.core.pipeline.verify.Verifier
import app.trimgallery.core.pipeline.verify.VerifyPass
import app.trimgallery.engine.CodecCaps
import app.trimgallery.engine.CodecFactory
import app.trimgallery.engine.ContainerFacts
import app.trimgallery.engine.ContainerReader
import app.trimgallery.engine.EncodeOutcome
import app.trimgallery.engine.EncodeSpec
import app.trimgallery.engine.EncoderCaps
import app.trimgallery.engine.HwEncoder
import app.trimgallery.engine.Image
import app.trimgallery.engine.LibraryStorage
import app.trimgallery.engine.Ms
import app.trimgallery.engine.NewCopyPlan
import app.trimgallery.engine.NewCopyResult
import app.trimgallery.engine.OutputProbe
import app.trimgallery.engine.ProbeEncoder
import app.trimgallery.engine.ProbedOutput
import app.trimgallery.engine.QualityScorer
import app.trimgallery.engine.ReplacePlan
import app.trimgallery.engine.ReplaceResult
import app.trimgallery.engine.Replacer
import app.trimgallery.engine.Setting
import app.trimgallery.engine.Source
import app.trimgallery.engine.Stat
import app.trimgallery.engine.TempFile
import app.trimgallery.engine.VideoCodec
import app.trimgallery.engine.YuvSource
import app.trimgallery.engine.YuvWindow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The chain that can destroy a photograph, asserted at its gates rather than at its happy
 * path.
 *
 * Every test here is a way the app could lose someone's file, written as the thing that
 * must not happen. The happy path gets one test; the refusals get the rest, because a
 * replace that runs when it should not is not a bug that shows up as a wrong number on a
 * screen — it shows up as a video the user cannot get back.
 *
 * `Replacer` is faked and **counted**. "The Replacer was never called" is the strongest
 * assertion available in this module: it is the only component in the app that may write
 * to the user's library, so a test that proves it was not reached proves the original was
 * not touched, whatever else went wrong upstream.
 */
class VideoOptimiseStepTest {

    // ------------------------------------------------------------------ the gates

    @Test
    fun `a device with no hardware encoder skips, and never reaches an encoder or the replacer`() = runTest {
        // BUILD.md § 2 rule 2. The failure mode this prevents is not a crash — it is a
        // silent software encode that flattens the battery and cooks the phone.
        val codecs = FakeCodecs(caps = CodecCaps())
        val replacer = FakeReplacer()

        val result = step(codecs = codecs, replacer = replacer).optimise(item())

        val skipped = assertIs<VideoOptimiseStep.Result.Skipped>(result)
        assertEquals(SkipReason.NO_HARDWARE_ENCODER, skipped.reason)
        assertTrue(codecs.encoders.isEmpty(), "no encoder may be created for a file we cannot encode")
        assertTrue(replacer.plans.isEmpty(), "nothing may be replaced")
    }

    @Test
    fun `a file that no setting can encode well enough is skipped rather than replaced worse`() = runTest {
        // The one outcome the app must never produce: a file the user can see is worse.
        val replacer = FakeReplacer()

        val result = step(replacer = replacer, xpsnrPerMbps = 0.5).optimise(item())

        val skipped = assertIs<VideoOptimiseStep.Result.Skipped>(result)
        assertEquals(SkipReason.COULD_NOT_REACH_QUALITY, skipped.reason)
        assertTrue(replacer.plans.isEmpty(), "a file that failed its quality gate must not be replaced")
    }

    @Test
    fun `a file edited while it was encoding is not replaced`() = runTest {
        // safe-replace step 5. The snapshot is taken before the encode and re-checked
        // after; the bytes we measured are not the bytes on disk any more.
        val storage = MovingStorage(before = Stat(SIZE, MTIME, exists = true), after = Stat(SIZE, MTIME + 1, true))
        val replacer = FakeReplacer()

        val result = step(storage = storage, replacer = replacer).optimise(item())

        assertIs<VideoOptimiseStep.Result.SourceChanged>(result)
        assertTrue(replacer.plans.isEmpty(), "an original that moved must not be replaced")
    }

    @Test
    fun `an original that has vanished is never encoded`() = runTest {
        val storage = FakeStorage(Stat(0, 0, exists = false))
        val codecs = FakeCodecs()

        val result = step(storage = storage, codecs = codecs).optimise(item())

        assertIs<VideoOptimiseStep.Result.SourceChanged>(result)
        assertTrue(codecs.encoders.isEmpty(), "there is nothing to encode")
    }

    @Test
    fun `an unreadable container is skipped before any encode`() = runTest {
        val codecs = FakeCodecs()

        val result = step(codecs = codecs, container = null).optimise(item())

        val skipped = assertIs<VideoOptimiseStep.Result.Skipped>(result)
        assertEquals(SkipReason.UNSUPPORTED_CODEC, skipped.reason)
        assertTrue(codecs.encoders.isEmpty())
    }

    // ------------------------------------------------------------- what it reports

    @Test
    fun `a verified smaller file is replaced, and reports what it was and what it is now`() = runTest {
        val replacer = FakeReplacer()

        val result = step(replacer = replacer).optimise(item())

        val done = assertIs<VideoOptimiseStep.Result.Optimised>(result)
        assertEquals(SIZE, done.wasBytes)
        assertEquals(NEW_SIZE, done.nowBytes)
        assertEquals(SIZE - NEW_SIZE, done.savedBytes)
        assertEquals(VideoCodec.HEVC, done.codec)
        assertEquals(1, replacer.plans.size, "exactly one replace, for one file")
    }

    @Test
    fun `the plan handed to the replacer carries the snapshot taken before the encode`() = runTest {
        // The Replacer refuses the swap if these do not match what is on disk. A plan
        // carrying post-encode numbers would defeat the last check in the chain.
        val replacer = FakeReplacer()

        step(replacer = replacer).optimise(item())

        val plan = replacer.plans.single()
        assertEquals(SIZE, plan.expectedSize)
        assertEquals(MTIME, plan.expectedMtime)
        assertEquals(REF, plan.original)
        assertEquals("item-1", plan.mediaId)
        assertEquals(UndoLocation.BIN, plan.undoLocation)
    }

    // --------------------------------------------------------------- the predictor

    @Test
    fun `the winning bitrate is learned only after the replace actually lands`() = runTest {
        // A setting that verified and then failed to commit is not evidence about what this
        // family needs — the file it would describe is not the file on disk.
        val facts = FakeFacts()
        val replacer = FakeReplacer(result = ReplaceResult.RolledBack("the bin was full"))

        val result = step(facts = facts, replacer = replacer).optimise(item())

        assertIs<VideoOptimiseStep.Result.Failed>(result)
        assertTrue(facts.learned.isEmpty(), "a rolled-back replace must teach the predictor nothing")
    }

    @Test
    fun `a replace that lands teaches the predictor the setting that won`() = runTest {
        val facts = FakeFacts()

        step(facts = facts).optimise(item())

        assertEquals(1, facts.learned.size)
        assertTrue(facts.learned.single().second > 0, "the learned bitrate must be the winning one")
    }

    // ------------------------------------------------------------------- priority

    @Test
    fun `the night pass asks for background priority and an explicit optimise does not`() = runTest {
        // codec-priority skill: `KEY_PRIORITY = 1` is what makes a foreground camera win the
        // hardware from the night job. An "Optimise" the user just tapped *is* the
        // foreground, and must not be deprioritised behind itself.
        val night = FakeCodecs()
        step(codecs = night).asNightStep().run(item())
        assertEquals(listOf(true), night.encoders.map { it.background })

        val now = FakeCodecs()
        step(codecs = now).optimise(item(), background = false)
        assertEquals(listOf(false), now.encoders.map { it.background })
    }

    @Test
    fun `the night pass reports bytes saved, and reports a skip as a skip rather than a failure`() = runTest {
        val done = step().asNightStep().run(item())
        assertEquals(NightRun.Outcome.Done(bytesSaved = SIZE - NEW_SIZE), done)

        val skipped = step(codecs = FakeCodecs(caps = CodecCaps())).asNightStep().run(item())
        assertEquals(NightRun.Outcome.Skipped, skipped)
    }

    // ---------------------------------------------------------------- the fixtures

    private fun item(width: Int = 1920, height: Int = 1080) = MediaItem(
        id = "item-1",
        platformRef = REF,
        name = "clip.mp4",
        kind = MediaKind.VIDEO,
        codec = "h264",
        width = width,
        height = height,
        fps = 30.0,
        bitrate = 20_000_000,
        size = SIZE,
        duration = 60_000,
        takenAt = null,
        location = null,
        cameraModel = "Pixel 6",
        phash = null,
        sha256 = null,
        status = MediaStatus.CANDIDATE,
        mtime = MTIME,
    )

    /**
     * A step over fakes, with the real `ProbeAndSearch`, `VerifyPass` and `Verifier` inside.
     *
     * Those three are the gates, so faking them would leave nothing worth testing: what
     * this class does is join them in an order, and the order is the safety contract.
     */
    @Suppress("LongParameterList")
    private fun step(
        storage: LibraryStorage = FakeStorage(Stat(SIZE, MTIME, exists = true)),
        codecs: FakeCodecs = FakeCodecs(),
        replacer: Replacer = FakeReplacer(),
        facts: VideoOptimiseStep.Facts = FakeFacts(),
        container: ContainerFacts? = ContainerFacts(
            codec = "h264",
            width = 1920,
            height = 1080,
            fps = 30.0,
            bitrate = 20_000_000,
            durationMs = 60_000,
            hasAudio = true,
        ),
        xpsnrPerMbps: Double = 8.0,
    ): VideoOptimiseStep {
        val scorer = BitrateScorer(codecs.bitrates, xpsnrPerMbps = xpsnrPerMbps)
        return VideoOptimiseStep(
            storage = storage,
            codecs = codecs,
            containers = object : ContainerReader {
                override suspend fun read(ref: MediaRef): ContainerFacts? = container
            },
            probe = ProbeAndSearch(FakeYuv(), FakeProbeEncoder(codecs.bitrates), scorer),
            verify = VerifyPass(Verifier(OutputProbe { PROBED }, FakeYuv(), scorer), storage),
            replacer = replacer,
            facts = facts,
        )
    }

    /** Where the fakes agree on which bitrate is currently being measured. */
    private class Bitrates {
        var probe: Int = 0
        var encode: Int = 0
    }

    /**
     * Quality rises with bitrate, which is the assumption the whole ladder rests on.
     *
     * XPSNR answers for the search's probe windows and VMAF for the verifier's, each from
     * the bitrate its own stage last used — so a search that picks a low setting is scored
     * on that setting rather than on whatever the other stage happened to try.
     */
    private class BitrateScorer(
        private val bitrates: Bitrates,
        private val xpsnrPerMbps: Double,
        private val vmafPerMbps: Double = 22.0,
    ) : QualityScorer {
        override suspend fun xpsnr(a: YuvWindow, b: YuvWindow) = bitrates.probe / 1_000_000.0 * xpsnrPerMbps
        override suspend fun vmaf(a: YuvWindow, b: YuvWindow, subsample: Int) =
            bitrates.encode / 1_000_000.0 * vmafPerMbps

        override suspend fun ssim2(a: Image, b: Image) = 0.0
    }

    private class FakeProbeEncoder(private val bitrates: Bitrates) : ProbeEncoder {
        override suspend fun encodeWindow(yuv: YuvWindow, setting: Setting): YuvWindow {
            bitrates.probe = setting.bitrate
            return yuv
        }
    }

    private class FakeYuv : YuvSource {
        override suspend fun decodeWindow(ref: MediaRef, start: Ms, len: Ms, width: Int) = win()
        override suspend fun decodeWindow(file: TempFile, start: Ms, len: Ms, width: Int) = win()
        private fun win() = YuvWindow(1920, 1080, 1, ByteArray(1), ByteArray(1), ByteArray(1))
    }

    private class FakeCodecs(
        private val caps: CodecCaps = CodecCaps(
            hevc = EncoderCaps(hardware = true, maxWidth = 3840, maxHeight = 2160, maxFps = 60.0),
        ),
    ) : CodecFactory {
        val bitrates = Bitrates()
        val encoders = mutableListOf<Made>()

        data class Made(val spec: EncodeSpec, val background: Boolean)

        override fun capabilities(): CodecCaps = caps

        override fun encoder(spec: EncodeSpec, background: Boolean): HwEncoder {
            encoders += Made(spec, background)
            bitrates.encode = spec.setting.bitrate
            return object : HwEncoder {
                override suspend fun encode(
                    input: MediaRef,
                    out: TempFile,
                    onProgress: (Float) -> Unit,
                ): EncodeOutcome {
                    onProgress(1f)
                    return EncodeOutcome.Success(out, NEW_SIZE, 60_000, "video/hevc", "audio/mp4a-latm", 1_000)
                }
            }
        }
    }

    private open class FakeStorage(private val stat: Stat) : LibraryStorage {
        private var counter = 0
        override fun scan(grants: List<FolderGrant>): Flow<MediaItem> = emptyFlow()
        override suspend fun stat(ref: MediaRef): Stat = stat
        override suspend fun openRead(ref: MediaRef): Source = error("the step never reads bytes")
        override suspend fun tempFile(): TempFile = TempFile("/tmp/out-${counter++}.mp4")
        override suspend fun writeTemp(bytes: ByteArray): TempFile = TempFile("/tmp/w-${counter++}")
        override suspend fun discard(file: TempFile) = Unit
    }

    /** A file the user edits while we are working on it: the second `stat` differs. */
    private class MovingStorage(private val before: Stat, private val after: Stat) : FakeStorage(before) {
        private var asked = 0
        override suspend fun stat(ref: MediaRef): Stat = if (asked++ == 0) before else after
    }

    private class FakeReplacer(private val result: ReplaceResult = ReplaceResult.Replaced(UNDO, NEW_SIZE)) : Replacer {
        val plans = mutableListOf<ReplacePlan>()

        override suspend fun replace(plan: ReplacePlan): ReplaceResult {
            plans += plan
            return result
        }

        override suspend fun saveCopy(plan: NewCopyPlan): NewCopyResult = error("not this path")
    }

    private class FakeFacts : VideoOptimiseStep.Facts {
        val learned = mutableListOf<Pair<Predictor.Key, Int>>()
        override suspend fun settings() = Settings()
        override suspend fun tier() = Tier.FREE
        override suspend fun prediction(key: Predictor.Key): Predictor.Entry? = null
        override suspend fun learn(key: Predictor.Key, winningBps: Int) {
            learned += key to winningBps
        }

        override suspend fun undoLocation(item: MediaItem) = UndoLocation.BIN
        override suspend fun av1Speed(): CodecChoice.MeasuredSpeed? = null
        override val platform = "android"
        override val device = "Pixel 6"
    }

    private companion object {
        val REF = MediaRef("content://clip.mp4")
        val UNDO = MediaRef("file:///bin/clip.mp4")
        val PROBED = ProbedOutput(60_000, hasVideo = true, hasAudio = true, sizeBytes = NEW_SIZE)
        const val SIZE = 400_000_000L
        const val NEW_SIZE = 170_000_000L
        const val MTIME = 1_700_000_000_000L
    }
}
