package app.trimgallery.core.pipeline

import app.trimgallery.core.model.MediaItem

/**
 * The table that collapses the search after the app has seen enough of a phone's video.
 *
 * BUILD.md § 5: *"Look up (device, camera model, codec, resolution, fps, bitrate bucket)
 * in the local predictor table. If ≥ 20 prior files match, start at the predicted setting
 * with a narrow bracket."* PROJECT.md is blunter about why it exists: the metric, not the
 * encoder, is the bottleneck, and this is what takes the typical file from three or four
 * probes down to one.
 *
 * The key is deliberately coarse. A phone's camera produces a small number of distinct
 * (resolution, fps, bitrate) shapes, so files cluster hard — but only within one camera
 * on one device. Cross-device sharing would need a server, and this app has no network.
 */
object Predictor {

    /** Below this many samples the prediction is not trusted enough to narrow the search. */
    const val CONFIDENT_SAMPLES = 20

    /**
     * How far either side of a confident prediction the search still looks.
     *
     * Not zero: the prediction is a mean over similar files, and this one may be busier or
     * flatter than the average. ±18% is roughly one step of the bitrate ladder, so a
     * single probe usually confirms it and a second corrects it.
     */
    const val NARROW_BRACKET = 0.18

    /** Identifies a family of files that compress alike. */
    data class Key(
        val platform: String,
        val device: String,
        val cameraModel: String,
        val codec: String,
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrateBucket: Int,
    )

    /** What the table remembers for one [Key]. */
    data class Entry(val key: Key, val settingBps: Int, val samples: Int) {
        val confident: Boolean get() = samples >= CONFIDENT_SAMPLES
    }

    /**
     * The key for [item] on this device.
     *
     * Missing camera model and codec become explicit placeholders rather than being
     * dropped: files with no camera metadata are their own family, and lumping them in
     * with a real camera's would poison a prediction that is otherwise reliable.
     */
    fun keyOf(item: MediaItem, platform: String, device: String): Key = Key(
        platform = platform,
        device = device,
        cameraModel = item.cameraModel ?: UNKNOWN,
        codec = item.codec?.lowercase() ?: UNKNOWN,
        width = item.width,
        height = item.height,
        fps = item.fps?.let { roundFps(it) } ?: 0,
        bitrateBucket = BitrateBucket.of(item.bitrate ?: 0),
    )

    /**
     * The bitrate range the search should cover.
     *
     * A confident entry narrows the bracket around what worked last time and starts
     * there. Anything less starts from [fallback] — an unconfident entry still moves the
     * starting point, because even a handful of samples beats the midpoint of a wide
     * range, but it must not narrow the bounds or an early wrong guess would trap every
     * later file in the same family.
     */
    fun bounds(entry: Entry?, fallback: SettingSearch.Bounds): SettingSearch.Bounds = when {
        entry == null -> fallback

        entry.confident -> {
            val low = (entry.settingBps * (1.0 - NARROW_BRACKET)).toInt()
            val high = (entry.settingBps * (1.0 + NARROW_BRACKET)).toInt()
            SettingSearch.Bounds(
                lowBps = low.coerceAtLeast(fallback.lowBps),
                highBps = high.coerceAtMost(fallback.highBps),
                startBps = entry.settingBps.coerceIn(fallback.lowBps, fallback.highBps),
            )
        }

        else -> fallback.copy(
            startBps = entry.settingBps.coerceIn(fallback.lowBps, fallback.highBps),
        )
    }

    /**
     * Folds a winning setting into the table.
     *
     * A running mean rather than last-write-wins: one unusually busy clip should nudge the
     * prediction, not replace it. The mean is over the settings that actually passed
     * verification, so it converges on what works for this camera rather than on what the
     * search happened to try.
     */
    fun learn(existing: Entry?, key: Key, winningBps: Int): Entry {
        if (existing == null) return Entry(key, winningBps, samples = 1)
        val samples = existing.samples + 1
        val mean = existing.settingBps.toLong() * existing.samples + winningBps
        return Entry(key, (mean / samples).toInt(), samples)
    }

    /** Frame rates cluster on a few values; 29.97 and 30 are the same family. */
    private fun roundFps(fps: Double): Int = when {
        fps <= 0 -> 0
        else -> ((fps + 0.5).toInt() / 5) * 5
    }

    private const val UNKNOWN = "unknown"
}

/**
 * Bitrate buckets for the predictor key.
 *
 * Half-octave steps rather than linear: at 4 Mbps a 500 kbps difference is a different
 * kind of file, at 60 Mbps it is noise. Explicit edges rather than a log formula so the
 * boundaries can be read, argued with and tested.
 *
 * Buckets have edges, and two clips from the same camera minutes apart can straddle one —
 * 11.8 and 12.2 Mbps land in different families. That splits a prediction rather than
 * corrupting it: each half still converges, it just takes longer to become confident.
 * Keying on the camera's *mode* would avoid it, but nothing in the container reports one.
 */
object BitrateBucket {

    /** Upper edge of each bucket, in bits per second. */
    val EDGES_BPS = intArrayOf(
        1_000_000, 1_500_000, 2_000_000, 3_000_000, 4_000_000, 6_000_000,
        8_000_000, 12_000_000, 16_000_000, 24_000_000, 32_000_000,
        48_000_000, 64_000_000, 96_000_000,
    )

    /** Bucket index, 0 for anything below the first edge, [EDGES_BPS].size for above all. */
    fun of(bitrateBps: Long): Int {
        if (bitrateBps <= 0) return 0
        val index = EDGES_BPS.indexOfFirst { bitrateBps <= it }
        return if (index < 0) EDGES_BPS.size else index
    }
}
