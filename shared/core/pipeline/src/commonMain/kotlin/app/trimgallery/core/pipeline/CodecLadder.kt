package app.trimgallery.core.pipeline

import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.QualityTarget
import app.trimgallery.engine.VideoCodec

/**
 * Where the search starts, per output codec (BUILD.md § 5, milestone 12).
 *
 * The bracket the binary search opens with matters more than it looks: the probe budget is
 * three or four encodes, and a bracket centred in the wrong place spends most of it walking
 * rather than converging. Until milestone 12 the fallback bracket was the caller's to
 * invent, which was survivable while there was one output codec and stops being so with
 * two — **AV1 reaches the same quality at roughly two thirds of HEVC's bitrate**, so a
 * bracket built for HEVC starts an AV1 search a third too high and converges downwards for
 * every probe it has.
 *
 * All of these are starting points, not answers. The predictor replaces them with measured
 * ones after about twenty files in a family (`Predictor.bounds`), and the field test in
 * milestone 13 is where the constants themselves get checked against real devices.
 */
object CodecLadder {

    /**
     * AV1's bitrate for the same quality, as a fraction of HEVC's.
     *
     * Two thirds is the conservative end of what the codec comparisons report for hardware
     * encoders — the software gap is wider, and phone AV1 encoders are not where the
     * software ones are. Conservative in the direction that costs a probe rather than
     * quality: starting slightly high converges downwards, which the search does well,
     * while starting below the threshold makes the first probe useless.
     */
    const val AV1_BITRATE_RATIO = 0.67

    /** The bracket, as fractions of the source's own bitrate. */
    const val LOW_FRACTION = 0.20
    const val HIGH_FRACTION = 0.85

    /**
     * Where the first probe lands for HEVC, as a fraction of the source's bitrate.
     *
     * PROJECT.md: *"Hardware HEVC is less efficient than x265; expect 30–45% on phone
     * H.264, not 50%."* 0.62 is the same conservative figure `Triager` uses to order the
     * queue, and using one number for both means the estimate the user is shown and the
     * bitrate the search opens at cannot drift apart.
     */
    const val START_FRACTION = 0.62

    /** A floor, so a mangled or missing source bitrate cannot produce a nonsense bracket. */
    const val MIN_BPS = 200_000

    /**
     * The XPSNR value calibrated to the quality target, per codec.
     *
     * Both numbers are read off the milestone 2 sweep in `shared/native/calibration/`,
     * which measured XPSNR against VMAF over a CRF ladder using this app's own metric code:
     * VMAF 95 interpolates to XPSNR y ≈ 39.8, and VMAF 90.035 was measured directly at
     * XPSNR y 36.0.
     *
     * That README is explicit that these are **not the numbers to ship** — software x265
     * rather than a phone's hardware encoder, one clip, 640×360 rather than the 1080p the
     * verifier works at. They are here because the pipeline needs a threshold from
     * somewhere and a measured provisional one beats an invented one; the verifier is what
     * makes a wrong threshold cost a re-encode rather than quality.
     *
     * **AV1 returns the same values as HEVC, and that is a placeholder, not a finding.**
     * XPSNR is a proxy for VMAF and the mapping depends on what the artefacts look like;
     * AV1's and HEVC's do not look alike, so there is no reason one calibration serves
     * both. Measuring it needs an AV1 encoder and the device fleet from milestone 13. The
     * table is keyed by codec now so the measurement has somewhere to land that is not a
     * search-and-replace through the pipeline. PROJECT.md records it as open.
     */
    fun xpsnrThreshold(codec: VideoCodec, target: QualityTarget): Double = when (codec) {
        VideoCodec.HEVC, VideoCodec.AV1 -> when (target) {
            QualityTarget.STANDARD -> STANDARD_XPSNR
            QualityTarget.COMPACT -> COMPACT_XPSNR
        }
    }

    /**
     * The bracket to search when the predictor has nothing to say.
     *
     * Derived from the source's own bitrate rather than from its resolution, because the
     * source bitrate is the one number that already accounts for how busy the footage is: a
     * static talking head and a handheld shot of a forest are the same 4K30 and want very
     * different answers.
     */
    fun fallbackBounds(item: MediaItem, codec: VideoCodec): SettingSearch.Bounds {
        val source = (item.bitrate ?: 0L).toInt().coerceAtLeast(MIN_BPS * 2)
        val ratio = if (codec == VideoCodec.AV1) AV1_BITRATE_RATIO else 1.0

        val low = (source * LOW_FRACTION * ratio).toInt().coerceAtLeast(MIN_BPS)
        val high = (source * HIGH_FRACTION * ratio).toInt().coerceAtLeast(low + 1)
        val start = (source * START_FRACTION * ratio).toInt().coerceIn(low, high)
        return SettingSearch.Bounds(lowBps = low, highBps = high, startBps = start)
    }

    /**
     * What the file is expected to come out at, for the queue's ordering estimate.
     *
     * The same arithmetic as [fallbackBounds]'s starting point, exposed separately so the
     * number the user is shown in "About 19 GB more possible" and the number the search
     * opens at are the same number.
     */
    fun expectedFactor(codec: VideoCodec): Double =
        if (codec == VideoCodec.AV1) START_FRACTION * AV1_BITRATE_RATIO else START_FRACTION

    /**
     * BUILD.md § 9: Standard targets VMAF 95, Compact 90.
     *
     * Both from the milestone 2 calibration sweep (`shared/native/calibration/README.md`).
     */
    private const val STANDARD_XPSNR = 39.8
    private const val COMPACT_XPSNR = 36.0
}
