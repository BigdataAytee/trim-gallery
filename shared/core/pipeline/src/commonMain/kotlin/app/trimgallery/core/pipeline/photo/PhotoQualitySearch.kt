package app.trimgallery.core.pipeline.photo

import app.trimgallery.core.model.QualityTarget

/**
 * The most aggressive JPEG/HEIC quality that still clears the SSIMULACRA2 gate.
 *
 * BUILD.md § 5: *"Gate with SSIMULACRA2 ≥ 85–90. Full binary search per file (milliseconds
 * each)."* Photos differ from video in exactly the way that matters here: a probe costs
 * milliseconds rather than a decode plus an encode plus VMAF, so this can afford a real
 * binary search over the whole quality range instead of the four-probe budget and early-exit
 * heuristics `SettingSearch` needs.
 *
 * It assumes the score rises with quality. That is true of SSIMULACRA2 against a fixed
 * source, and the search is only meaningful if it holds — a non-monotone score cannot be
 * bisected.
 */
class PhotoQualitySearch(private val config: Config = Config()) {

    data class Config(
        /** JPEG quality is 1–100; nothing below this is worth looking at. */
        val minQuality: Int = 40,
        val maxQuality: Int = 95,

        /**
         * Where the first probe lands.
         *
         * 82 rather than the midpoint: on a phone JPEG the answer is nearly always in the
         * high seventies to high eighties, so starting there usually leaves one or two
         * bisections rather than four.
         */
        val startQuality: Int = 82,

        /**
         * Stop once the bracket is this narrow.
         *
         * Two quality points. Below that the file-size difference is under a per cent and
         * the score difference is inside SSIMULACRA2's own noise, so a further probe buys
         * nothing measurable.
         */
        val convergence: Int = 2,

        /** A hard cap, so a pathological image cannot spin. */
        val maxProbes: Int = 8,
    ) {
        init {
            require(minQuality in 1..100 && maxQuality in 1..100) { "quality is 1..100" }
            require(minQuality < maxQuality) { "empty quality range" }
            require(startQuality in minQuality..maxQuality) { "start outside the range" }
        }
    }

    /** One probe: what was tried and what it scored. */
    data class Probe(val quality: Int, val score: Double)

    sealed interface Outcome {
        /** The lowest quality that cleared the gate. */
        data class Found(val quality: Int, val score: Double, val probes: List<Probe>) : Outcome

        /**
         * Even the top of the range could not clear the gate.
         *
         * The file is left alone. BUILD.md rule 3 and the safe-replace skill agree: a file
         * that fails verification is never replaced, and "probably fine" is not a verdict.
         */
        data class NotReachable(val probes: List<Probe>, val bestScore: Double) : Outcome
    }

    /** Encodes at one quality and scores the result against the original. */
    fun interface Scorer {
        suspend fun scoreAt(quality: Int): Double
    }

    /**
     * BUILD.md § 9's quality targets, on SSIMULACRA2's scale rather than VMAF's.
     *
     * BUILD.md § 5 gives the photo gate as a range, "≥ 85–90", and § 9 gives the user two
     * settings; mapping Standard to the strict end and Compact to the loose one is what
     * makes the two documents one behaviour. Recorded in PROJECT.md.
     */
    fun targetFor(quality: QualityTarget): Double = when (quality) {
        QualityTarget.STANDARD -> STANDARD_SSIM2
        QualityTarget.COMPACT -> COMPACT_SSIM2
    }

    suspend fun search(target: Double, scorer: Scorer): Outcome {
        val probes = mutableListOf<Probe>()

        var low = config.minQuality // known-or-assumed failing side
        var high = config.maxQuality // known-or-assumed passing side
        var best: Probe? = null
        var next = config.startQuality

        while (probes.size < config.maxProbes) {
            val score = scorer.scoreAt(next)
            probes += Probe(next, score)

            if (score >= target) {
                // Good enough: remember it and try harder.
                if (best == null || next < best.quality) best = Probe(next, score)
                high = next
            } else {
                low = next
            }

            // The bracket is (low, high]: everything at or above `high` passes, everything
            // at or below `low` fails.
            if (high - low <= config.convergence) break
            val candidate = (low + high) / 2
            if (candidate == next || candidate <= low || candidate >= high) break
            next = candidate
        }

        // The top of the range was never actually tried if the first probe passed at a
        // lower quality — but if nothing passed, it has to be, before giving up.
        if (best == null && probes.none { it.quality == config.maxQuality }) {
            val score = scorer.scoreAt(config.maxQuality)
            probes += Probe(config.maxQuality, score)
            if (score >= target) best = Probe(config.maxQuality, score)
        }

        val found = best
        return if (found != null) {
            Outcome.Found(found.quality, found.score, probes)
        } else {
            Outcome.NotReachable(probes, probes.maxOf { it.score })
        }
    }

    companion object {
        /** SSIMULACRA2 ≈ 90 is "very high quality"; the strict end of BUILD.md's range. */
        const val STANDARD_SSIM2 = 90.0

        /** The loose end, for the Compact setting that BUILD.md § 9 says must warn. */
        const val COMPACT_SSIM2 = 85.0
    }
}
