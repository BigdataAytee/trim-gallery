package app.trimgallery.core.domain.edit

/**
 * One user's edit, as a value (BUILD.md § 9: *"crop, rotate, straighten, light/colour
 * sliders, a few filters, video trim. Non-destructive; original kept."*).
 *
 * The editor never touches pixels; it builds one of these. Everything that renders — the
 * live preview at screen size, the full-resolution save, and a future "revert to original"
 * — reads the same recipe, which is what makes the preview and the saved file agree. An
 * editor whose preview is a different code path from its export is an editor that will one
 * day save something the user did not see.
 *
 * It is also why [isIdentity] is worth having: an edit session the user backed out of by
 * undoing every step must not write a file. Comparing the recipe to the default is the only
 * check that is right for every combination, including "rotated four times".
 */
data class EditRecipe(
    val crop: CropGeometry.Rect = CropGeometry.Rect.FULL,
    val orientation: Orientation = Orientation.NORMAL,
    /** Small correction, degrees. Positive is clockwise. */
    val straightenDegrees: Double = 0.0,
    /** The user's own slider positions, on top of whatever the filter contributes. */
    val adjustments: Adjustments = Adjustments.NONE,
    val filter: Filter = Filter.NONE,
    val filterStrength: Double = Filter.FULL_STRENGTH,
    /** Null on a photo, and on a video the user did not trim. */
    val trim: VideoTrim? = null,
) {

    /**
     * The filter and the sliders, combined into the eight numbers the renderer wants.
     *
     * They add rather than override, so choosing a warm filter and then pulling warmth back
     * lands between the two instead of discarding one. Addition is commutative, so which is
     * "on top" does not matter — the only asymmetry is the clamp at ±1, and a filter plus a
     * slider that both push the same direction to the limit is the user asking for the
     * limit.
     */
    val effectiveAdjustments: Adjustments
        get() = filter.at(filterStrength).over(adjustments)

    val isCropped: Boolean get() = crop != CropGeometry.Rect.FULL

    val isStraightened: Boolean get() = straightenDegrees != 0.0

    /**
     * Whether anything about the picture's own pixels changes.
     *
     * Rotation is excluded on purpose: EXIF, HEIF and MP4 all carry an orientation, so a
     * turn on its own is a tag to write rather than an image to re-encode. That distinction
     * is the difference between a free, lossless rotate and a second generation of
     * compression, and `EditRender` is where it is acted on.
     */
    val changesPixels: Boolean
        get() = isCropped || isStraightened || !effectiveAdjustments.isNeutral

    /** A rotate or a flip and nothing else — the one edit that can be free. */
    val isOrientationOnly: Boolean
        get() = !orientation.isIdentity && !changesPixels && trim == null

    /**
     * True when the user has, in the end, asked for nothing.
     *
     * A null [sourceDurationMs] means the container did not report a duration, and a trim is
     * then an edit rather than a non-edit: without the length there is no way to know
     * whether the range covers the whole clip, and the safe reading of "I don't know" is the
     * one that keeps what the user asked for. Treating it as identity instead would silently
     * throw a trim away on exactly the files whose metadata is already unreliable.
     */
    fun isIdentity(sourceDurationMs: Long? = null): Boolean =
        orientation.isIdentity &&
            !changesPixels &&
            (trim == null || (sourceDurationMs != null && trim.isFull(sourceDurationMs)))

    /** How the edit reads in the viewer's info sheet: "Cropped · Vivid · Trimmed". */
    fun describe(): String = buildList {
        if (isCropped) add("Cropped")
        if (isStraightened) add("Straightened")
        if (!orientation.isIdentity) add("Rotated")
        if (filter != Filter.NONE) add(filter.label)
        if (!adjustments.isNeutral) add("Adjusted")
        if (trim != null) add("Trimmed")
    }.joinToString(" · ").ifEmpty { "No changes" }

    companion object {
        /** No edit at all. What a freshly opened editor holds. */
        val NONE = EditRecipe()
    }
}
