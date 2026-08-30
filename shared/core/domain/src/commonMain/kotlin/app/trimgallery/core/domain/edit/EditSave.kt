package app.trimgallery.core.domain.edit

import app.trimgallery.core.domain.billing.Tier
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaStatus

/**
 * What "Save" and "Save over" actually do (USER_JOURNEY.md § 11).
 *
 * > Viewer → Edit → crop/rotate/straighten, light/colour, filters, video trim → Save (new
 * > copy, original kept) or Save over (goes to undo bin).
 *
 * This is where the editor meets the two rules the rest of the app is built around, and
 * both need care, because the obvious implementation gets each of them wrong in a different
 * direction.
 *
 * ### The verification gates do not all apply
 *
 * The optimiser's gates are "the output opens, plays for its full duration, scores VMAF ≥ 95
 * against the original, and is smaller" (BUILD.md § 5). Two of those are *wrong* for an
 * edit:
 *
 * - **Not smaller.** A crop re-encoded, or a photograph brightened, may be larger than what
 *   it replaced. Refusing to save it would be refusing to do what the user asked.
 * - **Not VMAF ≥ 95.** There is no reference. The output is *meant* to differ from the
 *   original — that is the entire point — and scoring it against one would fail every
 *   successful edit.
 *
 * What still applies, and must: the output opens, contains what it should, runs for the
 * duration the edit implies, and the original's size and mtime have not moved while the
 * render ran. Dropping those alongside the quality gate is the mistake this class exists to
 * prevent — "the edit path skips verification" is one sentence away from "the edit path
 * replaces a file with a truncated one".
 *
 * ### Editing does not make a file exempt from the optimiser, but it must not double-encode it
 *
 * A re-encoded edit is one generation of loss. If the night pass then optimises the result,
 * that is two, and two are visible. So a saved-over re-encode sets `optimisedAt` — which is
 * literally what that column means, *"when this app last replaced this file"* — and the
 * night pass leaves it alone. No `Job` row is written and no saving is claimed, because
 * none was measured; the file is simply not offered up for a second encode.
 */
object EditSave {

    /** Where the edited file goes. */
    enum class Destination {
        /** A new file beside the original, which is left exactly as it was. */
        NEW_COPY,

        /** Replaces the original, which goes to the undo bin (BUILD.md § 6). */
        OVER_ORIGINAL,
        ;

        /**
         * Both write into a folder the user granted, so both go through `Replacer` and
         * nothing else (ARCHITECTURE.md § 14, enforced by a build guard). A new copy is not
         * a lesser write for being a new file.
         */
        val throughReplacer: Boolean get() = true

        /** Only a replacement has an original to park, and therefore an undo entry. */
        val parksOriginal: Boolean get() = this == OVER_ORIGINAL
    }

    /**
     * The checks an edited output must pass before it is allowed near the library.
     *
     * Spelled out as data rather than as code in a save function so that the difference from
     * the optimiser's gates is visible, and so a test can assert it. Every field that is
     * false here is false for a stated reason.
     */
    data class Gates(
        /** The file opens and contains a video or image track. Always. */
        val requireOpenable: Boolean = true,

        /**
         * Runs for the duration the edit implies — the trim's length, or the source's when
         * there is no trim. The one gate a truncated render fails.
         */
        val requireExpectedDuration: Boolean = true,

        /**
         * Never. An edit may legitimately be larger than what it replaces, and the user
         * asked for it.
         */
        val requireSmaller: Boolean = false,

        /** Never. There is no reference to score against; the output is meant to differ. */
        val requireQualityScore: Boolean = false,

        /**
         * The safe-replace snapshot: size and mtime taken before the render and re-checked
         * after. Only a replacement can lose a concurrent edit, so only a replacement checks.
         */
        val requireUnchangedOriginal: Boolean = false,
    )

    fun gatesFor(destination: Destination): Gates =
        Gates(requireUnchangedOriginal = destination == Destination.OVER_ORIGINAL)

    /**
     * MONETIZATION.md lists the editor in the row that is ticked for both tiers.
     *
     * A function rather than a constant so the intent is greppable: nothing about editing is
     * gated, not by tier, not by the daily Compress now count, not by the monthly cap. An
     * edit is not an optimisation and must never be counted as one.
     */
    fun isAllowed(@Suppress("UNUSED_PARAMETER") tier: Tier): Boolean = true

    /**
     * Which parts of the index a save invalidates.
     *
     * A rotation is the one edit that keeps its meaning: the same faces, the same words, the
     * same labels, because every detector is given the orientation and works in the upright
     * frame. Its *hash* still changes, because the perceptual hash is built on a grid of
     * pixels and turning the picture permutes them — so a rotated photograph would stop
     * matching its own duplicates if the hash were left alone.
     *
     * Everything else changes the pixels enough to change the content: a crop can remove a
     * face or a sign outright, and a trim can remove the frames a label came from.
     */
    fun invalidatedBy(plan: EditRender.Plan): Invalidation = when (plan) {
        EditRender.Plan.Nothing -> Invalidation.NOTHING
        is EditRender.Plan.MetadataOnly -> Invalidation(hashes = true)
        is EditRender.Plan.StreamCopy, is EditRender.Plan.Reencode -> Invalidation.EVERYTHING
    }

    /** What has to be computed again after a save. */
    data class Invalidation(
        val hashes: Boolean = false,
        val labels: Boolean = false,
        val faces: Boolean = false,
        val text: Boolean = false,
    ) {
        val any: Boolean get() = hashes || labels || faces || text

        companion object {
            val NOTHING = Invalidation()
            val EVERYTHING = Invalidation(hashes = true, labels = true, faces = true, text = true)
        }
    }

    /**
     * The row as it stands after a save over the original.
     *
     * Returned rather than written, because this object does no database work — but the
     * decisions in it are the ones that would be wrong if each call site made them:
     *
     * - Back to `NEW`, so the next pass indexes it. A `DONE` item with a stale hash is a
     *   photograph that has quietly left its own duplicate group.
     * - `skipReason` cleared, because whatever the file used to be is no longer what it is:
     *   a 4K clip cropped to a quarter of its pixels is not the file triage skipped.
     * - `optimisedAt` set only when an encoder ran. A rotate or a keyframe trim moves the
     *   original bytes, so the file is exactly as un-optimised as it was, and marking it
     *   would cost the user the saving the night pass would have found.
     */
    fun afterSaveOver(
        item: MediaItem,
        plan: EditRender.Plan,
        newSize: Long,
        nowMs: Long,
    ): MediaItem = item.copy(
        size = newSize,
        mtime = nowMs,
        status = MediaStatus.NEW,
        skipReason = null,
        phash = null,
        sha256 = null,
        estSaving = null,
        updatedAt = nowMs,
        optimisedAt = if (EditRender.usesEncoder(plan)) nowMs else item.optimisedAt,
    )

    /**
     * What the sheet says before the user commits.
     *
     * The lossless cases are named out loud because they are the app's own claim made good:
     * PROJECT.md § Quality and reversibility is explicit that "visually lossless" is not
     * lossless, so on the occasions when it genuinely is, saying so is the honest thing —
     * and it is what makes the offer to shift a trim handle worth taking.
     */
    fun describe(plan: EditRender.Plan, destination: Destination): String = when (plan) {
        EditRender.Plan.Nothing -> "Nothing to save."
        is EditRender.Plan.MetadataOnly -> "Instant, and nothing is re-encoded."
        is EditRender.Plan.StreamCopy -> "Instant, and the video keeps its original quality."
        is EditRender.Plan.Reencode -> when (destination) {
            Destination.NEW_COPY -> "Saves a new file. Your original stays exactly as it is."
            Destination.OVER_ORIGINAL -> "Your original moves to Recently deleted, so you can get it back."
        }
    }
}
