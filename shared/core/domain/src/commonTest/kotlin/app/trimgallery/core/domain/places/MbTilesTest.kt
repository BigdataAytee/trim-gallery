package app.trimgallery.core.domain.places

import app.trimgallery.core.model.GeoPoint
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MbTilesTest {

    private val london = GeoPoint(51.5074, -0.1278)

    private class FakeRows(
        private val have: Set<Triple<Int, Int, Int>> = emptySet(),
        private val meta: Map<String, String> = emptyMap(),
        private val count: Long = 1,
    ) : MbTiles.Rows {
        val asked = mutableListOf<Triple<Int, Int, Int>>()
        override suspend fun tileData(zoom: Int, column: Int, tmsRow: Int): ByteArray? {
            asked += Triple(zoom, column, tmsRow)
            return if (Triple(zoom, column, tmsRow) in have) ByteArray(4) else null
        }
        override suspend fun metadata(): Map<String, String> = meta
        override suspend fun tileCount(): Long = count
    }

    // -------------------------------------------------------------- the flip

    /**
     * The trap this class exists for. MBTiles counts rows from the bottom of the world and
     * slippy tiles count from the top; get it wrong and the map is not blank, it renders
     * upside down by hemisphere — the sort of bug that survives a demo.
     */
    @Test
    fun `the row is flipped from slippy to TMS`() {
        assertEquals(0, MbTiles.tmsRow(Geo.Tile(0, 0, 0)))
        assertEquals(1, MbTiles.tmsRow(Geo.Tile(1, 0, 0)))
        assertEquals(0, MbTiles.tmsRow(Geo.Tile(1, 0, 1)))
        assertEquals(2730, MbTiles.tmsRow(Geo.Tile(12, 2045, 1365)))
    }

    @Test
    fun `the flip is its own inverse`() {
        for (zoom in 0..14) {
            val scale = 1 shl zoom
            for (y in listOf(0, scale / 3, scale - 1)) {
                assertEquals(y, MbTiles.xyzRow(zoom, MbTiles.tmsRow(Geo.Tile(zoom, 0, y))), "zoom $zoom y $y")
            }
        }
    }

    /** A tile in the northern hemisphere must not fetch one from the southern. */
    @Test
    fun `a northern tile is not read from the southern row`() = runTest {
        val tile = Geo.tileOf(london, 6)
        val rows = FakeRows()
        MbTiles.Source(rows, MbTiles.parse(emptyMap())).tile(tile)
        val (_, _, askedRow) = rows.asked.single()
        assertEquals(MbTiles.tmsRow(tile), askedRow)
        assertTrue(askedRow != tile.y, "the fixture is not exercising the flip")
    }

    // ------------------------------------------------------------- metadata

    @Test
    fun `a normal pack's metadata is read`() {
        val parsed = MbTiles.parse(
            mapOf(
                "name" to "Britain raster",
                "format" to "png",
                "minzoom" to "4",
                "maxzoom" to "12",
                "bounds" to "-8.0,49.0,2.0,61.0",
            ),
        )
        assertEquals("Britain raster", parsed.name)
        assertEquals(MbTiles.Format.PNG, parsed.format)
        assertEquals(4..12, parsed.zoomRange)
        assertEquals(Geo.Bounds(south = 49.0, west = -8.0, north = 61.0, east = 2.0), parsed.bounds)
    }

    @Test
    fun `keys and values are read whatever their case and spacing`() {
        val parsed = MbTiles.parse(mapOf("Format" to " PNG ", " MinZoom" to " 3 "))
        assertEquals(MbTiles.Format.PNG, parsed.format)
        assertEquals(3, parsed.minZoom)
    }

    @Test
    fun `jpeg and mvt are recognised by their other names`() {
        assertEquals(MbTiles.Format.JPG, MbTiles.Format.of("jpeg"))
        assertEquals(MbTiles.Format.PBF, MbTiles.Format.of("mvt"))
        assertNull(MbTiles.Format.of("something-else"))
        assertNull(MbTiles.Format.of(null))
    }

    /**
     * Asking for zoom 19 of a pack that stops at 12 returns nothing for every tile on
     * screen, which looks identical to a broken map.
     */
    @Test
    fun `a pack that does not state its zooms gets a conservative range`() {
        val parsed = MbTiles.parse(emptyMap())
        assertEquals(MbTiles.DEFAULT_MIN_ZOOM..MbTiles.DEFAULT_MAX_ZOOM, parsed.zoomRange)
        assertTrue(parsed.zoomRange.last < Geo.MAX_ZOOM)
    }

    @Test
    fun `a pack with its zooms the wrong way round does not produce an empty range`() {
        val parsed = MbTiles.parse(mapOf("minzoom" to "10", "maxzoom" to "4"))
        assertFalse(parsed.zoomRange.isEmpty())
    }

    /** A malformed cosmetic field must not cost the user a pack whose tiles are fine. */
    @Test
    fun `bounds that do not parse are dropped, not fatal`() {
        for (bad in listOf("nonsense", "1,2,3", "1,2,3,four", "2.0,61.0,-8.0,49.0", null)) {
            assertNull(MbTiles.parseBounds(bad), "$bad")
        }
        assertEquals(MbTiles.Format.PNG, MbTiles.parse(mapOf("format" to "png", "bounds" to "junk")).format)
    }

    // --------------------------------------------------------------- opening

    @Test
    fun `a raster pack with tiles opens`() {
        val opened = MbTiles.open(mapOf("format" to "png", "minzoom" to "4", "maxzoom" to "12"), tileCount = 5_000)
        assertEquals(4..12, assertIs<MbTiles.Opened.Ready>(opened).metadata.zoomRange)
    }

    /**
     * A vector pack is a valid MBTiles file this app cannot draw. Saying so beats a map that
     * silently stays empty after the user went and found a file.
     */
    @Test
    fun `a vector pack is refused with a reason`() {
        val opened = MbTiles.open(mapOf("format" to "pbf"), tileCount = 5_000)
        val refused = assertIs<MbTiles.Opened.Refused>(opened)
        assertEquals(MbTiles.Rejection.VECTOR_TILES, refused.rejection)
        assertTrue(refused.rejection.explanation.contains("raster"), refused.rejection.explanation)
    }

    @Test
    fun `an empty pack is refused before the map goes looking`() {
        assertEquals(
            MbTiles.Rejection.NO_TILES,
            assertIs<MbTiles.Opened.Refused>(MbTiles.open(mapOf("format" to "png"), tileCount = 0)).rejection,
        )
    }

    @Test
    fun `a file that is not a pack at all is refused`() {
        assertEquals(
            MbTiles.Rejection.NOT_A_PACK,
            assertIs<MbTiles.Opened.Refused>(MbTiles.open(emptyMap(), tileCount = 0)).rejection,
        )
    }

    @Test
    fun `every rejection has something to say to the user`() {
        for (rejection in MbTiles.Rejection.entries) {
            assertTrue(rejection.explanation.isNotBlank(), "$rejection")
            assertTrue(rejection.explanation.trim().endsWith("."), rejection.explanation)
        }
    }

    // ---------------------------------------------------------------- source

    @Test
    fun `a tile inside the pack comes back`() = runTest {
        val tile = Geo.tileOf(london, 8)
        val rows = FakeRows(have = setOf(Triple(8, tile.x, MbTiles.tmsRow(tile))))
        val source = MbTiles.Source(rows, MbTiles.parse(mapOf("minzoom" to "4", "maxzoom" to "12")))
        assertTrue(source.tile(tile) != null)
        assertTrue(source.available)
    }

    @Test
    fun `a zoom the pack does not hold is not even queried`() = runTest {
        val rows = FakeRows()
        val source = MbTiles.Source(rows, MbTiles.parse(mapOf("minzoom" to "4", "maxzoom" to "12")))
        assertNull(source.tile(Geo.tileOf(london, 18)))
        assertTrue(rows.asked.isEmpty(), "a query was made for a zoom the pack does not hold")
    }

    /** A user whose photographs span two continents hits this on their first pan. */
    @Test
    fun `a tile outside the pack's bounds is not queried`() = runTest {
        val rows = FakeRows()
        val britain = MbTiles.parse(mapOf("minzoom" to "0", "maxzoom" to "14", "bounds" to "-8.0,49.0,2.0,61.0"))
        val source = MbTiles.Source(rows, britain)

        assertNull(source.tile(Geo.tileOf(GeoPoint(40.7128, -74.0060), 8)))
        assertTrue(rows.asked.isEmpty())

        source.tile(Geo.tileOf(london, 8))
        assertEquals(1, rows.asked.size, "a tile inside the bounds should have been queried")
    }

    @Test
    fun `a pack with no bounds is asked about everywhere`() = runTest {
        val rows = FakeRows()
        val source = MbTiles.Source(rows, MbTiles.parse(mapOf("maxzoom" to "14")))
        source.tile(Geo.tileOf(GeoPoint(-33.87, 151.21), 8))
        assertEquals(1, rows.asked.size)
    }
}
