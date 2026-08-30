package app.trimgallery.core.pipeline

import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.model.MediaRef
import app.trimgallery.engine.VideoCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BitrateBucketTest {

    @Test
    fun `similar bitrates share a bucket and different ones do not`() {
        // At 4 Mbps a 500 kbps difference is a different kind of file; at 60 Mbps it is noise.
        assertEquals(BitrateBucket.of(3_600_000), BitrateBucket.of(3_900_000))
        assertNotEquals(BitrateBucket.of(3_600_000), BitrateBucket.of(11_000_000))
        assertEquals(BitrateBucket.of(50_000_000), BitrateBucket.of(60_000_000))
    }

    @Test
    fun `buckets rise with bitrate`() {
        val buckets = listOf(500_000L, 2_000_000L, 8_000_000L, 40_000_000L, 200_000_000L)
            .map { BitrateBucket.of(it) }
        assertEquals(buckets.sorted(), buckets)
    }

    @Test
    fun `an unknown bitrate lands in the bottom bucket rather than crashing`() {
        assertEquals(0, BitrateBucket.of(0))
        assertEquals(0, BitrateBucket.of(-1))
    }

    @Test
    fun `anything above the top edge shares the top bucket`() {
        assertEquals(BitrateBucket.of(500_000_000), BitrateBucket.of(900_000_000))
    }

    @Test
    fun `edges are inclusive at the top of their bucket`() {
        BitrateBucket.EDGES_BPS.forEachIndexed { index, edge ->
            assertEquals(index, BitrateBucket.of(edge.toLong()), "edge $edge")
        }
    }
}

class PredictorTest {

    private val fallback = SettingSearch.Bounds(lowBps = 1_000_000, highBps = 20_000_000, startBps = 10_000_000)

    private fun item(
        camera: String? = "Pixel 9 rear",
        codec: String? = "avc1",
        width: Int = 1920,
        height: Int = 1080,
        fps: Double? = 30.0,
        bitrate: Long? = 12_000_000,
    ) = MediaItem(
        id = "1", platformRef = MediaRef("ref"), name = "clip.mp4", kind = MediaKind.VIDEO,
        codec = codec, width = width, height = height, fps = fps, bitrate = bitrate,
        size = 100_000_000, duration = 60_000, takenAt = null, location = null,
        cameraModel = camera, phash = null, sha256 = null, mtime = 0,
    )

    /**
     * The defect this component fixed: AV1 reaches the same quality at roughly two thirds of
     * HEVC's bitrate, so a table keyed without the output codec averages the two together
     * and every prediction from that family is too low for HEVC and too high for AV1 — worse
     * than no prediction, because a confident one narrows the bracket around it.
     */
    @Test
    fun `HEVC and AV1 are different families of the same file`() {
        assertNotEquals(key(codec = VideoCodec.HEVC), key(codec = VideoCodec.AV1))
        assertEquals(key(codec = VideoCodec.AV1), key(codec = VideoCodec.AV1))
    }

    private fun key(item: MediaItem = item(), codec: VideoCodec = VideoCodec.HEVC) =
        Predictor.keyOf(item, "android", "Pixel 9", codec)

    @Test
    fun `files from the same camera in the same shape share a key`() {
        assertEquals(key(item(bitrate = 12_200_000)), key(item(bitrate = 15_000_000)))
    }

    @Test
    fun `clips either side of a bucket edge are different families`() {
        // Documented rather than fixed: every bucketing has edges, and nothing in the
        // container reports the camera mode that would avoid them. The effect splits a
        // prediction in two rather than corrupting it -- each half still converges.
        assertNotEquals(key(item(bitrate = 11_800_000)), key(item(bitrate = 12_200_000)))
    }

    @Test
    fun `a different camera on the same device is a different family`() {
        assertNotEquals(key(item(camera = "Pixel 9 rear")), key(item(camera = "Pixel 9 front")))
    }

    @Test
    fun `resolution, codec and frame rate all separate families`() {
        val base = key()
        assertNotEquals(base, key(item(width = 3840, height = 2160)))
        assertNotEquals(base, key(item(codec = "hevc")))
        assertNotEquals(base, key(item(fps = 60.0)))
    }

    @Test
    fun `near-identical frame rates are the same family`() {
        // 29.97 and 30 are the same camera mode.
        assertEquals(key(item(fps = 29.97)), key(item(fps = 30.0)))
    }

    @Test
    fun `missing metadata becomes its own family rather than being dropped`() {
        // Lumping unknown-camera files in with a real camera's would poison a prediction
        // that is otherwise reliable.
        val unknown = key(item(camera = null, codec = null))
        assertNotEquals(key(), unknown)
        assertEquals(unknown, key(item(camera = null, codec = null)))
    }

    @Test
    fun `with no entry the search uses the full fallback bracket`() {
        assertEquals(fallback, Predictor.bounds(null, fallback))
    }

    @Test
    fun `an unconfident entry moves the start but never narrows the bounds`() {
        // An early wrong guess must not trap every later file in the same family.
        val entry = Predictor.Entry(key(), settingBps = 5_000_000, samples = 3)
        val bounds = Predictor.bounds(entry, fallback)

        assertEquals(fallback.lowBps, bounds.lowBps)
        assertEquals(fallback.highBps, bounds.highBps)
        assertEquals(5_000_000, bounds.startBps)
    }

    @Test
    fun `a confident entry narrows the bracket around the prediction`() {
        // BUILD.md section 5: "If >= 20 prior files match, start at the predicted setting
        // with a narrow bracket."
        val entry = Predictor.Entry(key(), settingBps = 5_000_000, samples = Predictor.CONFIDENT_SAMPLES)
        val bounds = Predictor.bounds(entry, fallback)

        assertEquals(5_000_000, bounds.startBps)
        assertTrue(bounds.lowBps > fallback.lowBps, "bracket should narrow: ${bounds.lowBps}")
        assertTrue(bounds.highBps < fallback.highBps, "bracket should narrow: ${bounds.highBps}")
        assertTrue(bounds.lowBps < 5_000_000 && bounds.highBps > 5_000_000, "prediction should be inside")
    }

    @Test
    fun `twenty samples is the confidence boundary`() {
        val k = key()
        assertTrue(Predictor.Entry(k, 5_000_000, Predictor.CONFIDENT_SAMPLES).confident)
        assertTrue(!Predictor.Entry(k, 5_000_000, Predictor.CONFIDENT_SAMPLES - 1).confident)
    }

    @Test
    fun `a narrowed bracket never escapes the fallback`() {
        // A prediction near the edge of what the device supports must not push the search
        // outside the range the caller vouched for.
        val high = Predictor.Entry(key(), settingBps = 19_800_000, samples = 40)
        val bounds = Predictor.bounds(high, fallback)
        assertTrue(bounds.highBps <= fallback.highBps)
        assertTrue(bounds.lowBps >= fallback.lowBps)
        assertTrue(bounds.startBps in fallback.lowBps..fallback.highBps)
    }

    @Test
    fun `learning from nothing starts the count at one`() {
        val entry = Predictor.learn(null, key(), winningBps = 6_000_000)
        assertEquals(6_000_000, entry.settingBps)
        assertEquals(1, entry.samples)
        assertTrue(!entry.confident)
    }

    @Test
    fun `learning is a running mean, so one odd clip nudges rather than replaces`() {
        var entry = Predictor.learn(null, key(), 6_000_000)
        repeat(9) { entry = Predictor.learn(entry, key(), 6_000_000) }
        assertEquals(10, entry.samples)
        assertEquals(6_000_000, entry.settingBps)

        // One unusually busy clip.
        entry = Predictor.learn(entry, key(), 16_000_000)
        assertEquals(11, entry.samples)
        assertTrue(
            entry.settingBps in 6_000_000..7_200_000,
            "one outlier should nudge, not dominate: ${entry.settingBps}",
        )
    }

    @Test
    fun `the mean converges on a consistent camera`() {
        var entry: Predictor.Entry? = null
        repeat(30) { entry = Predictor.learn(entry, key(), 4_500_000) }
        val settled = entry!!
        assertTrue(settled.confident)
        assertTrue(settled.settingBps in 4_400_000..4_600_000, "${settled.settingBps}")
    }

    @Test
    fun `a confident table collapses the search to a narrow bracket`() {
        // The end-to-end point of the table: PROJECT.md says this is what takes a typical
        // file from three or four probes to one.
        var entry: Predictor.Entry? = null
        repeat(Predictor.CONFIDENT_SAMPLES) { entry = Predictor.learn(entry, key(), 5_000_000) }
        val bounds = Predictor.bounds(entry, fallback)
        val width = (bounds.highBps - bounds.lowBps).toDouble() / (fallback.highBps - fallback.lowBps)
        assertTrue(width < 0.2, "bracket should be far narrower than the fallback, was $width")
    }
}
