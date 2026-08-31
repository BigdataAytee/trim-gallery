package app.trimgallery.core.domain.field

import kotlin.math.ceil

/**
 * Fitting the XPSNR threshold that corresponds to a VMAF target (milestone 13).
 *
 * PROJECT.md has carried this as an open question since milestone 2, with the method
 * established and the answer deferred:
 *
 * > To answer the question for real, run it on device against the milestone 1 encoder over
 * > a set of real clips spanning resolution and content, and fit the threshold per
 * > (resolution, codec) bucket — the same key the predictor table already uses.
 *
 * This is the fitting. It cannot produce the number here — that needs the devices — but the
 * arithmetic that turns a sweep into a threshold is the part that can be got wrong quietly,
 * so it is written and tested against the one real sweep that exists, the milestone 2 table
 * in `shared/native/calibration/README.md`.
 *
 * The search uses XPSNR because VMAF is ten to twenty times more expensive (PROJECT.md), and
 * that trade only works if the threshold is right. Too low and every file fails
 * verification, gets re-encoded twice and is skipped; too high and the app leaves space on
 * the table on every file in the library.
 */
object ThresholdFit {

    /** One measured pair from a sweep. */
    data class Point(val xpsnr: Double, val vmaf: Double)

    /** Enough of a ladder that a fit means something. */
    const val MIN_POINTS = 4

    sealed interface Fit {
        data class Found(val xpsnr: Double, val fromPoints: Int) : Fit

        /** Why no threshold could be fitted. Each of these is a measurement to redo. */
        data class Unfittable(val reason: Reason) : Fit
    }

    enum class Reason {
        TOO_FEW_POINTS,

        /**
         * VMAF did not rise with XPSNR across the sweep.
         *
         * Real sweeps are monotone — both metrics measure the same thing badly and well —
         * so a sweep that is not means the measurement is broken, not that the codec is
         * strange. Fitting a line through it would bake the noise into a threshold that
         * then governs the whole library.
         */
        NOT_MONOTONE,

        /**
         * The target falls outside what was measured.
         *
         * Extrapolating past the end of a sweep is how a threshold nobody checked ends up
         * governing every encode. The answer is to measure further, not to project.
         */
        OUT_OF_RANGE,
    }

    /**
     * The XPSNR value at which [targetVmaf] is reached.
     *
     * Linear interpolation between the two measured points either side. The relationship is
     * not linear in general, but over the gap between two adjacent rungs of a CRF ladder it
     * is close enough that the error is smaller than the spread between clips — and a
     * higher-order fit through eight noisy points invents confidence the data has not got.
     */
    fun fit(points: List<Point>, targetVmaf: Double): Fit {
        if (points.size < MIN_POINTS) return Fit.Unfittable(Reason.TOO_FEW_POINTS)

        val sorted = points.sortedBy { it.xpsnr }
        if (!isMonotone(sorted)) return Fit.Unfittable(Reason.NOT_MONOTONE)

        if (targetVmaf < sorted.first().vmaf || targetVmaf > sorted.last().vmaf) {
            return Fit.Unfittable(Reason.OUT_OF_RANGE)
        }

        for (i in 0 until sorted.size - 1) {
            val low = sorted[i]
            val high = sorted[i + 1]
            if (targetVmaf in low.vmaf..high.vmaf) {
                val span = high.vmaf - low.vmaf
                val weight = if (span == 0.0) 0.0 else (targetVmaf - low.vmaf) / span
                return Fit.Found(low.xpsnr + weight * (high.xpsnr - low.xpsnr), points.size)
            }
        }
        return Fit.Unfittable(Reason.OUT_OF_RANGE)
    }

    /** Whether VMAF rises with XPSNR across the whole sweep, which a real one does. */
    fun isMonotone(points: List<Point>): Boolean {
        val sorted = points.sortedBy { it.xpsnr }
        return sorted.zipWithNext().all { (a, b) -> b.vmaf >= a.vmaf }
    }

    /**
     * The value to actually ship, rounded away from the risk.
     *
     * Up, always. A threshold a tenth of a decibel too high costs a sliver of space on each
     * file; a tenth too low costs quality on files that then pass verification anyway,
     * because the verifier samples three windows and the search targeted the mean. This app
     * sells "you will not see the difference", so the rounding goes where being wrong is
     * cheap.
     */
    fun conservative(xpsnr: Double, decimals: Int = 1): Double {
        var scale = 1.0
        repeat(decimals) { scale *= 10 }
        return ceil(xpsnr * scale) / scale
    }

    /**
     * Fits every bucket of a device's sweeps at once.
     *
     * Buckets that cannot be fitted are absent from the result rather than filled with a
     * neighbour's number: a resolution nobody measured has no threshold, and borrowing one
     * would hide exactly the gap the field test is meant to find.
     */
    fun <B> fitAll(sweeps: Map<B, List<Point>>, targetVmaf: Double): Map<B, Double> =
        sweeps.mapNotNull { (bucket, points) ->
            (fit(points, targetVmaf) as? Fit.Found)?.let { bucket to conservative(it.xpsnr) }
        }.toMap()
}
