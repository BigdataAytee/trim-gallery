package app.trimgallery.core.ui.motion

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Where a tile travels to when it opens, and every frame in between.
 *
 * This is the arithmetic behind the shared-element transition (BUILD.md § 9). It is kept
 * out of the composable so it can be unit tested: the numbers below are asserted against
 * the values measured in a browser from the signed-off reference prototype, which is the
 * only way to know the port matches rather than merely resembles it.
 */
object HeroGeometry {

    /** A rectangle in window coordinates, in density-independent pixels. */
    data class Rect(val left: Float, val top: Float, val width: Float, val height: Float) {
        val right: Float get() = left + width
        val bottom: Float get() = top + height
        val centerX: Float get() = left + width / 2f
    }

    /** The gallery is laid out in a column no wider than this, centred. */
    const val SHELL_MAX_DP = 430f

    /** Breathing room either side of the opened image. */
    const val SIDE_INSET_DP = 36f

    /**
     * How far down the leftover vertical space the image sits.
     *
     * Above centre, so the info sheet has room to rise underneath it without covering
     * the thing the viewer just tapped.
     */
    const val VERTICAL_BIAS = 0.35f

    /** Never tuck the image under the status bar or a back button. */
    const val MIN_TOP_DP = 80f

    /**
     * The square an opened tile animates to, for a window of [windowWidth] x
     * [windowHeight] dp.
     *
     * Square rather than the source aspect ratio: the grid is square, so a square target
     * means the shared element never changes shape mid-flight, only size and position.
     */
    fun target(windowWidth: Float, windowHeight: Float): Rect {
        // coerceAtLeast(0), because a window narrower than the inset makes this negative
        // and a negative Rect is not a small rectangle — it is a crash. `Modifier.size`
        // rejects negative constraints, so `target(0f, 0f)` (the fallback GalleryScreen
        // used when a tile had not reported its bounds) took the app down on every tap.
        // A zero-size rectangle animates from a point, which is the right degenerate case.
        val size = (min(windowWidth, SHELL_MAX_DP) - SIDE_INSET_DP).coerceAtLeast(0f)
        return Rect(
            left = (windowWidth - size) / 2f,
            top = max(MIN_TOP_DP, (windowHeight - size) * VERTICAL_BIAS),
            width = size,
            height = size,
        )
    }

    /**
     * The rectangle at [fraction] of the way from [from] to [to].
     *
     * Interpolated as a whole rather than as independent edges, so the shape stays
     * coherent even when an overshoot easing pushes [fraction] past 1.
     */
    fun lerp(from: Rect, to: Rect, fraction: Float): Rect = Rect(
        left = from.left + (to.left - from.left) * fraction,
        top = from.top + (to.top - from.top) * fraction,
        width = from.width + (to.width - from.width) * fraction,
        height = from.height + (to.height - from.height) * fraction,
    )

    /** Corner radius part-way through the same journey. */
    fun lerpRadius(fraction: Float): Float = MotionSpec.Hero.TILE_RADIUS_DP +
        (MotionSpec.Hero.HERO_RADIUS_DP - MotionSpec.Hero.TILE_RADIUS_DP) * fraction

    /**
     * How far a drag has dismissed the viewer, 0..1.
     *
     * BUILD.md § 9: "drag-down shrinks it back into place". The image tracks the finger
     * and a spring finishes the journey, so the threshold only decides the direction the
     * spring settles in.
     */
    fun dismissProgress(dragDp: Float, windowHeight: Float): Float =
        (abs(dragDp) / (windowHeight * DISMISS_TRAVEL_FRACTION)).coerceIn(0f, 1f)

    /** Past this much progress the drag completes rather than snapping back. */
    const val DISMISS_THRESHOLD = 0.3f

    /** A drag of this fraction of the window height counts as a full dismissal. */
    const val DISMISS_TRAVEL_FRACTION = 0.5f

    /** The image shrinks as it is dragged away, down to this at full progress. */
    fun dismissScale(progress: Float): Float = 1f - (1f - MIN_DISMISS_SCALE) * progress

    private const val MIN_DISMISS_SCALE = 0.82f
}
