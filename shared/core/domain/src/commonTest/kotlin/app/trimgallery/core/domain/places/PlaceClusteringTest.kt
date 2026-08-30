package app.trimgallery.core.domain.places

import app.trimgallery.core.model.GeoPoint
import app.trimgallery.core.model.MediaFlags
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.model.MediaRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class PlaceClusteringTest {

    private var next = 0

    private fun photo(
        lat: Double? = null,
        lon: Double? = null,
        takenAtMs: Long = 1_700_000_000_000,
        favourite: Boolean = false,
        hidden: Boolean = false,
        id: String = "p${next++}",
    ) = MediaItem(
        id = id,
        platformRef = MediaRef("content://$id"),
        name = "$id.jpg",
        kind = MediaKind.PHOTO,
        codec = "jpeg",
        width = 4000,
        height = 3000,
        fps = null,
        bitrate = null,
        size = 3_000_000,
        duration = null,
        takenAt = Instant.fromEpochMilliseconds(takenAtMs),
        location = if (lat != null && lon != null) GeoPoint(lat, lon) else null,
        cameraModel = "Pixel 9",
        flags = MediaFlags(favourite = favourite, hidden = hidden),
        phash = null,
        sha256 = null,
        mtime = 0,
    )

    /** Roughly [meters] north of a base point. One degree of latitude is about 111 km. */
    private fun north(base: GeoPoint, meters: Double) = GeoPoint(base.lat + meters / 111_320.0, base.lon)

    private val museum = GeoPoint(51.5194, -0.1270)
    private val park = GeoPoint(51.5074, -0.1657)

    @Test
    fun `photographs from the same spot make one place`() {
        val items = (0 until 5).map { photo(museum.lat, museum.lon) }
        val clusters = PlaceClustering.clusterByDistance(items)
        assertEquals(1, clusters.size)
        assertEquals(5, clusters.single().count)
    }

    @Test
    fun `photographs from different parts of a city make different places`() {
        val items = List(3) { photo(museum.lat, museum.lon) } + List(2) { photo(park.lat, park.lon) }
        val clusters = PlaceClustering.clusterByDistance(items)
        assertEquals(2, clusters.size)
        assertEquals(listOf(3, 2), clusters.map { it.count }, "clusters should come back largest first")
    }

    /** GPS drifts indoors; splitting one afternoon into four places would be the result. */
    @Test
    fun `a short walk is still the same place`() {
        val items = listOf(
            photo(museum.lat, museum.lon),
            photo(north(museum, 80.0).lat, north(museum, 80.0).lon),
            photo(north(museum, 160.0).lat, north(museum, 160.0).lon),
        )
        assertEquals(1, PlaceClustering.clusterByDistance(items).size)
    }

    /**
     * The single-linkage failure this guards against: photographs a hundred metres apart in
     * a chain would otherwise drag one cluster across a whole city, and on a map that looks
     * like a single pin swallowing a country.
     */
    @Test
    fun `a chain of nearby photographs does not drag one place across a city`() {
        val chain = (0 until 40).map {
            val point = north(museum, it * 200.0)
            photo(point.lat, point.lon, takenAtMs = 1_700_000_000_000 - it)
        }
        val clusters = PlaceClustering.clusterByDistance(chain, radiusMeters = 250.0)
        assertTrue(clusters.size > 1, "the whole 8 km chain collapsed into ${clusters.size} cluster(s)")
        for (cluster in clusters) {
            assertTrue(cluster.bounds.spanMeters < 2_000, "a cluster spans ${cluster.bounds.spanMeters} m")
        }
    }

    @Test
    fun `the pin sits among its own photographs`() {
        val items = listOf(
            photo(museum.lat, museum.lon),
            photo(north(museum, 100.0).lat, north(museum, 100.0).lon),
        )
        val cluster = PlaceClustering.clusterByDistance(items).single()
        assertTrue(cluster.bounds.contains(cluster.center), "pin at ${cluster.center} outside ${cluster.bounds}")
    }

    // ------------------------------------------------------------ what is in

    @Test
    fun `photographs with no location are not on the map`() {
        val items = listOf(photo(museum.lat, museum.lon), photo(), photo())
        assertEquals(1, PlaceClustering.clusterByDistance(items).single().count)
    }

    /** The locked folder is excluded from every other view, and a map is a view. */
    @Test
    fun `hidden photographs never reach the map`() {
        val items = listOf(
            photo(museum.lat, museum.lon),
            photo(museum.lat, museum.lon, hidden = true),
        )
        assertEquals(1, PlaceClustering.clusterByDistance(items).single().count)
    }

    @Test
    fun `a library with no locations produces no pins`() {
        assertTrue(PlaceClustering.clusterByDistance(listOf(photo(), photo())).isEmpty())
        assertTrue(PlaceClustering.clusterByDistance(emptyList()).isEmpty())
    }

    @Test
    fun `the located fraction says how much of the library the map can show`() {
        val items = listOf(photo(museum.lat, museum.lon), photo(), photo(), photo())
        assertEquals(0.25, PlaceClustering.locatedFraction(items))
        assertEquals(0.0, PlaceClustering.locatedFraction(emptyList()))
    }

    // ---------------------------------------------------------------- covers

    /** A favourite is the user's own statement about which picture is the one. */
    @Test
    fun `a favourite is the pin's picture`() {
        val items = listOf(
            photo(museum.lat, museum.lon, takenAtMs = 3_000, id = "newest"),
            photo(museum.lat, museum.lon, takenAtMs = 1_000, favourite = true, id = "loved"),
        )
        assertEquals("loved", PlaceClustering.clusterByDistance(items).single().cover?.id)
    }

    @Test
    fun `without a favourite the newest picture stands in`() {
        val items = listOf(
            photo(museum.lat, museum.lon, takenAtMs = 1_000, id = "older"),
            photo(museum.lat, museum.lon, takenAtMs = 3_000, id = "newer"),
        )
        assertEquals("newer", PlaceClustering.clusterByDistance(items).single().cover?.id)
    }

    // ------------------------------------------------------------------ zoom

    /**
     * Clustering by ground distance alone would leave a map of Iceland looking half as busy
     * as a map of Kenya, because a degree of longitude is a different distance at each.
     */
    @Test
    fun `zooming in separates pins that were merged`() {
        val items = listOf(
            photo(museum.lat, museum.lon),
            photo(north(museum, 900.0).lat, north(museum, 900.0).lon),
        )
        assertEquals(1, PlaceClustering.clusterForZoom(items, zoom = 10).size)
        assertEquals(2, PlaceClustering.clusterForZoom(items, zoom = 16).size)
    }

    /** The same library at the same zoom must produce the same map, every time. */
    @Test
    fun `clustering is stable`() {
        val items = (0 until 30).map {
            val point = north(museum, it * 130.0)
            photo(point.lat, point.lon, takenAtMs = 1_700_000_000_000 - it)
        }
        val first = PlaceClustering.clusterForZoom(items, zoom = 13).map { it.count }
        repeat(3) {
            assertEquals(first, PlaceClustering.clusterForZoom(items.shuffled(), zoom = 13).map { it.count })
        }
    }

    @Test
    fun `every photograph lands in exactly one cluster`() {
        val items = (0 until 25).map {
            val point = north(museum, it * 400.0)
            photo(point.lat, point.lon)
        }
        val clusters = PlaceClustering.clusterByDistance(items)
        assertEquals(items.size, clusters.sumOf { it.count })
        assertEquals(items.map { it.id }.toSet(), clusters.flatMap { c -> c.items.map { it.id } }.toSet())
    }
}
