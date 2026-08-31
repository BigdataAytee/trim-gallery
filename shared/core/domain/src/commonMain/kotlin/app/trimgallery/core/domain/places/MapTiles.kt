package app.trimgallery.core.domain.places

/**
 * Where the map's basemap comes from (BUILD.md § 9 v1.1: *"Map view with offline tiles"*).
 *
 * The word that does all the work in that line is **offline**. PRD.md R8 is that this app
 * never gets the `INTERNET` permission — it is displayed as a feature, two build guards
 * enforce it, and the manifest is checked in CI — so there is no version of this screen
 * that fetches a tile. Every pixel of the basemap has to already be on the device before
 * the map is opened.
 *
 * That leaves the source as a decision rather than an implementation detail, which is why
 * this is an interface with no implementation in this repository yet. What is settled is the
 * *shape*: tiles are addressed by the standard slippy-map scheme (`Geo.tileOf`), so a pack
 * produced by any of the usual tools can be read without a library that speaks a protocol.
 *
 * [available] is not decoration. A map with no basemap is a legitimate state — the user has
 * not added a pack, or the build ships none — and the screen has to be able to show the
 * user's own places on a plain background and say so, rather than displaying an empty grey
 * grid that reads as broken.
 */
interface MapTiles {

    /** False when there is no basemap to draw; the map then shows places without one. */
    val available: Boolean

    /** The zoom levels the source actually holds. Zooming past it is a no-op, not a blank. */
    val zoomRange: IntRange

    /**
     * The image bytes for one tile, or null when the source does not have it.
     *
     * Null is ordinary rather than exceptional: a regional pack has nothing outside its
     * region, and a user whose photographs span two continents will hit that on their first
     * pan. The map draws what it has.
     */
    suspend fun tile(tile: Geo.Tile): ByteArray?

    /** A source with nothing in it, so the map has something to bind before a pack exists. */
    object None : MapTiles {
        override val available: Boolean = false
        override val zoomRange: IntRange = IntRange.EMPTY
        override suspend fun tile(tile: Geo.Tile): ByteArray? = null
    }
}
