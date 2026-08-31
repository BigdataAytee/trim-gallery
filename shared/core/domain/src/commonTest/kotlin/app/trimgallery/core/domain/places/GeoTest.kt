package app.trimgallery.core.domain.places

import app.trimgallery.core.model.GeoPoint
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeoTest {

    private val london = GeoPoint(51.5074, -0.1278)
    private val paris = GeoPoint(48.8566, 2.3522)
    private val newYork = GeoPoint(40.7128, -74.0060)

    private fun close(expected: Double, actual: Double, tolerance: Double) {
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected ± $tolerance, was $actual")
    }

    // ------------------------------------------------------------- distance

    /** London to Paris is about 344 km; the tolerance is a kilometre. */
    @Test
    fun `distance matches known city pairs`() {
        close(343_500.0, Geo.distanceMeters(london, paris), 1_500.0)
        close(5_570_000.0, Geo.distanceMeters(london, newYork), 15_000.0)
    }

    @Test
    fun `distance is symmetric and zero to itself`() {
        assertEquals(0.0, Geo.distanceMeters(london, london))
        close(
            Geo.distanceMeters(london, paris),
            Geo.distanceMeters(paris, london),
            1e-6,
        )
    }

    /** Two photographs a few metres apart is the common case, not the long haul. */
    @Test
    fun `short distances are accurate`() {
        val a = GeoPoint(51.5074, -0.1278)
        val b = GeoPoint(51.50749, -0.1278)
        close(10.0, Geo.distanceMeters(a, b), 0.5)
    }

    @Test
    fun `antipodes are half the way round`() {
        close(20_015_000.0, Geo.distanceMeters(GeoPoint(0.0, 0.0), GeoPoint(0.0, 180.0)), 5_000.0)
    }

    // -------------------------------------------------------------- centroid

    /**
     * Averaging longitudes puts the centre of two photographs either side of the date line
     * in the middle of Africa. Anyone who has been to Fiji would see the pin.
     */
    @Test
    fun `the centroid crosses the date line correctly`() {
        val center = Geo.centroid(listOf(GeoPoint(0.0, 179.0), GeoPoint(0.0, -179.0)))!!
        assertTrue(abs(center.lon) > 179.0, "centre landed at ${center.lon}, not near the date line")
        close(0.0, center.lat, 1e-6)
    }

    @Test
    fun `the centroid of one point is that point`() {
        val center = Geo.centroid(listOf(london))!!
        close(london.lat, center.lat, 1e-9)
        close(london.lon, center.lon, 1e-9)
    }

    @Test
    fun `the centroid of nothing is nothing`() {
        assertNull(Geo.centroid(emptyList()))
    }

    /** A ring around the globe has no meaningful centre; somewhere the user has been beats (0,0). */
    @Test
    fun `points that cancel out fall back to a real one`() {
        val ring = listOf(
            GeoPoint(0.0, 0.0),
            GeoPoint(0.0, 90.0),
            GeoPoint(0.0, 180.0),
            GeoPoint(0.0, -90.0),
        )
        val center = Geo.centroid(ring)!!
        assertTrue(ring.contains(center), "fell back to $center, which is nowhere")
    }

    // ---------------------------------------------------------------- bounds

    @Test
    fun `bounds contain every point`() {
        val points = listOf(london, paris, newYork)
        val bounds = Geo.bounds(points)!!
        points.forEach { assertTrue(bounds.contains(it), "$it outside $bounds") }
    }

    /**
     * The map has to tell "no photographs have a location" from "every photograph was taken
     * in the Gulf of Guinea", and (0, 0) is where a stripped coordinate lands.
     */
    @Test
    fun `no points is no bounds, not a box at the origin`() {
        assertNull(Geo.bounds(emptyList()))
    }

    @Test
    fun `padding grows the box and stays on the planet`() {
        val padded = Geo.Bounds(-89.0, -179.0, 89.0, 179.0).padded(0.5)
        assertTrue(padded.south >= -90.0)
        assertTrue(padded.north <= 90.0)
        assertTrue(padded.west >= -180.0)
        assertTrue(padded.east <= 180.0)
    }

    // ----------------------------------------------------------------- tiles

    @Test
    fun `zoom zero is one tile that holds everything`() {
        for (point in listOf(london, newYork, GeoPoint(-33.87, 151.21))) {
            assertEquals(Geo.Tile(0, 0, 0), Geo.tileOf(point, 0))
        }
    }

    @Test
    fun `a point falls inside the tile it is assigned to`() {
        for (zoom in listOf(1, 5, 10, 14, 18)) {
            for (point in listOf(london, paris, newYork, GeoPoint(-33.87, 151.21))) {
                val bounds = Geo.boundsOf(Geo.tileOf(point, zoom))
                assertTrue(
                    point.lat in bounds.south..bounds.north && point.lon in bounds.west..bounds.east,
                    "$point at zoom $zoom is outside its own tile $bounds",
                )
            }
        }
    }

    @Test
    fun `tile indices stay in range at every zoom`() {
        for (zoom in 0..Geo.MAX_ZOOM) {
            val scale = 1 shl zoom
            for (point in listOf(GeoPoint(89.9, 179.9), GeoPoint(-89.9, -179.9), GeoPoint(0.0, 0.0))) {
                val tile = Geo.tileOf(point, zoom)
                assertTrue(tile.x in 0 until scale, "x ${tile.x} out of range at zoom $zoom")
                assertTrue(tile.y in 0 until scale, "y ${tile.y} out of range at zoom $zoom")
            }
        }
    }

    /**
     * Photographs are taken past 85° — research stations, flights over the pole — and Web
     * Mercator runs to infinity there. A crash reachable from a user's own library is not a
     * theoretical concern.
     */
    @Test
    fun `a photograph from the far north does not break the projection`() {
        val tile = Geo.tileOf(GeoPoint(89.99, 10.0), 12)
        assertTrue(tile.y in 0 until (1 shl 12))
        assertTrue(Geo.metersPerPixel(90.0, 12) > 0.0)
    }

    // ------------------------------------------------------- metres per pixel

    @Test
    fun `each zoom level halves the ground a pixel covers`() {
        val atTen = Geo.metersPerPixel(0.0, 10)
        val atEleven = Geo.metersPerPixel(0.0, 11)
        close(atTen / 2, atEleven, atTen * 1e-9)
    }

    /** A degree of longitude is a different distance in Iceland and in Kenya. */
    @Test
    fun `a pixel covers less ground away from the equator`() {
        assertTrue(Geo.metersPerPixel(64.0, 12) < Geo.metersPerPixel(0.0, 12))
    }

    /**
     * 156543.03 m/px is the standard figure, and it comes from the WGS84 equatorial radius
     * — the sphere Web Mercator is defined on — not from the mean radius haversine uses.
     * The two constants are not interchangeable: the mean one here would put the scale bar
     * a tenth of a percent out and disagree with the tiles it is drawn over.
     */
    @Test
    fun `a pixel at the equator at zoom zero is the standard 156543 metres`() {
        close(156_543.03, Geo.metersPerPixel(0.0, 0), 0.1)
    }

    @Test
    fun `distance and tile scale use different radii on purpose`() {
        assertTrue(Geo.MERCATOR_RADIUS_M > Geo.EARTH_RADIUS_M)
    }
}
