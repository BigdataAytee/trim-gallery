package app.trimgallery.core.ui.theme

/**
 * The non-colour half of DESIGN_SYSTEM.md: type, shape, spacing and spring physics.
 *
 * Compose-free on purpose, for the same reason `TrimPalette` is: these numbers are
 * assertions about the design — that the scale steps, that every control clears the touch
 * target, that a spring is under-damped enough to feel physical without wobbling — and
 * assertions belong in a JVM test rather than in a screenshot nobody re-takes.
 */

/**
 * One typeface, seven roles (DESIGN_SYSTEM.md § Typography). Sizes in sp, line heights in
 * sp, weights on the CSS/Compose 100–900 scale.
 *
 * Numbers are set in tabular figures throughout: "Freed 6.2 GB" counts up on the morning
 * card, and proportional digits make the whole line jitter as it does.
 */
enum class TrimType(val size: Float, val lineHeight: Float, val weight: Int) {
    /** "Freed 6.2 GB". The one place the app is allowed to be loud. */
    DISPLAY(40f, 44f, 600),
    TITLE(22f, 28f, 600),
    HEADING(17f, 24f, 600),
    BODY(15f, 22f, 400),
    LABEL(13f, 18f, 500),
    CAPTION(11f, 16f, 400),
    ;

    companion object {
        /** DESIGN_SYSTEM.md § Typography: Inter variable, falling back to the system face. */
        const val FAMILY = "Inter"

        /** Dynamic type is honoured up to 200% (DESIGN_SYSTEM.md § Accessibility). */
        const val MAX_SCALE = 2.0f
    }
}

/** Corner radii, in dp (DESIGN_SYSTEM.md § Spacing and shape). */
object TrimShape {
    const val THUMBNAIL_DP = 4f
    const val CARD_DP = 16f
    const val SHEET_DP = 24f
    const val BUTTON_DP = 12f

    /** Fully round. Chips are pills at any height. */
    const val CHIP_DP = 999f
}

/** The 4-pt grid, in dp (DESIGN_SYSTEM.md § Spacing and shape). */
object TrimSpacing {
    const val GRID_DP = 4f
    const val INSET_DP = 16f
    const val CARD_PADDING_DP = 16f

    /**
     * Gutters narrow as the grid zooms out, to nothing at year level.
     *
     * At year level the point is the *shape* of a year — where the busy months are — and
     * any gap at all breaks the block into stripes that read as structure the data does
     * not have.
     */
    const val GUTTER_DAY_DP = 2f
    const val GUTTER_MONTH_DP = 1f
    const val GUTTER_YEAR_DP = 0f

    /** DESIGN_SYSTEM.md § Accessibility: every control is at least this big. */
    const val MIN_TOUCH_TARGET_DP = 48f

    /** Hairline width. Elevation is blur plus this, never a shadow. */
    const val HAIRLINE_DP = 1f
}

/**
 * Spring physics (DESIGN_SYSTEM.md § Motion tokens).
 *
 * Stiffness and damping map directly onto Compose's `SpringSpec`. All three are
 * under-damped — a critically damped spring is indistinguishable from an ease-out and
 * gives up the reason for using a spring at all — but only just: the damping ratios below
 * settle without a visible second bounce, which on a gallery would read as a glitch rather
 * than as physics.
 */
enum class TrimSpring(val stiffness: Float, val damping: Float) {
    /** Most transitions, and both halves of the shared element. */
    STANDARD(400f, 30f),

    /** Sheets, cards, and the snap at the end of a pinch. Slower, so it reads as weight. */
    GENTLE(250f, 28f),

    /** Toggles and chips. Fast enough to feel like a direct response to the finger. */
    SNAPPY(700f, 35f),
    ;

    /**
     * Damping ratio for unit mass: `c / (2 √k)`.
     *
     * Below 1 the spring overshoots. Kept between roughly 0.65 and 0.9 so there is one
     * visible settle and never a second.
     */
    val dampingRatio: Float
        get() = damping / (2f * kotlin.math.sqrt(stiffness))
}

/**
 * What replaces motion when the user has asked for less of it
 * (DESIGN_SYSTEM.md § Motion tokens, and BUILD.md's accessibility line).
 *
 * Not "no animation": a shared element that cuts is harder to follow than one that moves,
 * because the eye loses the thing it was tracking. Springs become a short ease-out, and
 * the count-ups — which are decoration, not information — stop entirely.
 */
object ReducedMotion {
    const val DURATION_MS = 200
    const val COUNT_UP_ENABLED = false
}
