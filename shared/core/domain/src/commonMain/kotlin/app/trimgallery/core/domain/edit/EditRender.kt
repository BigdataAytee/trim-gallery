package app.trimgallery.core.domain.edit

import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind

/**
 * How little work an edit can be done with.
 *
 * The editor's most valuable decision is not which pixels to change but **whether to touch
 * the pixels at all**. Three of the things a user does in this editor need no encoder:
 *
 * - A rotate or a flip is a tag. EXIF, HEIF and MP4 all carry an orientation, so turning a
 *   photograph is a few bytes written, instantly and losslessly.
 * - A trim that starts on a keyframe is a container cut — the original frames moved into a
 *   new file untouched.
 * - An edit the user undid back to nothing is nothing.
 *
 * That matters more here than in most photo apps, because this app's whole claim is that it
 * compresses a library *once*. Re-encoding a clip it already optimised puts a second
 * generation of loss on top of the first (`MediaItem.optimisedAt`), and two generations are
 * visible. So every path that avoids the encoder is taken.
 */
object EditRender {

    sealed interface Plan {
        /** The user asked for nothing. Save should not be offered, let alone performed. */
        data object Nothing : Plan

        /**
         * Write an orientation tag. No decode, no encode, no quality lost, no time taken.
         *
         * The single most common edit in any gallery, and the one it would be most careless
         * to re-encode for.
         */
        data class MetadataOnly(val orientation: Orientation) : Plan

        /** Cut the container. Lossless and instant (see [TrimPlanner]). */
        data class StreamCopy(val trim: VideoTrim) : Plan

        /**
         * Decode, change, encode.
         *
         * [losslessAlternative] carries a nearby keyframe-aligned trim when one exists, so
         * the editor can offer "start 180 ms earlier and this is instant" rather than
         * silently spending the battery.
         */
        data class Reencode(
            val trim: VideoTrim? = null,
            val losslessAlternative: VideoTrim? = null,
        ) : Plan
    }

    /**
     * Plans one edit.
     *
     * @param keyframesMs the source's sync-sample times, ascending; empty when the container
     *   did not say, which forces a re-encode rather than a guess.
     */
    fun plan(recipe: EditRecipe, item: MediaItem, keyframesMs: List<Long> = emptyList()): Plan {
        val duration = item.duration
        if (recipe.isIdentity(duration)) return Plan.Nothing

        if (recipe.isOrientationOnly) return Plan.MetadataOnly(recipe.orientation)

        val trim = recipe.trim?.takeIf { duration != null && !it.isFull(duration) }
        if (trim == null || item.kind != MediaKind.VIDEO || duration == null) {
            return Plan.Reencode()
        }

        // A trim on its own can be a container cut; a trim with anything else cannot, because
        // everything else needs the pixels.
        val otherEdits = recipe.changesPixels || !recipe.orientation.isIdentity
        return when (val trimPlan = TrimPlanner.plan(trim, keyframesMs, duration, otherEdits)) {
            is TrimPlanner.Plan.StreamCopy -> Plan.StreamCopy(trimPlan.trim)
            is TrimPlanner.Plan.Reencode ->
                Plan.Reencode(trimPlan.trim, trimPlan.losslessAlternative)
        }
    }

    /** Whether this plan spends an encode, which is what decides the warnings and the wait. */
    fun usesEncoder(plan: Plan): Boolean = plan is Plan.Reencode

    /**
     * Whether the saved file is bit-for-bit as good as what went in.
     *
     * Said plainly in the save sheet, because PROJECT.md § Quality and reversibility is
     * explicit that this app never claims lossless where it is not — and here, for once, it
     * sometimes genuinely is.
     */
    fun isLossless(plan: Plan): Boolean = when (plan) {
        Plan.Nothing, is Plan.MetadataOnly, is Plan.StreamCopy -> true
        is Plan.Reencode -> false
    }
}
