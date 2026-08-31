package app.trimgallery.core.domain.space

import app.trimgallery.core.model.MediaItem

/**
 * "About 3 Wh" — what the night cost (BUILD.md § 11, § 9).
 *
 * > Energy reporting: bench-measured mWh per minute of 4K per chip family, stored in a
 * > table; displayed as "about X Wh" in Space screen. `BATTERY_PROPERTY_ENERGY_COUNTER`
 * > used for calibration only.
 *
 * This number exists because BUILD.md § 3's risk table names battery complaints as the
 * thing most likely to sink the app, and the honest answer to "what did this cost me?" is
 * the best defence against a suspicion the user cannot otherwise check. So it is shown
 * hedged — *about* — and it is deliberately rounded coarsely: a figure quoted to two
 * decimal places invites the user to believe a precision that a bench table across chip
 * families cannot possibly have.
 *
 * For scale: a phone battery holds roughly 15–20 Wh, so a full night's cap of an hour's
 * encoding is a few per cent of one charge. Saying so is the point.
 */
object EnergyEstimate {

    /**
     * Milliwatt-hours per minute of 4K30 encoding, by chip family.
     *
     * Bench-measured values go here as milestone 13's field test produces them; these are
     * the starting estimates, and they are deliberately on the high side. Over-reporting
     * energy makes the app look worse than it is, which is a bearable error; under-reporting
     * it is a claim the user's own battery graph can contradict, and once they catch the app
     * being optimistic about battery they have no reason to believe the space figures either.
     */
    val BENCH_MWH_PER_MINUTE_4K = mapOf(
        "flagship" to 55.0, // recent large-core SoCs with a dedicated encoder block
        "midrange" to 85.0, // the target market of BUILD.md § 3
        "entry" to 130.0, // older or cut-down encoder blocks, longer per file
    )

    /** Used when the chip family is not in the table. The pessimistic row, on purpose. */
    const val UNKNOWN_FAMILY = "entry"

    /** 3840 × 2160, the resolution the bench numbers are quoted at. */
    private const val REFERENCE_PIXELS = 3840L * 2160L

    private const val MS_PER_MINUTE = 60_000.0
    private const val MWH_PER_WH = 1000.0

    /**
     * Energy for one encode, in watt-hours.
     *
     * Scales with pixels and with duration, because both are what the encoder actually
     * does work proportional to. Frame rate rides along inside duration: a 60 fps clip of a
     * given length has twice the frames of a 30 fps one and takes about twice as long, and
     * [elapsedMs] is measured rather than assumed.
     *
     * @param elapsedMs how long the encode really took. Passed in rather than derived from
     *   the clip's duration: an encode that ran at four times real time cost a quarter of
     *   what its length suggests, and the whole point of this figure is to be checkable.
     */
    fun forEncode(item: MediaItem, elapsedMs: Long, chipFamily: String?): Double {
        if (elapsedMs <= 0) return 0.0
        val perMinute = BENCH_MWH_PER_MINUTE_4K[chipFamily]
            ?: BENCH_MWH_PER_MINUTE_4K.getValue(UNKNOWN_FAMILY)

        val pixelRatio = if (item.pixels > 0) item.pixels.toDouble() / REFERENCE_PIXELS else 1.0
        val minutes = elapsedMs / MS_PER_MINUTE

        return perMinute * minutes * pixelRatio / MWH_PER_WH
    }

    /**
     * "about 3 Wh", or "under 1 Wh" for anything too small to state.
     *
     * Whole numbers above one watt-hour. DESIGN_SYSTEM.md § Copy tone asks for numbers over
     * adjectives and short, calm, concrete lines; "about 2.73 Wh" is none of those, and it
     * claims a precision the bench table does not have.
     */
    fun describe(wh: Double): String = when {
        wh <= 0.0 -> "no energy used"
        wh < 1.0 -> "under 1 Wh"
        else -> "about ${wh.roundToWhole()} Wh"
    }

    /**
     * The same figure as a share of a typical phone battery, for the line beside it.
     *
     * Percentages are what people actually reason about; watt-hours are not. Null below one
     * per cent, because "0% of a charge" reads as a rounding error rather than as good news.
     */
    fun asBatteryPercent(wh: Double, batteryWh: Double = TYPICAL_BATTERY_WH): Int? {
        if (wh <= 0 || batteryWh <= 0) return null
        val percent = (wh / batteryWh * 100).roundToWhole()
        return percent.takeIf { it >= 1 }
    }

    /** A mid-range phone, 4000 mAh at 3.85 V. */
    const val TYPICAL_BATTERY_WH = 15.4

    private fun Double.roundToWhole(): Int = (this + 0.5).toInt()
}
