package app.trimgallery.core.ui.grid

/**
 * The three grid densities the user pinches between (BUILD.md § 9: "Pinch-zoom between
 * day / month / year grids").
 *
 * Column counts, not tile sizes: the shell is a fixed-width column, so the number of
 * columns is what actually determines how much library fits on screen.
 */
enum class GridZoom(val columns: Int) {
    /** Individual days. Large enough to recognise a photo without opening it. */
    DAY(3),

    /** A month at a time. The default reading distance for a year-old library. */
    MONTH(5),

    /** Whole years. Small enough to scrub a decade. */
    YEAR(9),
    ;

    val zoomedIn: GridZoom get() = entries.getOrElse(ordinal - 1) { this }
    val zoomedOut: GridZoom get() = entries.getOrElse(ordinal + 1) { this }

    companion object {
        val Default = DAY

        /**
         * How far a pinch must travel before the level changes.
         *
         * A pinch is continuous and a level is discrete, so without a deadband the grid
         * flickers between two densities while the fingers are still moving. 1.35 means
         * a deliberate gesture changes level and an incidental one does not.
         */
        const val STEP_RATIO = 1.35f

        /**
         * The level a pinch of [scale] from [from] lands on.
         *
         * Only ever one step per gesture: a pinch that crosses two thresholds at once is
         * almost always a fumble, and skipping a level loses the user's place.
         */
        fun step(from: GridZoom, scale: Float): GridZoom = when {
            scale >= STEP_RATIO -> from.zoomedIn
            scale <= 1f / STEP_RATIO -> from.zoomedOut
            else -> from
        }
    }
}
