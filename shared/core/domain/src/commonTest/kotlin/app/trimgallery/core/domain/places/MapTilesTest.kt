package app.trimgallery.core.domain.places

import app.trimgallery.core.model.GeoPoint
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapTilesTest {

    /**
     * A map with no basemap is a legitimate state, not a failure: the user has not added a
     * pack, or the build ships none. The screen shows their places on a plain background and
     * says so, rather than an empty grey grid that reads as broken.
     */
    @Test
    fun `no basemap is a state the map can render`() = runTest {
        assertFalse(MapTiles.None.available)
        assertTrue(MapTiles.None.zoomRange.isEmpty())
        assertNull(MapTiles.None.tile(Geo.tileOf(GeoPoint(51.5074, -0.1278), 12)))
    }

    /**
     * Null for a tile is ordinary rather than exceptional: a regional pack has nothing
     * outside its region, and a user whose photographs span two continents hits that on
     * their first pan.
     */
    @Test
    fun `a regional pack answers for its region and not beyond`() = runTest {
        val britain = object : MapTiles {
            override val available = true
            override val zoomRange = 4..12
            override suspend fun tile(tile: Geo.Tile): ByteArray? {
                val bounds = Geo.boundsOf(tile)
                val inside = bounds.center.lat in 49.0..61.0 && bounds.center.lon in -8.0..2.0
                return if (inside) ByteArray(1) else null
            }
        }

        assertTrue(britain.tile(Geo.tileOf(GeoPoint(51.5074, -0.1278), 8)) != null)
        assertNull(britain.tile(Geo.tileOf(GeoPoint(40.7128, -74.0060), 8)))
    }
}
