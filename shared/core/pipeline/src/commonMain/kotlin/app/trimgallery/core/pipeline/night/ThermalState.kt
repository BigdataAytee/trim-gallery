package app.trimgallery.core.pipeline.night

/**
 * Making iOS's four thermal states and Android's continuous headroom the same question
 * (milestone 15, the iOS port).
 *
 * `ThermalGate` decides when to stand down for heat, and it is written against a 0..1
 * reading because that is what `PowerManager.getThermalHeadroom` gives. iOS gives
 * `ProcessInfo.thermalState`, which is four named steps. The temptation is to write a second
 * gate for iOS; the result of that is two policies that drift apart, and a user who is told
 * "paused for heat" on one phone and not on the other for the same temperature.
 *
 * So the mapping lives here instead: one gate, one hysteresis, one set of thresholds, and a
 * platform that reports steps converts them at the edge.
 *
 * ARCHITECTURE.md § 6 fixes the behaviour — *"run at nominal/fair, pause at
 * serious/critical"* — and the numbers below are chosen to produce exactly that against
 * `ThermalGate`'s 0.7 / 0.5 pair.
 */
enum class ThermalState {
    NOMINAL,
    FAIR,
    SERIOUS,
    CRITICAL,
    ;

    /**
     * This state as a headroom reading.
     *
     * [FAIR] at 0.45 sits just below the resume threshold, so a phone cooling from serious
     * back to fair starts working again — which is what ARCHITECTURE.md § 6 asks for.
     *
     * **The consequence, stated because it is a real one:** iOS has no state between the two
     * thresholds, so the hysteresis does nothing there. If the OS oscillates between fair and
     * serious, every oscillation is a pause and a resume, which is the flapping the two
     * thresholds exist to prevent on Android. Whether that happens is a question for a device
     * (FIELD_TEST.md), and the fix if it does is [HELD_FAIR] — mapping fair *between* the
     * thresholds, so it means "carry on doing whatever you were doing" and only nominal
     * resumes. That trades one line of the spec for the behaviour the spec is trying to get.
     */
    val headroom: Float
        get() = when (this) {
            NOMINAL -> 0.2f
            FAIR -> 0.45f
            SERIOUS -> 0.8f
            CRITICAL -> 1.0f
        }

    companion object {
        /**
         * The alternative reading of fair: hold, rather than resume.
         *
         * Sits between `ThermalGate`'s thresholds, so a phone that has stood down for heat
         * keeps standing down until it reaches nominal. Not the default, because
         * ARCHITECTURE.md § 6 says fair runs — but here, named and tested, so that switching
         * to it after a field test is a one-line change rather than a rewrite.
         */
        const val HELD_FAIR = 0.6f

        /**
         * `ProcessInfo.thermalState` as an integer, which is how it crosses the Swift
         * boundary.
         *
         * Anything unrecognised becomes [NOMINAL] rather than throwing: a new state in a
         * future OS release must not stop the night pass on every phone that has it, and
         * `ThermalGate` will still pause the moment a reading it does understand says so.
         */
        fun ofRawValue(value: Int): ThermalState = entries.getOrElse(value) { NOMINAL }
    }
}
