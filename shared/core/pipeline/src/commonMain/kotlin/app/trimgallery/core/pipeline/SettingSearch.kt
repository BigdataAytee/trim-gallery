package app.trimgallery.core.pipeline

import kotlin.math.abs

/**
 * Finds the most aggressive encoder setting that still looks good enough.
 *
 * BUILD.md § 5: *"Binary search on bitrate (VBR) … Keep the most aggressive setting
 * scoring above the XPSNR threshold calibrated to VMAF 95. Early exit: if the first probe
 * is far above threshold, jump to the low bound. Typical: 1–2 probes with prediction, 3–4
 * without."*
 *
 * The search is the expensive part of the night — each probe is an encode plus a metric —
 * so the probe budget is a hard cap rather than a target, and every rule here exists to
 * spend fewer of them.
 *
 * It assumes quality rises with bitrate. That is not merely plausible: milestone 2's
 * metric tests assert monotonicity, because a search over a non-monotone score cannot
 * converge on anything meaningful.
 */
class SettingSearch(private val config: Config = Config()) {

    data class Config(
        /**
         * Hard cap. BUILD.md expects 3–4 without a prediction; the cap is what stops a
         * pathological file eating a whole night's budget on its own.
         */
        val maxProbes: Int = 4,

        /**
         * "Far above threshold", in dB.
         *
         * XPSNR is logarithmic, so a fixed dB margin means the same perceptual headroom at
         * every quality level. Three points clear of the threshold says the file is far
         * easier than the bracket assumed and the low bound is worth trying directly.
         */
        val earlyExitMarginDb: Double = 3.0,

        /**
         * Stop bisecting once the bracket is this close, as a fraction of its top.
         *
         * Twelve per cent, not less. A confident prediction hands the search a bracket
         * about 36% wide, and a tighter convergence spends a third probe inside it to win
         * roughly 4% of bitrate — a bad trade when the metric, not the encoder, is the
         * bottleneck (PROJECT.md § Speed), and one that would break BUILD.md's "1-2 probes
         * with prediction".
         */
        val convergenceRatio: Double = 0.12,
    )

    /** The range to search, and where to start inside it. */
    data class Bounds(val lowBps: Int, val highBps: Int, val startBps: Int) {
        init {
            require(lowBps > 0 && highBps >= lowBps) { "invalid bounds: $lowBps..$highBps" }
        }

        val midBps: Int get() = (lowBps + highBps) / 2

        fun clamp(bps: Int): Int = bps.coerceIn(lowBps, highBps)
    }

    /** One probe: what was tried and what it scored. */
    data class Probe(val bitrateBps: Int, val score: Double)

    sealed interface Outcome {
        /** The most aggressive setting that cleared the threshold. */
        data class Found(val bitrateBps: Int, val score: Double, val probes: List<Probe>) : Outcome

        /**
         * Nothing in the bracket cleared the threshold, including its top.
         *
         * The caller skips the file rather than shipping a worse one — re-encoding to
         * something the user can see is worse is the one outcome the app must never
         * produce.
         */
        data class NotReachable(val probes: List<Probe>, val bestScore: Double) : Outcome
    }

    /** Encodes the cached probe window at one setting and scores it. */
    fun interface Scorer {
        suspend fun score(bitrateBps: Int): Double
    }

    /**
     * @param threshold the XPSNR value calibrated to VMAF 95 for this bucket
     *   (`shared/native/calibration`).
     */
    suspend fun search(bounds: Bounds, threshold: Double, scorer: Scorer): Outcome {
        val probes = mutableListOf<Probe>()

        suspend fun probe(bps: Int): Probe {
            val clamped = bounds.clamp(bps)
            // Never pay for the same encode twice; a bisection can land on a bitrate the
            // early-exit path already tried.
            probes.firstOrNull { it.bitrateBps == clamped }?.let { return it }
            val result = Probe(clamped, scorer.score(clamped))
            probes += result
            return result
        }

        val first = probe(bounds.startBps)

        // Early exit: a file this far clear of the threshold is easier than the bracket
        // assumed, so the low bound is worth trying outright rather than bisecting down
        // to it one probe at a time.
        if (first.score >= threshold + config.earlyExitMarginDb &&
            first.bitrateBps > bounds.lowBps &&
            probes.size < config.maxProbes
        ) {
            val low = probe(bounds.lowBps)
            if (low.score >= threshold) {
                return Outcome.Found(low.bitrateBps, low.score, probes)
            }
            // The low bound is too far; the answer is between it and the first probe.
            return bisect(threshold, low.bitrateBps, first.bitrateBps, first, probes, ::probe)
        }

        return if (first.score >= threshold) {
            // It passes, so something cheaper might too.
            bisect(threshold, bounds.lowBps, first.bitrateBps, first, probes, ::probe)
        } else {
            // It fails, so the answer is above it — if it exists at all.
            bisectUpward(threshold, first.bitrateBps, bounds.highBps, probes, ::probe)
        }
    }

    /**
     * Narrows `(low, high]` where `high` is known to pass, looking for something cheaper
     * that still does.
     */
    private suspend fun bisect(
        threshold: Double,
        lowBps: Int,
        highBps: Int,
        best: Probe,
        probes: MutableList<Probe>,
        probe: suspend (Int) -> Probe,
    ): Outcome {
        var low = lowBps
        var high = highBps
        var winner = best

        while (probes.size < config.maxProbes && !converged(low, high)) {
            val candidate = (low + high) / 2
            if (candidate <= low || candidate >= high) break
            val result = probe(candidate)
            if (result.score >= threshold) {
                winner = result
                high = result.bitrateBps
            } else {
                low = result.bitrateBps
            }
        }
        return Outcome.Found(winner.bitrateBps, winner.score, probes.sortedBy { it.bitrateBps })
    }

    /** Searches `(low, high]` when nothing has passed yet. */
    private suspend fun bisectUpward(
        threshold: Double,
        lowBps: Int,
        highBps: Int,
        probes: MutableList<Probe>,
        probe: suspend (Int) -> Probe,
    ): Outcome {
        var low = lowBps
        var high = highBps
        var winner: Probe? = null

        // The top of the bracket is the only setting that can rescue the file, so try it
        // before spending probes bisecting a range that may have no answer at all.
        if (probes.size < config.maxProbes && high > low) {
            val top = probe(high)
            if (top.score >= threshold) {
                winner = top
            } else {
                return Outcome.NotReachable(
                    probes.sortedBy { it.bitrateBps },
                    probes.maxOf { it.score },
                )
            }
        }

        while (winner != null && probes.size < config.maxProbes && !converged(low, high)) {
            val candidate = (low + high) / 2
            if (candidate <= low || candidate >= high) break
            val result = probe(candidate)
            if (result.score >= threshold) {
                winner = result
                high = result.bitrateBps
            } else {
                low = result.bitrateBps
            }
        }

        return winner
            ?.let { Outcome.Found(it.bitrateBps, it.score, probes.sortedBy { p -> p.bitrateBps }) }
            ?: Outcome.NotReachable(probes.sortedBy { it.bitrateBps }, probes.maxOf { it.score })
    }

    private fun converged(low: Int, high: Int): Boolean =
        high <= low || abs(high - low).toDouble() / high <= config.convergenceRatio
}
