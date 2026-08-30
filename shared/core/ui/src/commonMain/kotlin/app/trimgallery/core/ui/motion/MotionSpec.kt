package app.trimgallery.core.ui.motion

import app.trimgallery.core.ui.theme.ReducedMotion
import app.trimgallery.core.ui.theme.TrimShape
import app.trimgallery.core.ui.theme.TrimSpring

/**
 * The gallery's motion, as numbers.
 *
 * BUILD.md § 9 asks for shared-element transitions between the grid and the viewer,
 * spring physics on dismissals, and muted autoplay in the grid. The timings here are
 * ported from `buyer-gallery-spec/reference-prototype.html`, whose feel was signed off,
 * and were measured in a browser before being copied (see
 * `design/buyer-gallery/tests/acceptance.mjs`).
 *
 * Deliberately plain Kotlin with no Compose types. Two reasons: it can be unit tested on
 * the JVM without a UI toolkit, and the same numbers drive Android and iOS rather than
 * being retyped per platform.
 *
 * Easings are cubic-Bézier control points in CSS order (x1, y1, x2, y2), which map
 * directly onto Compose's `CubicBezierEasing`.
 */
object MotionSpec {

    /** A cubic-Bézier easing curve. */
    data class Easing(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

    /** Tiles fade, rise and settle as they scroll into view. */
    object Arrival {
        const val DURATION_MS = 600

        /** Each tile in a row group starts this much after the one before it. */
        const val STAGGER_MS = 70

        /** Stagger restarts every N tiles, so a long grid never accumulates delay. */
        const val STAGGER_GROUP = 6
        const val FROM_TRANSLATION_Y_DP = 18f
        const val FROM_SCALE = 0.96f
        const val FROM_ALPHA = 0f
        val EASING = Easing(0.2f, 0.8f, 0.2f, 1f)

        /** Delay for the tile at [index]; the group wrap keeps the last row prompt. */
        fun delayMs(index: Int): Int = (index % STAGGER_GROUP) * STAGGER_MS
    }

    /**
     * The slow pulse that marks a thumbnail as being worked on.
     *
     * In the reference this is ambient decoration; here it earns its place as the
     * "thin progress ring on thumbnails being processed" from BUILD.md § 9.
     */
    object Breathing {
        const val PERIOD_MS = 4600

        /** DESIGN_SYSTEM.md, `progress-ring`: 2-px stroke in the accent, at 60 fps. */
        const val RING_DP = 2f
        const val HALO_DP = 30f
        const val HALO_SPREAD_DP = 6f

        /** Peak brightness multiplier at the middle of the cycle. */
        const val PEAK_BRIGHTNESS = 1.05f

        /** Reduced motion gets a static ring of this width instead of a pulse. */
        const val STATIC_RING_DP = 1.5f
        const val STATIC_RING_ALPHA = 0.4f
    }

    /**
     * Grid tile to full-bleed viewer, and back (DESIGN_SYSTEM.md, `shared-element`).
     *
     * The design system specifies `spring-standard` on the bounds with the corner radius
     * going 4 → 0, which supersedes the reference prototype's duration-and-Bézier version
     * of the same transition. The durations below are kept only as the reduce-motion
     * fallback and as the Macrobenchmark budget: a spring has no duration to assert
     * against, and the frame budget still has to be met.
     */
    object Hero {
        val SPRING = TrimSpring.STANDARD

        /** Thumbnail radius, opening out to a full-bleed square corner. */
        const val TILE_RADIUS_DP = TrimShape.THUMBNAIL_DP
        const val HERO_RADIUS_DP = 0f

        /** Reduce-motion fallback, and the window Macrobenchmark measures. */
        const val OPEN_MS = ReducedMotion.DURATION_MS
        const val CLOSE_MS = ReducedMotion.DURATION_MS
        val OPEN_EASING = Easing(0.2f, 0.9f, 0.25f, 1.1f)
        val CLOSE_EASING = Easing(0.3f, 0.7f, 0.3f, 1f)
    }

    /**
     * Closing the viewer (DESIGN_SYSTEM.md, `dismiss`).
     *
     * The drag follows the finger exactly — 1:1, no rubber-banding — because the image is
     * the thing being moved, not a proxy for it. On release it springs back into the grid
     * slot it came from, and the chrome fades over 120 ms so it is gone before the image
     * lands rather than arriving with it.
     */
    object Dismiss {
        const val DRAG_RATIO = 1f
        val SPRING = TrimSpring.STANDARD
        const val CHROME_FADE_MS = 120
    }

    /** Pinch between day / month / year (DESIGN_SYSTEM.md, `grid-zoom`). */
    object GridZoomMotion {
        /** Cells scale continuously under the fingers, then snap to the level. */
        val SNAP_SPRING = TrimSpring.GENTLE
    }

    /** The morning card (DESIGN_SYSTEM.md, `result-card`). */
    object ResultCard {
        val SPRING = TrimSpring.GENTLE

        /** "Freed 6.2 GB" counts up. Decoration, so reduce-motion drops it entirely. */
        const val COUNT_UP_MS = 800
    }

    /** List rows arriving (DESIGN_SYSTEM.md, `reveal`). */
    object Reveal {
        const val DURATION_MS = 150
        const val FROM_TRANSLATION_Y_DP = 8f
        const val FROM_ALPHA = 0f
    }

    /** The dimmed, blurred backdrop behind an open viewer. */
    object Veil {
        const val FADE_MS = 350
        const val BLUR_DP = 14f
        const val ALPHA = 0.82f
    }

    /** The info sheet that follows the image up. */
    object Sheet {
        const val DURATION_MS = 450

        /** Starts after the zoom has visibly begun, so the image leads. */
        const val DELAY_MS = 120
        val EASING = Easing(0.2f, 0.8f, 0.2f, 1f)
        const val CORNER_RADIUS_DP = 26f
    }

    /** Press feedback on tiles and controls. */
    object Press {
        const val SCALE = 0.97f
        const val DURATION_MS = 120
    }

    /** Light/dark crossfade. */
    const val THEME_CROSSFADE_MS = 350

    /**
     * A clip plays only once at least this much of it is on screen, and stops when it
     * falls below again (BUILD.md § 9, "Videos autoplay muted on hover in the grid").
     */
    const val AUTOPLAY_VISIBLE_FRACTION = 0.5f

    /**
     * BUILD.md § 2.7: the grid must hold display refresh rate while the night pass runs.
     * At 120 Hz that is 8.33 ms per frame; Macrobenchmark asserts against this.
     */
    const val FRAME_BUDGET_MS_120HZ = 1000f / 120f
}
