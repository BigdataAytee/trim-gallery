package app.trimgallery.core.domain.field

/**
 * LAUNCH.md's private-alpha gate, as something that can be evaluated rather than argued
 * about.
 *
 * > Private alpha · after milestone 7 · field test on 3+ devices: ≥ 30% median video
 * > saving, restore rate < 2%, zero thermal complaints.
 *
 * Written as code because a gate that lives in a document is a gate somebody talks their
 * way past at the end of a long week. Each criterion reports its own verdict and the
 * measured number beside it, so a failing gate says *what* failed and by how much — the
 * only form in which a "no" is useful.
 *
 * **A criterion with no data fails.** Not passes, and not "n/a": the field test exists to
 * produce the evidence, so its absence is the gate doing its job. A build that shipped
 * because nobody measured the restore rate would be the exact failure this gate is for.
 */
object AlphaGate {

    /** *"≥ 30% median video saving"*. */
    const val MIN_MEDIAN_VIDEO_SAVING = 0.30

    /** *"restore rate < 2%"*. */
    const val MAX_RESTORE_RATE = 0.02

    /**
     * *"Zero thermal complaints"*, made measurable.
     *
     * A complaint is a person, and a person cannot be counted from a log. What can is how
     * often the pass stood down for heat: BUILD.md § 11's guards are supposed to keep the
     * phone cool enough that nobody notices, and a device pausing repeatedly every night is
     * a phone that is getting hot whether or not its owner filed a complaint.
     *
     * One pause a night is the thermal gate working. Three is the pass fighting the phone.
     */
    const val MAX_THERMAL_PAUSES_PER_NIGHT = 2.0

    /** LAUNCH.md says three or more devices; fewer is not a field test. */
    const val MIN_DEVICES = 3

    /** Enough nights per device that one unusual night cannot carry the result. */
    const val MIN_NIGHTS_PER_DEVICE = 5

    data class Criterion(
        val name: String,
        val passed: Boolean,
        /** The worst value any device reported, or null when none did. */
        val measured: Double?,
        val required: String,
        /**
         * How many devices reported this at all, against how many were tested.
         *
         * Carried separately from [measured] because a criterion can fail for two quite
         * different reasons and the report has to say which. Two devices reporting 0.5%
         * restores while a third reported nothing is *not* "restore rate 0.5% — fails";
         * it is "the field test did not measure this on every device". Blaming the number
         * would send someone off to fix a build that was never the problem.
         */
        val reportedBy: Int = 0,
        val devices: Int = 0,
    ) {
        val incomplete: Boolean get() = devices > 0 && reportedBy < devices

        val explanation: String
            get() = when {
                measured == null -> "$name: not measured. Required $required."
                incomplete ->
                    "$name: not measured on every device ($reportedBy of $devices reported; " +
                        "worst ${format(measured)}). Required $required."
                passed -> "$name: ${format(measured)} — required $required."
                else -> "$name: ${format(measured)} — FAILS $required."
            }
    }

    data class Result(val criteria: List<Criterion>) {
        val passed: Boolean get() = criteria.all { it.passed }
        val failures: List<Criterion> get() = criteria.filterNot { it.passed }

        /** The whole verdict in the form a release meeting can read. */
        fun report(): String = buildString {
            appendLine(if (passed) "Alpha gate: PASSED" else "Alpha gate: NOT PASSED")
            criteria.forEach { appendLine("  ${it.explanation}") }
        }.trimEnd()
    }

    /**
     * Evaluates the gate over one summary per device.
     *
     * Per device rather than pooled, because the point of testing on three is to find the
     * one that behaves differently — a slow chip that misses the saving, or a phone that
     * throttles. Pooling would let two good devices carry a bad one, which is the failure
     * mode LAUNCH.md's *"3+ devices"* exists to catch. So the criteria are evaluated against
     * the *worst* device, not the average.
     */
    fun evaluate(perDevice: Map<String, FieldMetrics.Summary>): Result {
        val summaries = perDevice.values.toList()

        return Result(
            listOf(
                Criterion(
                    name = "Devices tested",
                    passed = perDevice.size >= MIN_DEVICES,
                    measured = perDevice.size.toDouble(),
                    required = "at least $MIN_DEVICES",
                ),
                Criterion(
                    name = "Nights per device",
                    passed = summaries.isNotEmpty() && summaries.all { it.nights >= MIN_NIGHTS_PER_DEVICE },
                    measured = summaries.minOfOrNull { it.nights.toDouble() },
                    required = "at least $MIN_NIGHTS_PER_DEVICE on every device",
                ),
                worst(
                    name = "Median video saving",
                    values = summaries.map { it.medianVideoSaving },
                    required = "at least ${percent(MIN_MEDIAN_VIDEO_SAVING)}",
                    pick = { it.minOrNull() },
                    test = { it >= MIN_MEDIAN_VIDEO_SAVING },
                ),
                worst(
                    name = "Restore rate",
                    values = summaries.map { it.restoreRate },
                    required = "below ${percent(MAX_RESTORE_RATE)}",
                    pick = { it.maxOrNull() },
                    test = { it < MAX_RESTORE_RATE },
                ),
                worst(
                    name = "Thermal pauses per night",
                    values = summaries.map { it.thermalPausesPerNight },
                    required = "at most $MAX_THERMAL_PAUSES_PER_NIGHT",
                    pick = { it.maxOrNull() },
                    test = { it <= MAX_THERMAL_PAUSES_PER_NIGHT },
                ),
            ),
        )
    }

    /**
     * One criterion, judged on the worst device that reported a number.
     *
     * A device that reported nothing fails the criterion outright rather than being skipped:
     * a field test in which one phone produced no restore data has not measured the restore
     * rate, and treating silence as a pass is how an unmeasured build ships.
     */
    private fun worst(
        name: String,
        values: List<Double?>,
        required: String,
        pick: (List<Double>) -> Double?,
        test: (Double) -> Boolean,
    ): Criterion {
        val present = values.filterNotNull()
        val complete = values.isNotEmpty() && present.size == values.size
        val measured = pick(present)
        return Criterion(
            name = name,
            passed = complete && measured != null && test(measured),
            measured = measured,
            required = required,
            reportedBy = present.size,
            devices = values.size,
        )
    }

    private fun percent(fraction: Double): String = "${(fraction * 100).toInt()}%"
}

/**
 * Enough precision for the numbers this gate actually judges.
 *
 * Three decimals rather than two, because a restore rate is a small fraction and rounding
 * 0.005 to "0" reports a passing 0.5% as zero — a number that looks like missing data in
 * the one report where the difference matters.
 */
private fun format(value: Double): String {
    val rounded = kotlin.math.round(value * 1000) / 1000.0
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
}
