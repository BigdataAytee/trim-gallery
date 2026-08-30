package app.trimgallery.core.domain.memories

import app.trimgallery.core.domain.places.Geo
import app.trimgallery.core.domain.places.PlaceClustering
import app.trimgallery.core.domain.places.Trips
import app.trimgallery.core.model.GeoPoint
import app.trimgallery.core.model.MediaItem
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Memories (BUILD.md § 9 v1.1: *"Memories / On this day with music"*).
 *
 * The feature is a photograph the app chose to show somebody without being asked, which
 * makes it the one place in this product where being *wrong* is worse than being absent.
 * Every gallery that has shipped this has hurt someone with it: the ex-partner on an
 * anniversary, the relative who died, the hospital corridor, the flat somebody was evicted
 * from. So the rules about what is **never** in a memory are not an afterthought bolted to
 * the end of the selection — they are [MemoryMutes], they are checked first, and the
 * selection cannot reach round them.
 *
 * The other half is quality. A memory of eleven near-identical frames of the same plate of
 * food is not a memory, and the index already knows enough to avoid it: perceptual hashes
 * for the duplicates, faces for the pictures with people in.
 *
 * **There are no place names anywhere here.** PRD.md R8 forbids the `INTERNET` permission
 * for the life of the product, so there is no geocoder — a place memory is described by
 * *when* and *how far from home*, or by a name the user typed, and never by a guess.
 */
object Memories {

    /** Below this a memory is a handful of pictures, not a story. */
    const val MIN_ITEMS = 4

    /** Above this nobody reaches the end. */
    const val MAX_ITEMS = 30

    /** How many memories the grid offers at once. */
    const val MAX_CARDS = 3

    /** Years back that "on this day" looks. */
    const val ON_THIS_DAY_YEARS = 12

    /** Days either side of the anniversary, so a trip that started on the 3rd still counts. */
    const val ON_THIS_DAY_WINDOW_DAYS = 1

    enum class Kind {
        /** "On this day", the anniversary of something. */
        ON_THIS_DAY,

        /** A trip away from home (`Trips`). */
        TRIP,

        /** One person, across time. Needs face clustering to be switched on. */
        PERSON,

        /** Somewhere the user has been more than once. */
        PLACE,
    }

    /**
     * One memory, ready to show.
     *
     * [title] is deliberately plain. DESIGN_SYSTEM.md's copy tone is short, calm and
     * concrete, and a memory that announces itself as "✨ Your amazing week!" is the tone
     * that makes the feature feel like it is performing at the user rather than showing them
     * something.
     */
    data class Memory(
        val kind: Kind,
        val title: String,
        val subtitle: String?,
        val items: List<MediaItem>,
        val cover: MediaItem?,
        val start: Instant?,
        val end: Instant?,
        val center: GeoPoint? = null,
    ) {
        val count: Int get() = items.size
    }

    /**
     * Everything the user has told the app never to resurface.
     *
     * Held as a value the caller passes in, so there is no path through this object that
     * skips it. Each field is a control a person needs after something has happened to
     * them, and the app cannot know which — so it offers all three and asks for no reason.
     */
    data class MemoryMutes(
        /** People, by cluster id, from "Don't show this person". */
        val personIds: Set<String> = emptySet(),
        /** Whole dates, from "Hide this date" — an anniversary nobody wants marked. */
        val dates: Set<LocalDate> = emptySet(),
        /** Places, as a centre and a radius: a hospital, a house, a street. */
        val places: List<MutedPlace> = emptyList(),
        /** Memories the user dismissed, so a dismissal means something. */
        val dismissedTitles: Set<String> = emptySet(),
    ) {
        val isEmpty: Boolean
            get() = personIds.isEmpty() && dates.isEmpty() && places.isEmpty() && dismissedTitles.isEmpty()
    }

    data class MutedPlace(val center: GeoPoint, val radiusMeters: Double = DEFAULT_MUTE_RADIUS_M)

    /** A kilometre: wide enough to cover a hospital campus without muting a whole district. */
    const val DEFAULT_MUTE_RADIUS_M = 1_000.0

    /**
     * What may be considered for a memory at all.
     *
     * Applied before anything is selected, ranked or grouped, and applied once, here — a
     * later filter is a filter somebody's next change can route around.
     *
     * The locked folder is excluded structurally rather than by mute: hidden items are out
     * of every other view already, and a memory is a view.
     */
    fun eligible(items: List<MediaItem>, mutes: MemoryMutes, zone: TimeZone): List<MediaItem> =
        items.asSequence()
            .filterNot { it.hidden }
            .filter { it.takenAt != null }
            .filterNot { item -> item.takenAt!!.toLocalDateTime(zone).date in mutes.dates }
            .filterNot { item -> item.location?.let { muted(it, mutes) } == true }
            .toList()

    private fun muted(point: GeoPoint, mutes: MemoryMutes): Boolean =
        mutes.places.any { Geo.distanceMeters(it.center, point) <= it.radiusMeters }

    /**
     * The memories to offer today, best first.
     *
     * @param peopleOf which person clusters a photograph belongs to, or empty when face
     *   clustering is switched off — in which case there are simply no person memories.
     *   Computing them anyway and filtering would be the same privacy mistake `IndexStep`
     *   already refuses to make: the way to be sure something never leaves is not to make it.
     */
    fun today(
        items: List<MediaItem>,
        now: Instant,
        zone: TimeZone,
        mutes: MemoryMutes = MemoryMutes(),
        peopleOf: (MediaItem) -> Set<String> = { emptySet() },
        personName: (String) -> String? = { null },
    ): List<Memory> {
        val pool = eligible(items, mutes, zone)
        if (pool.size < MIN_ITEMS) return emptyList()

        val candidates = buildList {
            addAll(onThisDay(pool, now, zone))
            addAll(tripMemories(pool))
            addAll(personMemories(pool, mutes, peopleOf, personName))
            addAll(placeMemories(pool))
        }

        return candidates
            .filterNot { it.title in mutes.dismissedTitles }
            .distinctBy { it.kind to it.title }
            .sortedByDescending { score(it, now) }
            .take(MAX_CARDS)
    }

    /** "On this day", one memory per year that has enough pictures. */
    fun onThisDay(items: List<MediaItem>, now: Instant, zone: TimeZone): List<Memory> {
        val today = now.toLocalDateTime(zone).date
        val thisYear = today.year

        return (1..ON_THIS_DAY_YEARS).mapNotNull { back ->
            val year = thisYear - back
            val onDay = items.filter { item ->
                val date = item.takenAt!!.toLocalDateTime(zone).date
                date.year == year && withinWindow(date, today)
            }
            val chosen = select(onDay)
            if (chosen.size < MIN_ITEMS) {
                null
            } else {
                Memory(
                    kind = Kind.ON_THIS_DAY,
                    title = if (back == 1) "A year ago today" else "$back years ago today",
                    subtitle = "${chosen.size} photos",
                    items = chosen,
                    cover = coverOf(chosen),
                    start = chosen.minOf { it.takenAt!! },
                    end = chosen.maxOf { it.takenAt!! },
                    center = Geo.centroid(chosen.mapNotNull { it.location }),
                )
            }
        }
    }

    /** A day or two either side, so a trip that began on the 3rd still surfaces on the 4th. */
    private fun withinWindow(date: LocalDate, today: LocalDate): Boolean {
        val sameDay = date.month == today.month && date.day == today.day
        if (sameDay) return true
        // Day-of-year arithmetic rather than calendar maths: the window is a day or two and
        // getting it exactly right across a leap year is not worth the branch.
        return kotlin.math.abs(date.dayOfYear - today.dayOfYear) <= ON_THIS_DAY_WINDOW_DAYS
    }

    fun tripMemories(items: List<MediaItem>): List<Memory> =
        Trips.trips(items).mapNotNull { trip ->
            val chosen = select(trip.items)
            if (chosen.size < MIN_ITEMS) {
                null
            } else {
                Memory(
                    kind = Kind.TRIP,
                    // No place name: there is no geocoder, and "Barcelona" would be a guess.
                    title = if (trip.days <= 1) "A day away" else "${trip.days} days away",
                    subtitle = Trips.describeDistance(trip.distanceFromHomeMeters),
                    items = chosen,
                    cover = trip.cover ?: coverOf(chosen),
                    start = trip.start,
                    end = trip.end,
                    center = trip.center,
                )
            }
        }

    private fun personMemories(
        items: List<MediaItem>,
        mutes: MemoryMutes,
        peopleOf: (MediaItem) -> Set<String>,
        personName: (String) -> String?,
    ): List<Memory> {
        val byPerson = mutableMapOf<String, MutableList<MediaItem>>()
        for (item in items) {
            for (person in peopleOf(item)) {
                if (person in mutes.personIds) continue
                byPerson.getOrPut(person) { mutableListOf() } += item
            }
        }

        return byPerson.mapNotNull { (person, theirs) ->
            val chosen = select(theirs)
            // A person the user has not named is not given one. There is no source for a
            // name but the user, and inventing "Person 3" as a memory title is worse than
            // having no memory.
            val name = personName(person) ?: return@mapNotNull null
            if (chosen.size < MIN_ITEMS) {
                null
            } else {
                Memory(
                    kind = Kind.PERSON,
                    title = name,
                    subtitle = "${chosen.size} photos",
                    items = chosen,
                    cover = coverOf(chosen),
                    start = chosen.minOf { it.takenAt!! },
                    end = chosen.maxOf { it.takenAt!! },
                )
            }
        }
    }

    private fun placeMemories(items: List<MediaItem>): List<Memory> =
        PlaceClustering.clusterByDistance(items, radiusMeters = PLACE_MEMORY_RADIUS_M)
            .filter { cluster -> cluster.items.mapNotNull { it.takenAt }.distinct().size >= MIN_ITEMS }
            .mapNotNull { cluster ->
                val chosen = select(cluster.items)
                if (chosen.size < MIN_ITEMS) {
                    null
                } else {
                    Memory(
                        kind = Kind.PLACE,
                        title = "Somewhere you go back to",
                        subtitle = "${chosen.size} photos",
                        items = chosen,
                        cover = cluster.cover ?: coverOf(chosen),
                        start = chosen.minOf { it.takenAt!! },
                        end = chosen.maxOf { it.takenAt!! },
                        center = cluster.center,
                    )
                }
            }

    /**
     * Picks the pictures, and drops the near-duplicates.
     *
     * Eleven frames of the same plate of food is what makes a memory feel automatic. The
     * index already computed the perceptual hashes; using them here is the difference
     * between a sequence somebody watches and one they swipe away.
     *
     * Favourites survive deduplication unconditionally — if the user marked two similar
     * frames, they meant both.
     */
    fun select(items: List<MediaItem>, limit: Int = MAX_ITEMS): List<MediaItem> {
        val ordered = items.sortedBy { it.takenAt?.toEpochMilliseconds() ?: 0L }
        val kept = mutableListOf<MediaItem>()

        for (item in ordered) {
            val hash = item.phash
            val duplicate = hash != null && !item.favourite && kept.any { other ->
                other.phash?.let { PerceptualDistance.near(it, hash) } == true
            }
            if (!duplicate) kept += item
        }

        if (kept.size <= limit) return kept

        // Over the limit, keep the favourites and spread the rest across the span rather
        // than taking the first thirty: a memory of one morning of a week-long trip is a
        // memory of the wrong thing.
        val favourites = kept.filter { it.favourite }
        val rest = kept.filterNot { it.favourite }
        val room = (limit - favourites.size).coerceAtLeast(0)
        val spread = if (rest.isEmpty() || room == 0) {
            emptyList()
        } else {
            val step = (rest.size.toDouble() / room).coerceAtLeast(1.0)
            (0 until room).map { rest[(it * step).toInt().coerceAtMost(rest.size - 1)] }.distinct()
        }
        return (favourites + spread)
            .distinctBy { it.id }
            .sortedBy { it.takenAt?.toEpochMilliseconds() ?: 0L }
            .take(limit)
    }

    /** A favourite, then a picture with a face in it, then the middle of the run. */
    private fun coverOf(items: List<MediaItem>): MediaItem? =
        items.firstOrNull { it.favourite } ?: items.getOrNull(items.size / 2)

    /**
     * Which memory leads.
     *
     * Recency dominates, because a memory of last summer means more than one of a decade
     * ago, and within that a trip beats an anniversary — a trip is a thing that happened,
     * where "on this day" is an accident of the calendar.
     */
    private fun score(memory: Memory, now: Instant): Double {
        val ageDays = memory.end?.let { (now - it).inWholeDays.toDouble() } ?: Double.MAX_VALUE
        val recency = 1.0 / (1.0 + ageDays / 365.0)
        val kindWeight = when (memory.kind) {
            Kind.TRIP -> 1.3
            Kind.ON_THIS_DAY -> 1.0
            Kind.PERSON -> 0.9
            Kind.PLACE -> 0.6
        }
        val size = (memory.count.toDouble() / MAX_ITEMS).coerceAtMost(1.0)
        return recency * kindWeight * (0.5 + 0.5 * size)
    }

    /** A place worth a memory is bigger than a map pin: a park, a beach, a village. */
    private const val PLACE_MEMORY_RADIUS_M = 1_500.0
}

/**
 * Hamming distance on perceptual hashes, duplicated from the pipeline's `PerceptualHash`.
 *
 * `core/domain` does not depend on `core/pipeline` (ARCHITECTURE.md § 3 puts the dependency
 * the other way), and one `countOneBits` is a better answer than inverting a module
 * dependency to share it. The threshold is deliberately tighter than the duplicate finder's:
 * a memory dropping a picture that was merely similar costs nothing, where the cleanup
 * screen suggesting a delete does.
 */
internal object PerceptualDistance {
    const val SAME_MOMENT = 6

    fun near(a: Long, b: Long, threshold: Int = SAME_MOMENT): Boolean =
        (a xor b).countOneBits() <= threshold
}
