package app.trimgallery.core.pipeline.replace

import app.trimgallery.core.model.SkipReason
import app.trimgallery.core.pipeline.replace.ReplacePreflight.Album
import app.trimgallery.core.pipeline.replace.ReplacePreflight.AlbumKind
import app.trimgallery.core.pipeline.replace.ReplacePreflight.AssetState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ReplacePreflightTest {

    private fun proceed(state: AssetState) =
        assertIs<ReplacePreflight.Verdict.Proceed>(ReplacePreflight.check(state)).carry

    private fun skip(state: AssetState) =
        assertIs<ReplacePreflight.Verdict.Skip>(ReplacePreflight.check(state))

    // ------------------------------------------------------------ carry-over

    @Test
    fun `an ordinary asset carries its albums, favourite and hidden across`() {
        val carry = proceed(
            AssetState(
                albums = listOf(Album("holiday", AlbumKind.USER), Album("family", AlbumKind.USER)),
                favourite = true,
                hidden = false,
            ),
        )
        assertEquals(listOf("holiday", "family"), carry.albumIds)
        assertTrue(carry.favourite)
        assertFalse(carry.hidden)
    }

    /**
     * The locked folder is `hidden`, and a replacement that dropped it would put a
     * photograph the user deliberately hid back into the main grid — where somebody else
     * might be looking.
     */
    @Test
    fun `hidden is carried, because losing it un-hides a photograph`() {
        assertTrue(proceed(AssetState(hidden = true)).hidden)
    }

    /**
     * A new asset belongs to no album, so favourite has to be re-applied rather than
     * inherited — and it is also the property `PlaceClustering` and `Memories` use to pick
     * a cover, so losing it changes what the user sees on three other screens.
     */
    @Test
    fun `favourite is carried`() {
        assertTrue(proceed(AssetState(favourite = true)).favourite)
        assertFalse(proceed(AssetState(favourite = false)).favourite)
    }

    /**
     * Derived smart albums cannot be added to and do not need to be: the replacement
     * matches the same predicate the original did, so it lands in them by itself. Including
     * them in the carry-over would mean asking PhotoKit for a change request it returns nil
     * for.
     */
    @Test
    fun `derived smart albums are neither carried nor a reason to skip`() {
        val carry = proceed(
            AssetState(
                albums = listOf(
                    Album("recently-added", AlbumKind.DERIVED_SMART),
                    Album("videos", AlbumKind.DERIVED_SMART),
                    Album("holiday", AlbumKind.USER),
                ),
            ),
        )
        assertEquals(listOf("holiday"), carry.albumIds)
    }

    @Test
    fun `an asset in no album at all is fine`() {
        assertEquals(emptyList(), proceed(AssetState()).albumIds)
        assertTrue(ReplacePreflight.mayReplace(AssetState()))
    }

    // ------------------------------------------------------------ the refusals

    /**
     * The one a person is most likely to care about: an edited photograph still has its
     * original underneath, and `PHAssetChangeRequest` has no way to attach adjustment data
     * to a new asset. Replacing it would quietly stop "revert to original" working.
     */
    @Test
    fun `an edited asset is skipped, not silently flattened`() {
        val skip = skip(AssetState(hasAdjustments = true))
        assertEquals(SkipReason.WOULD_LOSE_STATE, skip.reason)
        assertTrue(skip.detail.contains("edits"), skip.detail)
    }

    @Test
    fun `a burst member is skipped, because burstIdentifier has no setter`() {
        val skip = skip(AssetState(burstIdentifier = "burst-1"))
        assertEquals(SkipReason.WOULD_LOSE_STATE, skip.reason)
        assertTrue(skip.detail.contains("burst"), skip.detail)
    }

    /**
     * Adding to a shared album re-uploads the asset to somebody else's library — a network
     * operation this app cannot perform (PRD.md R8) and a decision that is not ours.
     */
    @Test
    fun `an asset in a shared album is skipped`() {
        val skip = skip(AssetState(albums = listOf(Album("trip-2025", AlbumKind.SHARED))))
        assertEquals(SkipReason.WOULD_LOSE_STATE, skip.reason)
        assertTrue(skip.detail.contains("shared album"), skip.detail)
    }

    @Test
    fun `an asset in a smart album we cannot reproduce is skipped`() {
        val skip = skip(AssetState(albums = listOf(Album("some-smart", AlbumKind.OPAQUE_SMART))))
        assertEquals(SkipReason.WOULD_LOSE_STATE, skip.reason)
        assertTrue(skip.detail.contains("smart album"), skip.detail)
    }

    /** A user album alongside an unreproducible one does not rescue it. */
    @Test
    fun `one unreproducible membership is enough to refuse`() {
        assertFalse(
            ReplacePreflight.mayReplace(
                AssetState(albums = listOf(Album("holiday", AlbumKind.USER), Album("shared", AlbumKind.SHARED))),
            ),
        )
    }

    @Test
    fun `every refusal explains itself in the user's words`() {
        val states = listOf(
            AssetState(hasAdjustments = true),
            AssetState(burstIdentifier = "b"),
            AssetState(albums = listOf(Album("a", AlbumKind.SHARED))),
            AssetState(albums = listOf(Album("a", AlbumKind.OPAQUE_SMART))),
        )
        for (state in states) {
            val skip = skip(state)
            assertTrue(skip.detail.isNotBlank(), "$state")
            assertTrue(skip.detail.first().isLowerCase(), "reads as a clause, not a sentence: ${skip.detail}")
        }
    }

    /**
     * The whole point of a *pre*flight: the file is never touched. Discovering this during
     * the swap would mean either aborting halfway through the user's only copy or finishing
     * and dropping something they will not notice for months.
     */
    @Test
    fun `the check needs nothing but metadata`() {
        // If this ever needs the file itself, the signature will stop compiling — which is
        // the point of taking a value rather than a handle.
        val state = AssetState(albums = listOf(Album("holiday", AlbumKind.USER)), favourite = true)
        assertTrue(ReplacePreflight.mayReplace(state))
    }
}
