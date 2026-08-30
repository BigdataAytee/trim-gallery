package app.trimgallery.core.domain.edit

import kotlin.math.round

/**
 * The light and colour sliders (BUILD.md § 9: *"light/colour sliders, a few filters"*).
 *
 * Every slider runs −1 to +1 with 0 meaning "leave it alone", rather than each having its
 * own natural units. That is a deliberate flattening: it makes "is this edit doing
 * anything?" a single check instead of eight special cases, it makes a filter a vector that
 * can be scaled by a strength, and it means a double-tap to reset is the same code
 * everywhere. The renderer maps each one onto whatever its own maths wants — a stop of
 * exposure, a percentage of saturation — and that mapping is the renderer's business.
 */
enum class Slider {
    /** Overall brightness, in stops. The one people reach for first. */
    EXPOSURE,
    CONTRAST,

    /** Pulls back a blown sky without touching the rest. */
    HIGHLIGHTS,

    /** Opens up a face in shadow. Positive lifts. */
    SHADOWS,

    /** Where black is. The difference between flat and punchy. */
    BLACK_POINT,
    SATURATION,

    /** Towards amber (+) or blue (−). */
    WARMTH,

    /** Towards magenta (+) or green (−); the second half of a white balance. */
    TINT,
    ;

    /** Sliders that move light, as opposed to colour. Grouped this way in the UI. */
    val isLight: Boolean
        get() = this == EXPOSURE ||
            this == CONTRAST ||
            this == HIGHLIGHTS ||
            this == SHADOWS ||
            this == BLACK_POINT
}

/**
 * A set of slider positions.
 *
 * Stored as a map of only the sliders that moved, so an untouched edit is an empty map and
 * costs nothing to keep, compare or persist. [get] answers 0 for anything absent, which is
 * what makes every caller able to treat "no adjustment" and "adjustment at neutral" as the
 * same thing — because to the user they are.
 */
data class Adjustments(private val values: Map<Slider, Double> = emptyMap()) {

    operator fun get(slider: Slider): Double = values[slider] ?: NEUTRAL

    /** Sets one slider, dropping it from the map when it returns to neutral. */
    fun with(slider: Slider, value: Double): Adjustments {
        val clamped = quantise(value.coerceIn(MIN, MAX))
        return Adjustments(if (clamped == NEUTRAL) values - slider else values + (slider to clamped))
    }

    fun reset(slider: Slider): Adjustments = Adjustments(values - slider)

    val isNeutral: Boolean get() = values.isEmpty()

    /** The sliders the user has actually moved, for "3 adjustments" in the UI. */
    val moved: Set<Slider> get() = values.keys

    /**
     * Scales every slider towards neutral.
     *
     * This is what a filter's strength control does, and why the sliders had to share a
     * scale: at strength 0 a filter is exactly the identity, and at 0.5 it is exactly half
     * of itself, with no per-slider curve to get wrong.
     */
    fun scaled(strength: Double): Adjustments {
        val factor = strength.coerceIn(0.0, 1.0)
        if (factor == 1.0) return this
        if (factor == 0.0) return NONE
        return Adjustments(values.mapValues { (_, v) -> quantise(v * factor) }.filterValues { it != NEUTRAL })
    }

    /**
     * Lays [other] on top of this, adding the two positions and clamping.
     *
     * Adding rather than replacing, because this is how a filter and the user's own sliders
     * combine: picking a warm filter and then pulling warmth down should land somewhere
     * between, not throw one of the two away. The clamp is why it is not associative at the
     * extremes, which is documented in a test rather than pretended away.
     */
    fun over(other: Adjustments): Adjustments {
        if (other.isNeutral) return this
        if (isNeutral) return other
        val merged = mutableMapOf<Slider, Double>()
        for (slider in values.keys + other.values.keys) {
            val sum = quantise((this[slider] + other[slider]).coerceIn(MIN, MAX))
            if (sum != NEUTRAL) merged[slider] = sum
        }
        return Adjustments(merged)
    }

    companion object {
        /**
         * The resolution every stored slider position is rounded to.
         *
         * A slider has a few hundred usable steps, so a millionth is far below anything a
         * user can express — and rounding to it is what makes this type's equality mean
         * something. Without it, a filter at 0.3 plus a slider at −0.1 lands on
         * 0.19999999999999998, two edits that should compare equal do not, and an
         * adjustment the user has cancelled by hand can fail [isNeutral] and write a file
         * for an edit that does nothing. Binary floating point has no exact 0.1; the fix is
         * to decide the resolution rather than to inherit whichever one the arithmetic
         * happens to produce.
         */
        const val RESOLUTION = 1_000_000.0

        const val MIN = -1.0
        const val MAX = 1.0
        const val NEUTRAL = 0.0

        val NONE = Adjustments()

        fun of(vararg pairs: Pair<Slider, Double>): Adjustments =
            pairs.fold(NONE) { acc, (slider, value) -> acc.with(slider, value) }

        private fun quantise(value: Double): Double = round(value * RESOLUTION) / RESOLUTION

        /**
         * The order the renderer applies them in.
         *
         * Not alphabetical and not the order they are shown. Tone before colour, because
         * saturating first and then lifting exposure amplifies the saturation into
         * clipping; and black point last within tone, because it is defined against the
         * histogram the earlier sliders produced. Fixed here so that the Compose preview
         * and the full-size render cannot disagree — a preview that does not match the
         * saved file is worse than no preview.
         */
        val PIPELINE = listOf(
            Slider.EXPOSURE,
            Slider.HIGHLIGHTS,
            Slider.SHADOWS,
            Slider.CONTRAST,
            Slider.BLACK_POINT,
            Slider.WARMTH,
            Slider.TINT,
            Slider.SATURATION,
        )
    }
}
