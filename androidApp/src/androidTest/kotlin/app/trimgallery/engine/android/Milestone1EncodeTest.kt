package app.trimgallery.engine.android

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trimgallery.core.model.MediaRef
import app.trimgallery.engine.EncodeOutcome
import app.trimgallery.engine.EncodeSpec
import app.trimgallery.engine.Setting
import app.trimgallery.engine.TempFile
import app.trimgallery.engine.VideoCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Milestone 1 (BUILD.md § 13.1): encode one video to HEVC with audio passthrough at
 * background priority, then prove the output **opens and plays for its full duration**.
 *
 * Runs against the golden H.264 clip in `shared/testdata`, which carries a real AAC
 * track so passthrough is exercised rather than assumed.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class Milestone1EncodeTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var source: File
    private lateinit var output: File

    @Before
    fun copyGoldenClipOutOfAssets() {
        source = File(context.cacheDir, GOLDEN).apply {
            parentFile?.mkdirs()
            outputStream().use { out ->
                InstrumentationRegistry.getInstrumentation().context.assets.open(GOLDEN).use { it.copyTo(out) }
            }
        }
        output = File(context.cacheDir, "milestone1-out.mp4")
        output.delete()
    }

    /**
     * What this device can actually do, reported rather than asserted.
     *
     * This is the part of the smoke job that is meaningful on an emulator. It proves the
     * capability query itself runs on a real Android runtime — `MediaCodecList`, the
     * hardware filter, the performance-point probe — which is the code every encode
     * decision depends on and which no JVM test can reach. What it must not do is require
     * a hardware encoder: an emulator has none, and the correct behaviour there is an
     * honest report of zero, not a failure.
     *
     * The numbers go to logcat so a CI run leaves a record of what the device offered.
     */
    @Test
    fun reportsCodecCapabilities() {
        val caps = MediaCodecFactory(context).capabilities()
        for ((name, e) in listOf("HEVC" to caps.hevc, "AV1" to caps.av1)) {
            Log.i(
                TAG,
                "$name: hardware=${e.hardware} max=${e.maxWidth}x${e.maxHeight}@${e.maxFps} " +
                    "cq=${e.cqSupported} performancePoints=${e.performancePoints.size}",
            )
        }
        // The only assertion: the query returned without throwing and is self-consistent.
        // A device with no hardware encoder must report no ceiling either, rather than
        // advertising limits nothing can meet.
        for (e in listOf(caps.hevc, caps.av1)) {
            if (!e.hardware) {
                assertEquals("a non-hardware encoder must not advertise a ceiling", 0, e.maxWidth)
            }
        }
    }

    @Test
    fun encodesToHevcWithAudioPassthroughAndPlaysBackInFull() = runBlocking {
        val factory = MediaCodecFactory(context)
        val caps = factory.capabilities()

        // BUILD.md § 2 rule 2: with no hardware HEVC encoder the correct behaviour is to
        // skip the file, never to encode it in software. So this test skips too — a skip
        // here is the rule being observed, not a gap in coverage.
        //
        // The CI emulator always lands here, by design: an ATD image has no hardware
        // encoder, and adding a software fallback to make this green would invert the one
        // rule the whole app is built around. The smoke job's purpose is install, launch
        // and the capability report below; the encode itself is a physical-device test and
        // is listed as one in PROJECT.md's device-required section.
        //
        // `caps.hevc.hardware`, not `caps.hardwareHevc`: milestone 12 split `CodecCaps`
        // into one `EncoderCaps` per codec, because AV1's ceiling is commonly lower than
        // HEVC's on the same chip and a single flag hid that. This file kept the old flat
        // name for four milestones without anyone noticing, because nothing compiled it.
        assumeTrue(
            "SKIPPED: no hardware HEVC encoder on this device. Expected on an emulator — " +
                "BUILD.md § 2 rule 2 forbids a software fallback, so the encode path is " +
                "verified on physical hardware only (see PROJECT.md, device-required).",
            caps.hevc.hardware,
        )

        val spec = EncodeSpec(
            codec = VideoCodec.HEVC,
            setting = Setting(bitrate = TARGET_BITRATE),
            width = SOURCE_WIDTH,
            height = SOURCE_HEIGHT,
            fps = SOURCE_FPS,
        )

        val progress = mutableListOf<Float>()
        val outcome = withTimeout(ENCODE_TIMEOUT_MS) {
            factory.encoder(spec, background = true)
                .encode(MediaRef(Uri.fromFile(source).toString()), TempFile(output.absolutePath)) {
                    progress += it
                }
        }

        val success = outcome as? EncodeOutcome.Success
            ?: error("expected a successful encode, got $outcome")

        // --- it produced a file ------------------------------------------------
        assertTrue("output missing", output.isFile)
        assertTrue("output empty", output.length() > 0)
        assertEquals(output.length(), success.bytes)
        assertTrue("progress never reported", progress.isNotEmpty())

        // --- it is HEVC video with the audio passed through ---------------------
        val tracks = trackFormats(output)
        val video = tracks.single { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
        val audio = tracks.singleOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }

        assertEquals("video should be HEVC", MediaFormat.MIMETYPE_VIDEO_HEVC, video.getString(MediaFormat.KEY_MIME))
        assertNotNull("audio track was dropped; milestone 1 requires passthrough", audio)
        assertEquals(
            "audio should be passed through untouched, not re-encoded",
            SOURCE_AUDIO_MIME,
            audio!!.getString(MediaFormat.KEY_MIME),
        )

        // --- it reports the full duration ---------------------------------------
        val sourceDuration = durationUs(source)
        val outputDuration = durationUs(output)
        assertTrue(
            "duration changed: source ${sourceDuration}us, output ${outputDuration}us",
            abs(outputDuration - sourceDuration) <= DURATION_TOLERANCE_US,
        )

        // --- it actually plays, all the way to the end ---------------------------
        val playedToEnd = playThrough(output)
        assertTrue("output did not play through to the end", playedToEnd)
    }

    /** Plays the file and resolves true once the player reaches [Player.STATE_ENDED]. */
    private suspend fun playThrough(file: File): Boolean = withContext(Dispatchers.Main) {
        val ended = CompletableDeferred<Boolean>()
        val player = ExoPlayer.Builder(context).build()
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) ended.complete(true)
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                ended.completeExceptionally(error)
            }
        }
        try {
            player.addListener(listener)
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            player.prepare()
            player.play()
            withTimeout(PLAYBACK_TIMEOUT_MS) { ended.await() }
        } finally {
            player.removeListener(listener)
            player.release()
        }
    }

    private fun trackFormats(file: File): List<MediaFormat> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).map(extractor::getTrackFormat)
        } finally {
            extractor.release()
        }
    }

    private fun durationUs(file: File): Long = trackFormats(file)
        .filter { it.containsKey(MediaFormat.KEY_DURATION) }
        .maxOf { it.getLong(MediaFormat.KEY_DURATION) }

    private companion object {
        const val TAG = "TrimGalleryCaps"
        const val GOLDEN = "golden-h264-640x360-3s.mp4"
        const val SOURCE_WIDTH = 640
        const val SOURCE_HEIGHT = 360
        const val SOURCE_FPS = 30.0
        const val SOURCE_AUDIO_MIME = "audio/mp4a-latm"
        const val TARGET_BITRATE = 400_000
        const val ENCODE_TIMEOUT_MS = 120_000L
        const val PLAYBACK_TIMEOUT_MS = 30_000L

        /** One frame at 30 fps is 33 333 us; allow a little container rounding. */
        const val DURATION_TOLERANCE_US = 100_000L
    }
}
