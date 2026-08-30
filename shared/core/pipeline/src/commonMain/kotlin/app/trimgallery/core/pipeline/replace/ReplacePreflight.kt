package app.trimgallery.core.pipeline.replace

import app.trimgallery.core.model.SkipReason

/**
 * What a replacement would lose, decided before anything is encoded.
 *
 * Android renames a file over a path, so the row, its name and everything hanging off it
 * survive by construction. **iOS has no rename.** A replacement is a *new* `PHAsset`, and a
 * new asset arrives with nothing: no albums, no favourite, no creation date, no history.
 * `SafeReplacerIos` re-applies what it can inside the one change block — but "what it can"
 * is not everything, and the difference is the whole of this file.
 *
 * The rule: **anything that cannot be put back is a reason not to start.** Discovering it
 * during the swap would mean either aborting halfway through the user's only copy or
 * finishing and silently dropping something they will not notice for months. Detecting it
 * first costs a metadata read and skips the file with a reason (BUILD.md § 9).
 *
 * Shared, and tested on the JVM, because it is a decision rather than PhotoKit plumbing —
 * and because it is the kind of decision nobody can check by looking at Swift.
 */
object ReplacePreflight {

    /** How a collection behaves when you try to add an asset to it. */
    enum class AlbumKind {
        /**
         * A user-made album. `PHAssetCollectionChangeRequest(for:)` accepts it, so
         * membership is carried across inside the change block.
         */
        USER,

        /**
         * A system smart album whose membership the OS *derives* from properties this app
         * carries across anyway — Favourites, Recently Added, Videos.
         *
         * Cannot be added to programmatically, and does not need to be: the replacement
         * matches the same predicate the original did, so it lands in the same place.
         */
        DERIVED_SMART,

        /**
         * A smart album whose predicate this app does not reproduce.
         *
         * Cannot be added to and will not re-derive, so membership is lost. Skipped.
         */
        OPAQUE_SMART,

        /**
         * A shared album — iCloud Shared, or somebody else's.
         *
         * Adding to one re-uploads the asset to another person's library, which is both a
         * network operation this app cannot perform and a decision that is not ours to make.
         */
        SHARED,
    }

    data class Album(val id: String, val kind: AlbumKind)

    /**
     * Everything about an asset that a replacement has to preserve or refuse.
     *
     * Read from `PHAsset` before the encode. Deliberately a value rather than a handle, so
     * this object can be exercised without a photo library.
     */
    data class AssetState(
        val albums: List<Album> = emptyList(),
        val favourite: Boolean = false,
        /** In the Hidden album. Carried, and must stay carried — see the test. */
        val hidden: Boolean = false,
        /**
         * The asset has edits applied in Photos, with the original still recoverable.
         *
         * Replacing it discards that: `PHAssetChangeRequest` has no way to attach adjustment
         * data to a new asset, so "revert to original" would quietly stop working on a
         * photograph the user has already edited once.
         */
        val hasAdjustments: Boolean = false,
        /**
         * Part of a burst. `burstIdentifier` is read-only on a creation request, so a
         * replacement leaves the burst one frame short with no way to rejoin it.
         */
        val burstIdentifier: String? = null,
    )

    /** What may be carried across, once the preflight has allowed the replacement. */
    data class CarryOver(
        val albumIds: List<String>,
        val favourite: Boolean,
        val hidden: Boolean,
    )

    sealed interface Verdict {
        data class Proceed(val carry: CarryOver) : Verdict

        /** [detail] names what would have been lost, for the Skipped screen. */
        data class Skip(val reason: SkipReason, val detail: String) : Verdict
    }

    /**
     * Decides whether this asset can be replaced without losing anything.
     *
     * Order matters only for which explanation the user sees first, and it is deliberate:
     * an edited photograph is the one a person is most likely to care about, so it is
     * named before a membership they may not remember.
     */
    fun check(state: AssetState): Verdict {
        if (state.hasAdjustments) {
            return Verdict.Skip(
                SkipReason.WOULD_LOSE_STATE,
                "it has edits, and replacing it would lose the original underneath them",
            )
        }
        if (state.burstIdentifier != null) {
            return Verdict.Skip(
                SkipReason.WOULD_LOSE_STATE,
                "it is part of a burst, and replacing it would take it out of the set",
            )
        }
        state.albums.firstOrNull { it.kind == AlbumKind.SHARED }?.let {
            return Verdict.Skip(
                SkipReason.WOULD_LOSE_STATE,
                "it is in a shared album, which Trim cannot add the new version to",
            )
        }
        state.albums.firstOrNull { it.kind == AlbumKind.OPAQUE_SMART }?.let {
            return Verdict.Skip(
                SkipReason.WOULD_LOSE_STATE,
                "it is in a smart album Trim cannot put the new version back into",
            )
        }

        return Verdict.Proceed(
            CarryOver(
                // Only the ones a change request can actually add to. The derived smart
                // albums are left out on purpose: adding to them is impossible and
                // unnecessary, because the replacement re-derives into them.
                albumIds = state.albums.filter { it.kind == AlbumKind.USER }.map { it.id },
                favourite = state.favourite,
                hidden = state.hidden,
            ),
        )
    }

    /** True when this asset can be replaced at all. */
    fun mayReplace(state: AssetState): Boolean = check(state) is Verdict.Proceed
}
