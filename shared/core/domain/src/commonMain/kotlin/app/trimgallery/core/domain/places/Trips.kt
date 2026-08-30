package app.trimgallery.core.domain.places

import app.trimgallery.core.model.GeoPoint
import app.trimgallery.core.model.MediaItem
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Where the user usually is, and the times they were not (BUILD.md § 9 v1.1, Memories).
 *
 * "A weekend in Lisbon" is the memory people actually want, and it cannot be found without
 * knowing where home is — the same photographs, taken by someone who lives in Lisbon, are
 * not a trip.
 *
 * ### What home is, and what it is not
 *
 * Home is derived here from nothing but the coordinates already in the user's own
 * photographs, on the device, and it is used for exactly two things: deciding which
 * photographs are away, and labelling a map. It is never stored anywhere it could leave, is
 * never in the diagnostics export (`Diagnostics` excludes locations outright), and there is
 * no reverse geocoder to turn it into an address because this app has no network and never
 * will (PRD.md R8). It is the single most sensitive thing the app derives, and the reason
 * it can be derived at all is that nothing it touches can leave the phone.
 */
object Trips {

    /**
     * How far from home a photograph has to be taken to count as away.
     *
     * Fifty kilometres: beyond a commute and beyond the nearest big city for most people,
     * so a day at work or a trip to the shops is not a memory. Below it, "away" would fire
     * on the ordinary week and the feature would be noise.
     */
    const val AWAY_METERS = 50_000.0

    /** A place has to hold this share of the located library before it is called home. */
    const val HOME_SHARE = 0.25

    /**
     * How much bigger than the runner-up the largest place has to be.
     *
     * A share test on its own is not enough: five places holding a fifth of the library each
     * all clear any threshold low enough to be useful, and the largest of them wins by
     * rounding. Requiring it to be twice the next one is what distinguishes *home* from
     * *the busiest of several places*.
     *
     * Someone who genuinely splits their life between two places has no home by this rule,
     * and the app then offers no trips. That is the right outcome: measuring "away" against
     * one of two homes would call half their ordinary life a holiday.
     */
    const val HOME_DOMINANCE = 2.0

    /** A trip needs at least this many photographs, or it is one stop on a motorway. */
    const val MIN_TRIP_PHOTOS = 5

    /**
     * A gap this long between photographs ends a trip.
     *
     * Three days rather than two, chosen by which mistake the user sees. Splitting one
     * holiday into three memories looks broken — travel days, a conference, a phone left in
     * a bag all produce gaps inside a single trip. Merging two separate trips three days
     * apart is both rarer and, when it happens, indistinguishable from a longer trip that
     * genuinely was one.
     */
    val TRIP_GAP: Duration = 3.days

    /** The shortest span that reads as a trip rather than an afternoon. */
    val MIN_TRIP_SPAN: Duration = Duration.ZERO

    /**
     * Where the library says the user usually is.
     *
     * The largest cluster at city scale, and only if it both holds a real share of the
     * library and is clearly bigger than the next place. Without both tests, someone whose
     * photographs are spread evenly over a country gets a "home" that is merely the busiest
     * of many, and every trip is then measured against a place they barely visit.
     *
     * Null means the library cannot say, and the honest consequence is that there are no
     * trips: a "trip" against an unknown home is just a photograph with a coordinate.
     */
    fun home(items: List<MediaItem>): GeoPoint? {
        val located = items.filter { it.location != null && !it.hidden }
        if (located.isEmpty()) return null

        val clusters = PlaceClustering.clusterByDistance(located, radiusMeters = HOME_RADIUS_METERS)
            .sortedByDescending { it.count }
        val largest = clusters.firstOrNull() ?: return null

        val share = largest.count.toDouble() / located.size
        if (share < HOME_SHARE) return null

        val runnerUp = clusters.getOrNull(1)?.count ?: 0
        if (runnerUp > 0 && largest.count < runnerUp * HOME_DOMINANCE) return null

        return largest.center
    }

    /** One time the user was somewhere else. */
    data class Trip(
        val items: List<MediaItem>,
        val start: Instant,
        val end: Instant,
        val center: GeoPoint,
        val bounds: Geo.Bounds,
        /** How far the trip's centre is from home. Decides the ordering, and the copy. */
        val distanceFromHomeMeters: Double,
    ) {
        val count: Int get() = items.size

        val span: Duration get() = end - start

        /** Whole days, counting both ends: a Friday-to-Sunday trip is three days. */
        val days: Int get() = (span.inWholeDays + 1).toInt()

        /**
         * The photograph the trip is shown as.
         *
         * A favourite first, exactly as a map pin does: the user has already answered the
         * question of which picture from that week is the one.
         */
        val cover: MediaItem?
            get() = items.filter { it.favourite }.minByOrNull { it.takenAt?.toEpochMilliseconds() ?: 0L }
                ?: items.minByOrNull { it.takenAt?.toEpochMilliseconds() ?: 0L }
    }

    /**
     * Every trip the library contains, newest first.
     *
     * A trip is a run of away-from-home photographs with no long gap in it. Split on time
     * rather than on distance, because a fortnight in Italy that moves between four cities
     * is one trip in every way that matters to the person who took it — and two visits to
     * the same city a year apart are two.
     */
    fun trips(items: List<MediaItem>, home: GeoPoint? = home(items)): List<Trip> {
        if (home == null) return emptyList()

        val away = items
            .filter { !it.hidden }
            .filter { it.takenAt != null && it.location != null }
            .filter { Geo.distanceMeters(home, it.location!!) >= AWAY_METERS }
            .sortedBy { it.takenAt!! }

        if (away.isEmpty()) return emptyList()

        val runs = mutableListOf<MutableList<MediaItem>>()
        var current = mutableListOf(away.first())
        for (item in away.drop(1)) {
            val previous = current.last().takenAt!!
            if (item.takenAt!! - previous > TRIP_GAP) {
                runs += current
                current = mutableListOf()
            }
            current += item
        }
        runs += current

        return runs
            .filter { it.size >= MIN_TRIP_PHOTOS }
            .mapNotNull { run -> toTrip(run, home) }
            .filter { it.span >= MIN_TRIP_SPAN }
            .sortedByDescending { it.end }
    }

    private fun toTrip(run: List<MediaItem>, home: GeoPoint): Trip? {
        val points = run.mapNotNull { it.location }
        val center = Geo.centroid(points) ?: return null
        val bounds = Geo.bounds(points) ?: return null
        return Trip(
            items = run,
            start = run.first().takenAt ?: return null,
            end = run.last().takenAt ?: return null,
            center = center,
            bounds = bounds,
            distanceFromHomeMeters = Geo.distanceMeters(home, center),
        )
    }

    /**
     * How far away a trip was, in the user's words.
     *
     * Rounded hard, and never to a precision the source can support: a GPS fix in a
     * photograph is good to a few metres at best and to a few hundred indoors, and
     * "1,247 km away" claims an accuracy the number has not got.
     */
    fun describeDistance(meters: Double): String = when {
        meters < 1_000 -> "nearby"
        meters < 100_000 -> "${(meters / 1_000).toInt()} km away"
        else -> "${(meters / 100_000).toInt() * 100} km away"
    }

    /**
     * Home is found at city scale, not at the 250 m a place uses.
     *
     * A person's home photographs are spread over their neighbourhood, their street and
     * wherever they walk the dog; at 250 m that is a dozen places, none of which holds
     * enough of the library to be recognised as home.
     */
    private const val HOME_RADIUS_METERS = 15_000.0
}
