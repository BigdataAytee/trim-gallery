package app.trimgallery.core.pipeline

import app.trimgallery.core.model.MediaItem
import app.trimgallery.engine.BitrateMode
import app.trimgallery.engine.ProbeEncoder
import app.trimgallery.engine.QualityScorer
import app.trimgallery.engine.Setting
import app.trimgallery.engine.YuvSource
import app.trimgallery.engine.YuvWindow

/**
 * The search phase of the night pass (BUILD.md § 5, milestone 3).
 *
 * Ties the predictor, the window plan and the binary search to the platform engines
 * without knowing anything about either platform: everything here is written against
 * `shared/engine-api` and tested with fakes (ARCHITECTURE.md § 2.7).
 *
 * The one rule that makes the whole thing affordable: **the source window is decoded
 * once**. Every candidate setting re-encodes that cached buffer, so a four-probe search
 * pays for one decode, not four (PROJECT.md § Speed).
 */
class ProbeAndSearch(
    private val yuvSource: YuvSource,
    private val probeEncoder: ProbeEncoder,
    private val scorer: QualityScorer,
    private val search: SettingSearch = SettingSearch(),
) {

    /** What the search decided, plus what it cost. */
    data class Result(
        val outcome: SettingSearch.Outcome,
        val windowsDecoded: Int,
        val probes: Int,
    )

    /**
     * @param threshold the XPSNR value calibrated to VMAF 95 for this bucket.
     * @param prediction the predictor entry for this file's family, if any.
     *
     * The search runs on bitrate throughout. BUILD.md § 5 allows `BITRATE_MODE_CQ` where
     * the encoder advertises it, but that is a choice for the final encode: a predictor
     * table holding a mixture of CQ levels and bitrates would not be comparable, and CQ
     * is not universally supported (PROJECT.md § Codec facts).
     */
    suspend fun run(
        item: MediaItem,
        threshold: Double,
        fallback: SettingSearch.Bounds,
        prediction: Predictor.Entry?,
    ): Result {
        val duration = item.duration ?: return Result(
            SettingSearch.Outcome.NotReachable(emptyList(), Double.NaN),
            windowsDecoded = 0,
            probes = 0,
        )

        val windows = WindowPlan.probeWindows(duration)
        // Decoded once, reused by every probe. This is the difference between a search
        // that costs one decode and one that costs four.
        val references = windows.map { window ->
            yuvSource.decodeWindow(
                ref = item.platformRef,
                start = window.startMs,
                len = window.lengthMs,
                width = scoringWidth(item),
            )
        }

        val bounds = Predictor.bounds(prediction, fallback)

        val outcome = search.search(bounds, threshold) { bitrateBps ->
            scoreAt(references, Setting(bitrate = bitrateBps, mode = BitrateMode.VBR))
        }

        return Result(
            outcome = outcome,
            windowsDecoded = references.size,
            probes = when (outcome) {
                is SettingSearch.Outcome.Found -> outcome.probes.size
                is SettingSearch.Outcome.NotReachable -> outcome.probes.size
            },
        )
    }

    /**
     * Mean XPSNR across the probe windows.
     *
     * The mean rather than the minimum: a single hard window would otherwise set the
     * bitrate for the whole file, and the verifier — which does look at three separate
     * windows and can reject — is the place to catch a file that holds up badly in one
     * part.
     */
    private suspend fun scoreAt(references: List<YuvWindow>, setting: Setting): Double {
        var total = 0.0
        for (reference in references) {
            val encoded = probeEncoder.encodeWindow(reference, setting)
            total += scorer.xpsnr(reference, encoded)
        }
        return total / references.size
    }

    /**
     * Width that puts the window at [WindowPlan.SCORING_HEIGHT], preserving aspect.
     *
     * Rounded to an even number: chroma planes are half-width in 4:2:0, and an odd width
     * would leave the last column without one.
     */
    private fun scoringWidth(item: MediaItem): Int {
        if (item.height <= 0 || item.width <= 0) return WindowPlan.SCORING_HEIGHT
        if (item.height <= WindowPlan.SCORING_HEIGHT) return item.width and 1.inv()
        val scaled = item.width * WindowPlan.SCORING_HEIGHT / item.height
        return (scaled and 1.inv()).coerceAtLeast(2)
    }
}
