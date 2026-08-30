package app.trimgallery.core.domain.places

import app.trimgallery.core.model.GeoPoint
import app.trimgallery.core.model.MediaFlags
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.model.MediaRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class TripsTest {

    private val day = 24 * 60 * 60 * 1000L
    private val start = 1_700_000_000_000L

    private val london = GeoPoint(51.5074, -0.1278)
    private val lisbon = GeoPoint(38.7223, -9.1393)
    private val edinburgh = GeoPoint(55.9533, -3.1883)

    private var next = 0

    private fun photo(
        at: GeoPoint?,
        dayOffset: Double,
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
        takenAt = Instant.fromEpochMilliseconds(start + (dayOffset * day).toLong()),
        location = at,
        cameraModel = "Pixel 9",
        flags = MediaFlags(favourite = favourite, hidden = hidden),
        phash = null,
        sha256 = null,
        mtime = 0,
    )

    /** A scattering around a point, so a place is not a single repeated coordinate. */
    private fun around(point: GeoPoint, count: Int, fromDay: Double, spreadKm: Double = 3.0) = (0 until count).map {
        val offset = (it % 5 - 2) * spreadKm / 111.32
        photo(GeoPoint(point.lat + offset, point.lon + offset), fromDay + it * 0.1)
    }

    private fun home(count: Int = 60) = around(london, count, fromDay = 0.0)

    // ------------------------------------------------------------------ home

    @Test
    fun `home is where most of the library was taken`() {
        val library = home() + around(lisbon, 20, fromDay = 100.0)
        val found = Trips.home(library)!!
        assertTrue(Geo.distanceMeters(london, found) < 20_000, "home came out at $found")
    }

    /**
     * A share test alone is not enough: five places holding a fifth of the library each all
     * clear any threshold low enough to be useful, and the largest wins by rounding. A
     * "home" like that would make every trip a measurement against a place the user barely
     * visits.
     */
    @Test
    fun `a library with no centre of gravity has no home`() {
        val scattered = listOf(london, lisbon, edinburgh, GeoPoint(41.9028, 12.4964), GeoPoint(52.52, 13.405))
            .flatMapIndexed { i, place -> around(place, 6, fromDay = i * 30.0) }
        assertNull(Trips.home(scattered))
    }

    /**
     * Someone who genuinely splits their life between two places has no home by this rule,
     * and the app then offers no trips — which is right: measuring "away" against one of two
     * homes would call half their ordinary life a holiday.
     */
    @Test
    fun `two lives in two cities means neither is home`() {
        val split = around(london, 30, fromDay = 0.0) + around(edinburgh, 28, fromDay = 5.0)
        assertNull(Trips.home(split))
    }

    /** But a clear favourite among several places still is. */
    @Test
    fun `a dominant place is home even with others around it`() {
        val library = around(london, 60, fromDay = 0.0) +
            around(edinburgh, 12, fromDay = 100.0) +
            around(lisbon, 10, fromDay = 200.0)
        assertTrue(Geo.distanceMeters(london, Trips.home(library)!!) < 20_000)
    }

    @Test
    fun `a library with no locations has no home`() {
        assertNull(Trips.home(listOf(photo(null, 0.0), photo(null, 1.0))))
        assertNull(Trips.home(emptyList()))
    }

    // ----------------------------------------------------------------- trips

    @Test
    fun `a week away is a trip`() {
        val library = home() + around(lisbon, 30, fromDay = 100.0)
        val trips = Trips.trips(library)
        assertEquals(1, trips.size)
        val trip = trips.single()
        assertEquals(30, trip.count)
        assertTrue(Geo.distanceMeters(lisbon, trip.center) < 20_000)
        assertTrue(trip.distanceFromHomeMeters > 1_000_000, "Lisbon is not ${trip.distanceFromHomeMeters} m away")
    }

    /** The ordinary week is not a memory. */
    @Test
    fun `days at home are not trips`() {
        assertTrue(Trips.trips(home(120)).isEmpty())
    }

    /** A day trip within the region is a commute, not a holiday. */
    @Test
    fun `somewhere close by is not away`() {
        val nearby = GeoPoint(london.lat + 0.2, london.lon)
        val library = home() + around(nearby, 20, fromDay = 50.0)
        assertTrue(Trips.trips(library).isEmpty())
    }

    /**
     * A fortnight in Italy that moves between four cities is one trip to the person who took
     * it. Splitting on distance would make it four.
     */
    @Test
    fun `one holiday across several cities is one trip`() {
        val library = home() +
            around(lisbon, 10, fromDay = 100.0) +
            around(GeoPoint(41.1579, -8.6291), 10, fromDay = 102.0) +
            around(GeoPoint(37.0194, -7.9304), 10, fromDay = 104.0)
        val trips = Trips.trips(library)
        assertEquals(1, trips.size)
        assertEquals(30, trips.single().count)
    }

    /** And two visits to the same city a year apart are two. */
    @Test
    fun `going back a year later is a second trip`() {
        val library = home() + around(lisbon, 10, fromDay = 100.0) + around(lisbon, 10, fromDay = 465.0)
        assertEquals(2, Trips.trips(library).size)
    }

    @Test
    fun `a single photograph from a motorway service station is not a trip`() {
        val library = home() + listOf(photo(edinburgh, 100.0), photo(edinburgh, 100.1))
        assertTrue(Trips.trips(library).isEmpty())
    }

    @Test
    fun `trips come back newest first`() {
        val library = home() +
            around(lisbon, 8, fromDay = 100.0) +
            around(edinburgh, 8, fromDay = 300.0)
        val trips = Trips.trips(library)
        assertEquals(2, trips.size)
        assertTrue(trips[0].end > trips[1].end)
    }

    @Test
    fun `the locked folder is not in a trip`() {
        val library = home() + around(lisbon, 8, fromDay = 100.0) +
            listOf(photo(lisbon, 100.5, hidden = true), photo(lisbon, 100.6, hidden = true))
        assertTrue(Trips.trips(library).single().items.none { it.hidden })
    }

    /** A trip against an unknown home is just a photograph with a coordinate. */
    @Test
    fun `no home means no trips`() {
        val library = around(lisbon, 20, fromDay = 0.0)
        assertTrue(Trips.trips(library, home = null).isEmpty())
    }

    // --------------------------------------------------------------- details

    @Test
    fun `a trip spans the days it covers, counting both ends`() {
        val library = home() + (0 until 10).map { photo(lisbon, 100.0 + it * 0.25) }
        val trip = Trips.trips(library).single()
        assertEquals(3, trip.days, "a Friday-to-Sunday trip is three days")
        assertTrue(trip.span >= 2.days)
        assertTrue(trip.span < 3.days)
    }

    @Test
    fun `a favourite is the trip's picture`() {
        val library = home() + around(lisbon, 8, fromDay = 100.0) +
            listOf(photo(lisbon, 100.9, favourite = true, id = "loved"))
        assertEquals("loved", Trips.trips(library).single().cover?.id)
    }

    @Test
    fun `without a favourite the trip opens on its first picture`() {
        val library = home() + (0 until 8).map { photo(lisbon, 100.0 + it * 0.1, id = "day$it") }
        assertEquals("day0", Trips.trips(library).single().cover?.id)
    }

    /**
     * Which mistake the user sees decides the gap. Splitting one holiday into three memories
     * looks broken; merging two trips three days apart is rarer and reads as one longer trip.
     */
    @Test
    fun `a gap of a couple of days ends a trip`() {
        val library = home() +
            (0 until 6).map { photo(lisbon, 100.0 + it * 0.1) } +
            (0 until 6).map { photo(lisbon, 110.0 + it * 0.1) }
        assertEquals(2, Trips.trips(library).size)
    }

    @Test
    fun `photographs hours apart stay in one trip`() {
        val sixHours = 6.hours.inWholeMilliseconds / day.toDouble()
        val library = home() + (0 until 8).map { photo(lisbon, 100.0 + it * sixHours) }
        assertEquals(1, Trips.trips(library).size)
    }

    // ---------------------------------------------------------------- wording

    /**
     * A GPS fix in a photograph is good to a few metres at best. "1,247 km away" claims an
     * accuracy the number has not got.
     */
    @Test
    fun `distance is described no more precisely than it is known`() {
        assertEquals("nearby", Trips.describeDistance(400.0))
        assertEquals("40 km away", Trips.describeDistance(40_500.0))
        assertEquals("1200 km away", Trips.describeDistance(1_247_000.0))
    }
}
