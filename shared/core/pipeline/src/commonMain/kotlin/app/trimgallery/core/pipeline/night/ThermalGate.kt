package app.trimgallery.core.pipeline.night

/**
 * The thermal pause, with the hysteresis that stops it flapping.
 *
 * BUILD.md § 11: *"Poll `getThermalHeadroom(30)` every 5 s; pause above 0.7, resume below
 * 0.5."* Two thresholds, not one — and the gap between them is the whole point. A single
 * threshold on a value that hovers around it starts and stops the encoder several times a
 * second, which heats the phone *more* than running steadily would and produces a History
 * screen reading "paused for heat 400×".
 *
 * **The reading gets hotter as it rises.** `PowerManager.getThermalHeadroom` returns 0.0
 * for no thermal load and 1.0 at the throttling threshold, so "headroom above 0.7" means
 * *close to throttling*, not *plenty of room left*. The name reads the other way round and
 * this comment exists because someone will eventually try to "fix" the comparison.
 */
class ThermalGate(
    private val pauseAbove: Float = PAUSE_ABOVE,
    private val resumeBelow: Float = RESUME_BELOW,
) {
    init {
        require(resumeBelow < pauseAbove) { "resume must be cooler than pause, or this flaps" }
    }

    var isPaused: Boolean = false
        private set

    /** How many times the pass has stood down for heat, for `RunSession.thermal_pauses`. */
    var pauseCount: Int = 0
        private set

    /**
     * Feeds one reading in.
     *
     * @return true while work must stay stood down. Between the two thresholds the
     *   previous answer is kept: a phone cooling from 0.75 keeps working only once it
     *   reaches 0.5, and one heating from 0.45 keeps working until it passes 0.7.
     */
    fun update(headroom: Float): Boolean {
        // A device with no thermal sensing reports NaN. Treat that as "no information",
        // which means carrying on: refusing to work on every such phone would be a worse
        // failure than trusting a device that has never reported being hot.
        if (headroom.isNaN()) return isPaused

        when {
            headroom > pauseAbove -> {
                if (!isPaused) pauseCount += 1
                isPaused = true
            }
            headroom < resumeBelow -> isPaused = false
            // Between the thresholds: hold whatever was decided last.
        }
        return isPaused
    }

    fun reset() {
        isPaused = false
        pauseCount = 0
    }

    companion object {
        const val PAUSE_ABOVE = 0.7f
        const val RESUME_BELOW = 0.5f

        /** BUILD.md § 11: polled every 5 s, forecasting 30 s ahead. */
        const val POLL_INTERVAL_MS = 5_000L
        const val FORECAST_SECONDS = 30
    }
}
