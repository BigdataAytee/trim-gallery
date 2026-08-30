package app.trimgallery.core.pipeline.verify

import app.trimgallery.core.model.FolderGrant
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.model.MediaRef
import app.trimgallery.core.model.SkipReason
import app.trimgallery.core.model.UndoLocation
import app.trimgallery.engine.BitrateMode
import app.trimgallery.engine.EncodeOutcome
import app.trimgallery.engine.Image
import app.trimgallery.engine.LibraryStorage
import app.trimgallery.engine.Ms
import app.trimgallery.engine.OutputProbe
import app.trimgallery.engine.ProbedOutput
import app.trimgallery.engine.QualityScorer
import app.trimgallery.engine.Setting
import app.trimgallery.engine.Source
import app.trimgallery.engine.Stat
import app.trimgallery.engine.TempFile
import app.trimgallery.engine.YuvSource
import app.trimgallery.engine.YuvWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest

/**
 * BUILD.md § 5: *"If VMAF < 95, step up one notch and re-encode; max twice, then log as
 * failed and skip permanently."*
 *
 * The interesting assertions are about what does **not** happen: how many encodes are
 * spent, which failures are retried, and — the one that matters most — that no
 * `ReplacePlan` ever escapes for a file that did not pass.
 */
class VerifyPassTest {

    private val ref = MediaRef("content://tree/DCIM/VID_0001.mp4")

    private val item = MediaItem(
        id = "item-1",
        platformRef = ref,
        name = "VID_0001.mp4",
        kind = MediaKind.VIDEO,
        codec = "h264",
        width = 1920,
        height = 1080,
        fps = 30.0,
        bitrate = 20_000_000,
        size = 100_000_000,
        duration = 60_000,
        takenAt = null,
        location = null,
        cameraModel = "Pixel",
        phash = null,
        sha256 = null,
        mtime = 1_700_000_000_000,
    )

    private val snapshot = VerifyPass.Snapshot(size = 100_000_000, mtime = 1_700_000_000_000)

    private class FakeStorage(var stat: Stat) : LibraryStorage {
        val discarded = mutableListOf<TempFile>()
        private var counter = 0
        override fun scan(grants: List<FolderGrant>): Flow<MediaItem> = emptyFlow()
        override suspend fun stat(ref: MediaRef): Stat = stat
        override suspend fun openRead(ref: MediaRef): Source = error("not needed")
        override suspend fun tempFile(): TempFile = TempFile("/tmp/${counter++}")
        override suspend fun discard(file: TempFile) { discarded += file }
    }

    private class FakeYuv : YuvSource {
        override suspend fun decodeWindow(ref: MediaRef, start: Ms, len: Ms, width: Int) = win()
        override suspend fun decodeWindow(file: TempFile, start: Ms, len: Ms, width: Int) = win()
        private fun win() = YuvWindow(1920, 1080, 1, ByteArray(1), ByteArray(1), ByteArray(1))
    }

    /** Quality rises with bitrate, which is the assumption the whole ladder rests on. */
    private class BitrateScorer(private val perMbps: Double) : QualityScorer {
        var lastSetting: Setting? = null
        override suspend fun xpsnr(a: YuvWindow, b: YuvWindow) = 0.0
        override suspend fun vmaf(a: YuvWindow, b: YuvWindow, subsample: Int): Double {
            val mbps = lastSetting!!.bitrate / 1_000_000.0
            return mbps * perMbps
        }
        override suspend fun ssim2(a: Image, b: Image) = 0.0
    }

    private fun pass(
        storage: FakeStorage,
        scorer: QualityScorer,
        probe: OutputProbe,
        config: VerifyPass.Config = VerifyPass.Config(),
    ) = VerifyPass(Verifier(probe, FakeYuv(), scorer), storage, config)

    private fun probeOf(size: Long = 40_000_000, durationMs: Long = 60_000) = OutputProbe {
        ProbedOutput(durationMs, hasVideo = true, hasAudio = true, sizeBytes = size)
    }

    private suspend fun run(
        storage: FakeStorage,
        scorer: BitrateScorer,
        probe: OutputProbe = probeOf(),
        startBps: Int = 8_000_000,
        config: VerifyPass.Config = VerifyPass.Config(),
        encodes: MutableList<Setting> = mutableListOf(),
    ): VerifyPass.Result = pass(storage, scorer, probe, config).run(
        item = item,
        snapshot = snapshot,
        chosen = Setting(startBps, BitrateMode.VBR),
        undoLocation = UndoLocation.BIN,
        originalHasAudio = true,
    ) { setting ->
        encodes += setting
        scorer.lastSetting = setting
        EncodeOutcome.Success(TempFile("/tmp/out-${encodes.size}.mp4"), 40_000_000, 60_000, "video/hevc", "audio/mp4a-latm", 1000)
    }

    @Test
    fun `a passing encode yields a plan naming the original and the item`() = runTest {
        val storage = FakeStorage(Stat(100_000_000, 1_700_000_000_000, exists = true))
        val result = run(storage, BitrateScorer(perMbps = 13.0))
        val ready = assertIs<VerifyPass.Result.Ready>(result)
        assertEquals(ref, ready.plan.original)
        assertEquals("item-1", ready.plan.mediaId)
        assertEquals(100_000_000, ready.plan.expectedSize)
        assertEquals(1_700_000_000_000, ready.plan.expectedMtime)
        assertEquals(UndoLocation.BIN, ready.plan.undoLocation)
        assertEquals(1, ready.attempts.size)
        assertTrue(storage.discarded.isEmpty(), "a passing temp file must survive to be committed")
    }

    @Test
    fun `below target steps up and succeeds on the second attempt`() = runTest {
        val encodes = mutableListOf<Setting>()
        val storage = FakeStorage(Stat(100_000_000, 1_700_000_000_000, exists = true))
        // 8 Mbps scores 94.4; one 15% notch takes it to 9.2 Mbps and 108 — comfortably over.
        val result = run(storage, BitrateScorer(perMbps = 11.8), encodes = encodes)
        assertIs<VerifyPass.Result.Ready>(result)
        assertEquals(2, encodes.size)
        assertEquals(9_200_000, encodes[1].bitrate)
        assertEquals(listOf(TempFile("/tmp/out-1.mp4")), storage.discarded)
    }

    @Test
    fun `at most two step-ups, then a permanent skip`() = runTest {
        val encodes = mutableListOf<Setting>()
        val storage = FakeStorage(Stat(100_000_000, 1_700_000_000_000, exists = true))
        // Hopeless file: even three notches up cannot reach 95.
        val result = run(storage, BitrateScorer(perMbps = 5.0), encodes = encodes)
        val skipped = assertIs<VerifyPass.Result.Skipped>(result)
        assertEquals(SkipReason.COULD_NOT_REACH_QUALITY, skipped.reason)
        assertEquals(3, encodes.size, "one encode plus at most two step-ups")
        assertEquals(3, storage.discarded.size, "every rejected temp file is thrown away")
    }

    @Test
    fun `the step-up cap is configurable and respected exactly`() = runTest {
        val encodes = mutableListOf<Setting>()
        val storage = FakeStorage(Stat(100_000_000, 1_700_000_000_000, exists = true))
        run(
            storage, BitrateScorer(perMbps = 5.0), encodes = encodes,
            config = VerifyPass.Config(maxStepUps = 0),
        )
        assertEquals(1, encodes.size)
    }

    @Test
    fun `an output that is not smaller is skipped without another encode`() = runTest {
        val encodes = mutableListOf<Setting>()
        val storage = FakeStorage(Stat(100_000_000, 1_700_000_000_000, exists = true))
        val result = run(
            storage, BitrateScorer(perMbps = 13.0),
            probe = probeOf(size = 110_000_000), encodes = encodes,
        )
        val skipped = assertIs<VerifyPass.Result.Skipped>(result)
        assertEquals(SkipReason.WOULD_NOT_SHRINK, skipped.reason)
        // Stepping up raises the bitrate, which could only make it larger still.
        assertEquals(1, encodes.size)
    }

    @Test
    fun `a broken output is not retried at a higher bitrate`() = runTest {
        val encodes = mutableListOf<Setting>()
        val storage = FakeStorage(Stat(100_000_000, 1_700_000_000_000, exists = true))
        val result = run(
            storage, BitrateScorer(perMbps = 13.0),
            probe = probeOf(durationMs = 12_000), encodes = encodes,
        )
        assertIs<VerifyPass.Result.Failed>(result)
        assertEquals(1, encodes.size, "a truncated mux is not a bitrate problem")
    }

    @Test
    fun `a file edited during the encode is requeued, not replaced`() = runTest {
        val storage = FakeStorage(Stat(100_000_000, 1_700_000_000_000, exists = true))
        // The user trimmed the clip in another app while we were working on it.
        storage.stat = Stat(90_000_000, 1_700_000_500_000, exists = true)
        val result = run(storage, BitrateScorer(perMbps = 13.0))
        val changed = assertIs<VerifyPass.Result.SourceChanged>(result)
        assertTrue(changed.detail.contains("size"), changed.detail)
        assertEquals(1, storage.discarded.size, "the temp describes a file that no longer exists")
    }

    @Test
    fun `an original that vanished during the encode is not replaced`() = runTest {
        val storage = FakeStorage(Stat(0, 0, exists = false))
        val result = run(storage, BitrateScorer(perMbps = 13.0))
        assertIs<VerifyPass.Result.SourceChanged>(result)
    }

    @Test
    fun `no hardware encoder is a skip, never a software fallback`() = runTest {
        val storage = FakeStorage(Stat(100_000_000, 1_700_000_000_000, exists = true))
        val result = VerifyPass(
            Verifier(probeOf(), FakeYuv(), BitrateScorer(13.0)), storage,
        ).run(
            item = item,
            snapshot = snapshot,
            chosen = Setting(8_000_000),
            undoLocation = UndoLocation.BIN,
            originalHasAudio = true,
        ) { EncodeOutcome.NoHardwareEncoder }
        val skipped = assertIs<VerifyPass.Result.Skipped>(result)
        assertEquals(SkipReason.NO_HARDWARE_ENCODER, skipped.reason)
    }

    @Test
    fun `a step up moves further than the search's own convergence`() {
        // A notch smaller than SettingSearch's 12% convergence would land inside the
        // bracket the search already called indistinguishable, spending an encode to
        // move nothing.
        val config = VerifyPass.Config()
        val stepped = VerifyPass(
            Verifier(probeOf(), FakeYuv(), BitrateScorer(13.0)),
            FakeStorage(Stat(0, 0, true)),
            config,
        ).stepUp(Setting(10_000_000))
        assertTrue(
            stepped.bitrate >= 10_000_000 * 1.12,
            "a ${stepped.bitrate} bps step is inside the search's own resolution",
        )
    }
}
