package app.trimgallery.feature.gallery

/**
 * The handles an emulator test uses to find the grid, a tile and the viewer.
 *
 * In production code rather than in the test, deliberately. A test that finds a tile by
 * its position in the tree, or by the text inside it, breaks when the layout is rearranged
 * or the wording changes — and a UI test that breaks for reasons unrelated to the bug it
 * guards is a UI test that gets deleted. A tag is a name the screen agrees to answer to.
 *
 * Tags carry no behaviour and nothing draws them; they add a semantics property and
 * nothing else. Keyed by the item's id rather than by index so a test can say *which*
 * photograph it means — "the video" and "the third tile" are different questions, and only
 * the first one stays true when the sort order changes.
 */
object GalleryTestTags {

    /** The grid itself, so a test can say "the photographs are on screen" in one node. */
    const val GRID = "gallery-grid"

    /** The opened item's frame — the shared element the grid animates into. */
    const val VIEWER = "gallery-viewer"

    /** One tile, by the id of the item it draws. */
    fun tile(id: String): String = "gallery-tile-$id"
}
