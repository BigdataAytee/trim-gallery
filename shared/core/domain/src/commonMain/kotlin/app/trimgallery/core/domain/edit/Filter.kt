package app.trimgallery.core.domain.edit

/**
 * The "few filters" of BUILD.md § 9.
 *
 * **A filter here is a set of slider positions, not a look-up table.** That is the whole
 * design decision, and it buys three things:
 *
 * - Picking a filter and then adjusting still works, because both live on the same eight
 *   sliders. In an editor where filters are opaque, the sliders and the filter fight.
 * - The strength control is exact: strength 0 is the identity, 0.5 is half, by arithmetic
 *   rather than by a second interpolation path that could disagree with the first.
 * - Nothing has to ship a LUT, so a filter costs no binary size and renders on the same
 *   code path as everything else. The alternative is a megabyte of cube files and a second
 *   renderer to be wrong in.
 *
 * The cost is that a filter cannot do anything the sliders cannot — no split toning, no
 * film curve. For "a few filters" beside a proper set of adjustments, that is the right
 * trade; a v2 that wants real looks should add LUTs deliberately rather than discover it
 * needs them.
 */
enum class Filter(val label: String) {
    NONE("Original"),
    VIVID("Vivid"),
    DRAMATIC("Dramatic"),
    WARM("Warm"),
    COOL("Cool"),
    MONO("Mono"),
    NOIR("Noir"),
    ;

    /** The slider positions this filter stands for, at full strength. */
    val adjustments: Adjustments
        get() = when (this) {
            NONE -> Adjustments.NONE
            VIVID -> Adjustments.of(
                Slider.SATURATION to 0.35,
                Slider.CONTRAST to 0.2,
                Slider.SHADOWS to 0.1,
            )
            DRAMATIC -> Adjustments.of(
                Slider.CONTRAST to 0.45,
                Slider.BLACK_POINT to 0.25,
                Slider.HIGHLIGHTS to -0.2,
                Slider.SATURATION to -0.15,
            )
            WARM -> Adjustments.of(
                Slider.WARMTH to 0.3,
                Slider.SATURATION to 0.1,
                Slider.SHADOWS to 0.1,
            )
            COOL -> Adjustments.of(
                Slider.WARMTH to -0.25,
                Slider.TINT to -0.1,
                Slider.CONTRAST to 0.1,
            )
            // Saturation at its floor is what makes these two monochrome, and it is the
            // reason SATURATION's range has to reach -1 rather than stopping at "muted".
            MONO -> Adjustments.of(
                Slider.SATURATION to -1.0,
                Slider.CONTRAST to 0.15,
            )
            NOIR -> Adjustments.of(
                Slider.SATURATION to -1.0,
                Slider.CONTRAST to 0.5,
                Slider.BLACK_POINT to 0.35,
                Slider.HIGHLIGHTS to -0.15,
            )
        }

    /**
     * The filter at a given strength, 0..1.
     *
     * A monochrome filter at half strength is a half-desaturated picture, not a
     * half-transparent black-and-white one laid over the original. Those are different
     * images, and this is the one the sliders can express — which is the point, because it
     * means the user can then keep adjusting from there.
     */
    fun at(strength: Double): Adjustments = adjustments.scaled(strength)

    companion object {
        /** The default when a filter is chosen: all of it, which is what a tap means. */
        const val FULL_STRENGTH = 1.0
    }
}
