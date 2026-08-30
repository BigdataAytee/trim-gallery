package app.trimgallery.core.domain.edit

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The crop frame: where it is, what shape it may be, and how far the picture can be
 * straightened before the frame runs out of image (BUILD.md § 9).
 *
 * All of it is arithmetic, and all of it is the kind of arithmetic that is wrong in a way
 * nobody notices until a user's photograph has a sliver of grey along one edge. So it is
 * here, in shared code, with tests — not in a gesture handler.
 *
 * **Coordinates are normalised to the source**, 0..1 of its width and height. That keeps
 * the recipe independent of the resolution it is previewed at, which matters because the
 * editor works on a screen-sized bitmap and the save re-renders from the full-size file. It
 * also means a normalised rectangle's *shape* is not its aspect ratio: on a 4000×3000
 * source, a normalised 0.5×0.5 rectangle is 2000×1500, which is 4:3 rather than square.
 * Every function that cares takes [sourceAspect] for exactly that reason.
 */
object CropGeometry {

    /** The smallest crop the user may drag to, as a fraction of the source. */
    const val MIN_SIDE = 0.05

    /** BUILD.md § 9 calls it "straighten", which is a small correction, not a rotation. */
    const val MAX_STRAIGHTEN_DEGREES = 15.0

    /** A rectangle in normalised source coordinates. */
    data class Rect(
        val left: Double,
        val top: Double,
        val right: Double,
        val bottom: Double,
    ) {
        val width: Double get() = right - left
        val height: Double get() = bottom - top
        val centerX: Double get() = (left + right) / 2
        val centerY: Double get() = (top + bottom) / 2

        /** The shape the user sees, which needs the source's own proportions to know. */
        fun aspect(sourceAspect: Double): Double = (width * sourceAspect) / height

        val isValid: Boolean get() = width > 0 && height > 0

        companion object {
            /** The whole picture. */
            val FULL = Rect(0.0, 0.0, 1.0, 1.0)
        }
    }

    /** What the crop's shape is allowed to be. */
    sealed interface AspectLock {
        /** Any shape the user drags to. */
        data object Free : AspectLock

        /** Whatever the source is, so a crop of a 16:9 clip stays 16:9. */
        data object Original : AspectLock

        /** A named ratio: 1:1, 4:3, 16:9, and their portrait forms. */
        data class Fixed(val width: Int, val height: Int) : AspectLock {
            val ratio: Double get() = width.toDouble() / height
        }

        /** The ratio this lock requires, or null when it requires nothing. */
        fun ratioFor(sourceAspect: Double): Double? = when (this) {
            Free -> null
            Original -> sourceAspect
            is Fixed -> ratio
        }
    }

    /** The presets the editor offers, in the order they are shown. */
    val PRESETS = listOf(
        AspectLock.Free,
        AspectLock.Original,
        AspectLock.Fixed(1, 1),
        AspectLock.Fixed(4, 3),
        AspectLock.Fixed(3, 4),
        AspectLock.Fixed(16, 9),
        AspectLock.Fixed(9, 16),
        AspectLock.Fixed(3, 2),
        AspectLock.Fixed(2, 3),
    )

    /** The eight drag handles. */
    enum class Handle(
        val movesLeft: Boolean,
        val movesTop: Boolean,
        val movesRight: Boolean,
        val movesBottom: Boolean,
    ) {
        TOP_LEFT(true, true, false, false),
        TOP(false, true, false, false),
        TOP_RIGHT(false, true, true, false),
        LEFT(true, false, false, false),
        RIGHT(false, false, true, false),
        BOTTOM_LEFT(true, false, false, true),
        BOTTOM(false, false, false, true),
        BOTTOM_RIGHT(false, false, true, true),
        ;

        val isCorner: Boolean get() = (movesLeft || movesRight) && (movesTop || movesBottom)
    }

    /** Keeps a rectangle inside the picture without changing its size, where it can. */
    fun clamp(rect: Rect): Rect {
        val width = min(rect.width, 1.0)
        val height = min(rect.height, 1.0)
        val left = rect.left.coerceIn(0.0, 1.0 - width)
        val top = rect.top.coerceIn(0.0, 1.0 - height)
        return Rect(left, top, left + width, top + height)
    }

    /**
     * Drags one handle.
     *
     * The opposite corner stays put — that is what makes a drag feel like grabbing an edge
     * rather than moving the whole frame. With an aspect lock the other axis follows, and
     * the direction it follows from is the handle: dragging a side adjusts around the
     * centre of the perpendicular axis, dragging a corner adjusts from the anchored corner.
     *
     * Everything is clamped last, so a drag that would leave the picture stops at the edge
     * instead of being refused. A crop gesture that simply stops responding reads as a bug
     * even when the refusal is correct.
     */
    @Suppress("CyclomaticComplexMethod")
    fun drag(
        current: Rect,
        handle: Handle,
        dx: Double = 0.0,
        dy: Double = 0.0,
        lock: AspectLock = AspectLock.Free,
        sourceAspect: Double = 1.0,
    ): Rect {
        var left = current.left + if (handle.movesLeft) dx else 0.0
        var top = current.top + if (handle.movesTop) dy else 0.0
        var right = current.right + if (handle.movesRight) dx else 0.0
        var bottom = current.bottom + if (handle.movesBottom) dy else 0.0

        // Keep at least MIN_SIDE, by moving the dragged edge back rather than the anchored one.
        if (right - left < MIN_SIDE) {
            if (handle.movesLeft) left = right - MIN_SIDE else right = left + MIN_SIDE
        }
        if (bottom - top < MIN_SIDE) {
            if (handle.movesTop) top = bottom - MIN_SIDE else bottom = top + MIN_SIDE
        }

        val ratio = lock.ratioFor(sourceAspect)
            ?: return Rect(left, top, right, bottom).coerceInside()

        // The dragged axis leads and the other follows: on a side handle the user is
        // stating one dimension, and deriving the other from it is the only reading that
        // does not fight the gesture.
        val locked = if (handle.isCorner || handle == Handle.LEFT || handle == Handle.RIGHT) {
            val width = (right - left).coerceAtLeast(MIN_SIDE)
            val height = (width * sourceAspect / ratio).coerceAtLeast(MIN_SIDE)
            val (newTop, newBottom) = span(current.top, current.bottom, height, handle.movesTop, handle.movesBottom)
            Rect(left, newTop, right, newBottom)
        } else {
            val height = (bottom - top).coerceAtLeast(MIN_SIDE)
            val width = (height * ratio / sourceAspect).coerceAtLeast(MIN_SIDE)
            val (newLeft, newRight) = span(current.left, current.right, width, handle.movesLeft, handle.movesRight)
            Rect(newLeft, top, newRight, bottom)
        }

        // A locked crop is never coerced edge-by-edge: cropping one side to the picture's
        // boundary would silently break the lock the user chose. It shrinks about its own
        // centre if it has outgrown the picture, then slides back inside at that size.
        return clamp(locked.shrunkToFit())
    }

    /**
     * The largest crop of a given shape that still fits, centred, once the picture is
     * straightened.
     *
     * This is the number that decides whether straightening shows a grey corner. Rotating a
     * centred W×H frame by θ inside a w×h picture, the frame fits exactly when its
     * axis-aligned bounding box does — true because the frame is convex and centred on the
     * picture, so its corners are the only extremes:
     *
     * ```
     * W·|cos θ| + H·|sin θ| ≤ w
     * W·|sin θ| + H·|cos θ| ≤ h
     * ```
     *
     * With H = W/aspect, both give a bound on W and the smaller wins. Returned as a
     * fraction of the source's width, so the caller does not have to know the pixels.
     */
    fun maxCentredWidth(sourceAspect: Double, angleDegrees: Double, aspect: Double): Double {
        val radians = angleDegrees * PI_OVER_180
        val cosA = abs(cos(radians))
        val sinA = abs(sin(radians))

        // Pixel space, normalised so the source is `sourceAspect` wide and 1 tall.
        val byWidth = sourceAspect / (cosA + sinA / aspect)
        val byHeight = 1.0 / (sinA + cosA / aspect)
        return min(byWidth, byHeight) / sourceAspect
    }

    /**
     * The crop the editor snaps to while the straighten slider moves.
     *
     * Centred, because the alternative — keeping the user's framing and letting a corner go
     * grey — means the picture silently loses content the user did not choose to lose. Every
     * editor worth using zooms instead, and this is the amount it has to zoom by.
     */
    fun straightenedCrop(sourceAspect: Double, angleDegrees: Double, aspect: Double): Rect {
        val width = maxCentredWidth(sourceAspect, angleDegrees, aspect).coerceIn(MIN_SIDE, 1.0)
        val height = (width * sourceAspect / aspect).coerceIn(MIN_SIDE, 1.0)
        val left = (1.0 - width) / 2
        val top = (1.0 - height) / 2
        return Rect(left, top, left + width, top + height)
    }

    /** How much of the picture a crop keeps, for "this will be a 6 MP photo" in the sheet. */
    fun keptFraction(rect: Rect): Double = (rect.width * rect.height).coerceIn(0.0, 1.0)

    /** The output's pixel size, rounded to even numbers because encoders require them. */
    fun outputSize(sourceWidth: Int, sourceHeight: Int, rect: Rect, orientation: Orientation): Pair<Int, Int> {
        val w = (sourceWidth * rect.width).toInt().coerceAtLeast(2) and 1.inv()
        val h = (sourceHeight * rect.height).toInt().coerceAtLeast(2) and 1.inv()
        return if (orientation.swapsDimensions) h to w else w to h
    }

    /**
     * Moves one edge pair to a new length, keeping whichever side the drag anchored.
     *
     * When neither side moved — a corner drag's following axis — it grows from the centre,
     * which is what keeps a corner drag from walking the frame across the picture.
     */
    private fun span(
        start: Double,
        end: Double,
        length: Double,
        movesStart: Boolean,
        movesEnd: Boolean,
    ): Pair<Double, Double> = when {
        movesStart && !movesEnd -> (end - length) to end
        movesEnd && !movesStart -> start to (start + length)
        else -> {
            val center = (start + end) / 2
            (center - length / 2) to (center + length / 2)
        }
    }

    /** Pulls a rectangle back inside 0..1 without letting it invert. */
    private fun Rect.coerceInside(): Rect = Rect(
        left = left.coerceIn(0.0, 1.0 - MIN_SIDE),
        top = top.coerceIn(0.0, 1.0 - MIN_SIDE),
        right = right.coerceIn(MIN_SIDE, 1.0),
        bottom = bottom.coerceIn(MIN_SIDE, 1.0),
    )

    /** Scales a rectangle about its own centre until it fits, keeping its shape exactly. */
    private fun Rect.shrunkToFit(): Rect {
        val scale = min(1.0, min(1.0 / width, 1.0 / height))
        if (scale >= 1.0) return this
        val halfWidth = width * scale / 2
        val halfHeight = height * scale / 2
        return Rect(centerX - halfWidth, centerY - halfHeight, centerX + halfWidth, centerY + halfHeight)
    }

    private const val PI_OVER_180 = 0.017453292519943295
}
