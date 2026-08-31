package app.trimgallery.core.domain.edit

/**
 * A trim range, in milliseconds from the start of the clip (BUILD.md § 9: *"video trim"*).
 *
 * Clamped to the source on construction rather than validated by its callers, because the
 * handles that produce these values come from a drag on a filmstrip and will routinely
 * report a millisecond past the end.
 */
data class VideoTrim(val startMs: Long, val endMs: Long) {

    val durationMs: Long get() = endMs - startMs

    /** True when this keeps everything, so no trim is really being asked for. */
    fun isFull(sourceDurationMs: Long): Boolean = startMs <= 0 && endMs >= sourceDurationMs

    companion object {
        /**
         * The shortest clip the handles may produce.
         *
         * Half a second, because below that the two handles overlap on any filmstrip a
         * phone can draw and the gesture stops being controllable — not because a shorter
         * video would be invalid.
         */
        const val MIN_DURATION_MS = 500L

        fun full(sourceDurationMs: Long) = VideoTrim(0, sourceDurationMs)

        /** A range clamped into the source and never shorter than [MIN_DURATION_MS]. */
        fun of(startMs: Long, endMs: Long, sourceDurationMs: Long): VideoTrim {
            val limit = sourceDurationMs.coerceAtLeast(MIN_DURATION_MS)
            val start = startMs.coerceIn(0, (limit - MIN_DURATION_MS).coerceAtLeast(0))
            val end = endMs.coerceIn(start + MIN_DURATION_MS, limit)
            return VideoTrim(start, end)
        }
    }
}

/**
 * Whether a trim can be done by cutting the container rather than re-encoding it.
 *
 * This is the most valuable decision in the editor, and it is invisible: a trim that starts
 * on a keyframe is a **stream copy** — the original frames are moved into a new container
 * untouched. That is instant, costs no battery, and is genuinely lossless. A trim that
 * starts anywhere else has to decode and re-encode, which on an already-optimised clip
 * means a second generation of loss on top of the first (see `MediaItem.optimisedAt`).
 *
 * Only the *start* matters. The first frames of a cut that begins mid-group reference an
 * I-frame that is no longer in the file, so they cannot be decoded; the end can fall on any
 * frame, because truncating a group loses only frames the user asked to lose.
 *
 * So the editor offers the shift: "start 180 ms earlier and this is instant and lossless".
 * Most users take it, and the ones who do not get a correct re-encode.
 */
object TrimPlanner {

    /**
     * How far the editor will silently move a handle to reach a keyframe.
     *
     * Beyond this it asks instead. A quarter of a second is under the width of a filmstrip
     * thumbnail, so a shift inside it is smaller than the gesture that produced it — and
     * moving a handle further than the user can see them move it is the app editing their
     * edit.
     */
    const val SILENT_SNAP_MS = 120L

    /** How far a shift may be *offered* before re-encoding is simply the better answer. */
    const val OFFERABLE_SNAP_MS = 2_000L

    sealed interface Plan {
        /** Cut the container. Instant, lossless, no encoder. */
        data class StreamCopy(val trim: VideoTrim) : Plan

        /**
         * Decode and re-encode.
         *
         * [losslessAlternative] is the same cut starting at the previous keyframe, when one
         * is close enough to be worth offering. Null when nothing is near, or when the edit
         * does more than trim and would need an encode regardless.
         */
        data class Reencode(val trim: VideoTrim, val losslessAlternative: VideoTrim? = null) : Plan
    }

    /**
     * Plans one trim.
     *
     * @param keyframesMs the source's sync-sample times, ascending. An empty list means the
     *   container did not say, and the answer is then always a re-encode — guessing that
     *   frame zero is a keyframe would be right for most files and would produce an
     *   undecodable one for the rest.
     * @param otherEdits true when the recipe also crops, rotates, straightens or adjusts.
     *   Any of those needs the pixels, so the keyframes stop mattering.
     */
    fun plan(trim: VideoTrim, keyframesMs: List<Long>, sourceDurationMs: Long, otherEdits: Boolean = false): Plan {
        if (otherEdits) return Plan.Reencode(trim)
        if (trim.isFull(sourceDurationMs)) return Plan.StreamCopy(trim)
        if (keyframesMs.isEmpty()) return Plan.Reencode(trim)

        val previous = keyframesMs.lastOrNull { it <= trim.startMs }
        if (previous == trim.startMs) return Plan.StreamCopy(trim)

        // Backwards only. Snapping forward would drop footage the user chose to keep, which
        // is a worse outcome than an encode; snapping back keeps a fraction of a second they
        // chose to lose, which nobody notices.
        val snapped = previous?.let { VideoTrim(it, trim.endMs) }
        val shift = snapped?.let { trim.startMs - it.startMs }

        return when {
            snapped == null -> Plan.Reencode(trim)
            shift!! <= SILENT_SNAP_MS -> Plan.StreamCopy(snapped)
            shift <= OFFERABLE_SNAP_MS -> Plan.Reencode(trim, losslessAlternative = snapped)
            else -> Plan.Reencode(trim)
        }
    }

    /** The offer, in the user's words. Only shown when there is one to make. */
    fun explainAlternative(requested: VideoTrim, alternative: VideoTrim): String {
        val shift = requested.startMs - alternative.startMs
        return "Starting $shift ms earlier keeps the original quality and saves the wait."
    }
}
