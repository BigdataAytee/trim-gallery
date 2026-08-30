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
    private val minimumPauseMs: Long = MINIMUM_PAUSE_MS,
) {
    init {
        require(resumeBelow < pauseAbove) { "resume must be cooler than pause, or this flaps" }
        require(minimumPauseMs >= 0) { "a pause cannot be shorter than no time at all" }
    }

    var isPaused: Boolean = false
        private set

    /** How many times the pass has stood down for heat, for `RunSession.thermal_pauses`. */
    var pauseCount: Int = 0
        private set

    /** When the current pause began, for the floor below. */
    private var pausedAtMs: Long = 0

    /**
     * Feeds one reading in.
     *
     * @return true while work must stay stood down. Between the two thresholds the
     *   previous answer is kept: a phone cooling from 0.75 keeps working only once it
     *   reaches 0.5, and one heating from 0.45 keeps working until it passes 0.7.
     *
     * @param nowMs the clock, for the minimum pause duration. Passed in rather than read,
     *   because the guards already have one and a gate that read its own would be a gate
     *   no test could wind forward.
     */
    fun update(headroom: Float, nowMs: Long): Boolean {
        // A device with no thermal sensing reports NaN. Treat that as "no information",
        // which means carrying on: refusing to work on every such phone would be a worse
        // failure than trusting a device that has never reported being hot.
        if (headroom.isNaN()) return isPaused

        when {
            headroom > pauseAbove -> {
                if (!isPaused) {
                    pauseCount += 1
                    pausedAtMs = nowMs
                }
                isPaused = true
            }
            headroom < resumeBelow -> if (settled(nowMs)) isPaused = false
            // Between the thresholds: hold whatever was decided last.
        }
        return isPaused
    }

    /**
     * Whether the current pause has lasted long enough to be worth ending.
     *
     * The two thresholds damp a *continuous* reading, which is what Android supplies. iOS
     * supplies four discrete states with nothing between the thresholds
     * (`ThermalState`), so the hysteresis has nothing to bite on there: an OS signal
     * oscillating between fair and serious produced a pause and a resume per oscillation.
     *
     * A floor in *time* damps both, because it does not care what shape the reading is.
     * Once the pass has stood down it stays down for at least [minimumPauseMs], and a phone
     * that is genuinely cooling loses at most that minute — while a phone whose sensor is
     * flapping stops costing the user a "paused for heat 400×" line in their History.
     *
     * Deliberately not applied to *entering* a pause: heat is a reason to stop immediately
     * and always. The floor only ever delays resumption, never protection.
     */
    private fun settled(nowMs: Long): Boolean = !isPaused || nowMs - pausedAtMs >= minimumPauseMs

    fun reset() {
        isPaused = false
        pauseCount = 0
        pausedAtMs = 0
    }

    companion object {
        const val PAUSE_ABOVE = 0.7f
        const val RESUME_BELOW = 0.5f

        /**
         * The shortest a thermal pause may last, once begun.
         *
         * A minute: long enough that a sensor oscillating around a threshold produces one
         * pause rather than a dozen, and short enough that a phone which has genuinely
         * cooled loses only twelve poll intervals of work. Configurable because the right
         * number is a device question (FIELD_TEST.md), not one to settle from a desk.
         */
        const val MINIMUM_PAUSE_MS = 60_000L

        /** BUILD.md § 11: polled every 5 s, forecasting 30 s ahead. */
        const val POLL_INTERVAL_MS = 5_000L
        const val FORECAST_SECONDS = 30
    }
}
