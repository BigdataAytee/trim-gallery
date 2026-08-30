package app.trimgallery.core.pipeline

import app.trimgallery.core.model.MediaItem
import app.trimgallery.engine.VideoCodec
import kotlin.math.sqrt

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

    /**
     * How much spread a confident family may have, as a fraction of its mean.
     *
     * A quarter: `Predictor.bounds` narrows a confident family to roughly ±18%, so a
     * family whose own settings scatter wider than that is one the narrow bracket would
     * routinely miss — and a missed bracket costs the whole probe budget.
     */
    const val MAX_RELATIVE_SPREAD = 0.25

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
        /** The source's codec: what the camera wrote. */
        val codec: String,
        /**
         * The codec being *written* — HEVC or AV1 (milestone 12).
         *
         * Not in BUILD.md § 5's list and not in SCHEMA.md's table (both recorded in
         * PROJECT.md), and it has to be here: AV1 reaches the same quality at roughly two
         * thirds of HEVC's bitrate, so a table keyed without it would average the two
         * together. Every prediction from that family would then be too low for HEVC and
         * too high for AV1 — worse than no prediction at all, because the search narrows
         * its bracket around a confident one and would spend its whole probe budget
         * escaping a number no file ever wanted.
         *
         * The same argument applies to a user toggling AV1 on: their existing HEVC history
         * is not thrown away, it simply does not answer questions about AV1.
         */
        val outputCodec: VideoCodec,
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrateBucket: Int,
    )

    /**
     * What the table remembers for one [Key] (SCHEMA.md `predictor`).
     *
     * [settingVar] is the population variance of the settings that have passed
     * verification for this family, carried because the mean alone cannot tell a family
     * that is genuinely predictable from one whose files merely average out. A wide
     * variance is the signal that a narrow bracket would be wrong however many samples
     * back it.
     */
    data class Entry(val key: Key, val settingBps: Int, val samples: Int, val settingVar: Double = 0.0) {
        val confident: Boolean
            get() = samples >= CONFIDENT_SAMPLES && relativeSpread <= MAX_RELATIVE_SPREAD

        /** Standard deviation as a fraction of the mean; 0 when there is nothing to spread. */
        val relativeSpread: Double
            get() = if (settingBps <= 0) 0.0 else sqrt(settingVar) / settingBps
    }

    /**
     * The key for [item] on this device.
     *
     * Missing camera model and codec become explicit placeholders rather than being
     * dropped: files with no camera metadata are their own family, and lumping them in
     * with a real camera's would poison a prediction that is otherwise reliable.
     */
    fun keyOf(item: MediaItem, platform: String, device: String, outputCodec: VideoCodec): Key = Key(
        platform = platform,
        device = device,
        cameraModel = item.cameraModel ?: UNKNOWN,
        codec = item.codec?.lowercase() ?: UNKNOWN,
        outputCodec = outputCodec,
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
            val low = (entry.settingBps * (1.0 - NARROW_BRACKET)).toInt().coerceAtLeast(fallback.lowBps)
            val high = (entry.settingBps * (1.0 + NARROW_BRACKET)).toInt().coerceAtMost(fallback.highBps)

            if (low > high) {
                // The prediction does not overlap the search space at all, so it is not a
                // prediction about *this* file. Since milestone 12 the fallback bracket is
                // derived from the source's own bitrate, so a family whose learned setting
                // was recorded for much busier or much flatter footage lands outside it —
                // and the intersection of the two ranges is then empty.
                //
                // This used to construct `Bounds(fallback.low, narrowedHigh)` with low above
                // high, which the constructor rejects: a crash in the night pass, from a
                // table row that was merely out of date. Trusting the fallback is both safe
                // and correct — the search still starts as near the prediction as the range
                // allows, and one extra probe is the whole cost.
                fallback.copy(startBps = entry.settingBps.coerceIn(fallback.lowBps, fallback.highBps))
            } else {
                SettingSearch.Bounds(
                    lowBps = low,
                    highBps = high,
                    startBps = entry.settingBps.coerceIn(low, high),
                )
            }
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
        if (existing == null) return Entry(key, winningBps, samples = 1, settingVar = 0.0)
        val samples = existing.samples + 1
        val previousMean = existing.settingBps.toDouble()
        val mean = previousMean + (winningBps - previousMean) / samples

        // Welford, adapted to a stored mean and variance rather than a running sum of
        // squares: the table is a database row that survives restarts, so the update has
        // to work from what was persisted and nothing else.
        val previousM2 = existing.settingVar * existing.samples
        val m2 = previousM2 + (winningBps - previousMean) * (winningBps - mean)

        return Entry(key, mean.toInt(), samples, settingVar = m2 / samples)
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
