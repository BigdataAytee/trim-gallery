package app.trimgallery.core.domain.memories

import app.trimgallery.core.model.GeoPoint
import app.trimgallery.core.model.MediaFlags
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.model.MediaRef
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class MemoriesTest {

    private val utc = TimeZone.UTC

    /** 2026-08-30T12:00Z, the date this milestone was written. */
    private val now = Instant.parse("2026-08-30T12:00:00Z")

    private val london = GeoPoint(51.5074, -0.1278)
    private val lisbon = GeoPoint(38.7223, -9.1393)
    private val hospital = GeoPoint(51.4980, -0.1195)

    private var next = 0

    private fun photo(
        iso: String,
        at: GeoPoint? = null,
        favourite: Boolean = false,
        hidden: Boolean = false,
        phash: Long? = null,
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
        takenAt = Instant.parse(iso),
        location = at,
        cameraModel = "Pixel 9",
        flags = MediaFlags(favourite = favourite, hidden = hidden),
        phash = phash,
        sha256 = null,
        mtime = 0,
    )

    /**
     * A perceptual hash for a picture of something else.
     *
     * Golden-ratio mixing, because two real photographs of different scenes differ in
     * roughly half their bits, and a naive `index shl n` produces hashes one bit apart —
     * which the near-duplicate rule correctly collapses, making the fixture measure the
     * fixture rather than the code.
     */
    private fun distinctHash(index: Int): Long = index.toLong() * -0x61c8864680b583ebL

    /** [count] photographs on one day, an hour apart. */
    private fun day(date: String, count: Int, at: GeoPoint? = null) =
        (0 until count).map { photo("${date}T${(9 + it).toString().padStart(2, '0')}:00:00Z", at) }

    // ------------------------------------------------------- on this day

    @Test
    fun `an anniversary with enough pictures becomes a memory`() {
        val memories = Memories.onThisDay(day("2025-08-30", 8), now, utc)
        assertEquals(1, memories.size)
        assertEquals("A year ago today", memories.single().title)
        assertEquals(Memories.Kind.ON_THIS_DAY, memories.single().kind)
    }

    @Test
    fun `several years each get their own memory`() {
        val library = day("2025-08-30", 6) + day("2023-08-30", 6)
        val titles = Memories.onThisDay(library, now, utc).map { it.title }
        assertEquals(listOf("A year ago today", "3 years ago today"), titles)
    }

    /** A handful of pictures is not a story. */
    @Test
    fun `too few pictures is not a memory`() {
        assertTrue(Memories.onThisDay(day("2025-08-30", Memories.MIN_ITEMS - 1), now, utc).isEmpty())
    }

    /** A trip that began on the 3rd should still surface on the 4th. */
    @Test
    fun `a day either side of the anniversary counts`() {
        assertEquals(1, Memories.onThisDay(day("2025-08-29", 6), now, utc).size)
        assertEquals(1, Memories.onThisDay(day("2025-08-31", 6), now, utc).size)
        assertTrue(Memories.onThisDay(day("2025-08-25", 6), now, utc).isEmpty())
    }

    @Test
    fun `today's own photographs are not an anniversary`() {
        assertTrue(Memories.onThisDay(day("2026-08-30", 8), now, utc).isEmpty())
    }

    // --------------------------------------------------------- the mutes

    /**
     * The rules that make this feature safe to ship. Every gallery that has shipped Memories
     * has hurt someone with it, and each of these is a control a person needs *after*
     * something has happened to them.
     */
    @Test
    fun `a muted date never produces a memory`() {
        val library = day("2025-08-30", 8)
        val mutes = Memories.MemoryMutes(dates = setOf(LocalDate(2025, 8, 30)))
        assertTrue(Memories.eligible(library, mutes, utc).isEmpty())
        assertTrue(Memories.today(library, now, utc, mutes).isEmpty())
    }

    @Test
    fun `a muted place never produces a memory`() {
        val library = day("2025-08-30", 8, at = hospital)
        val mutes = Memories.MemoryMutes(places = listOf(Memories.MutedPlace(hospital)))
        assertTrue(Memories.eligible(library, mutes, utc).isEmpty())
    }

    @Test
    fun `a muted place does not mute the whole city`() {
        val library = day("2025-08-30", 8, at = london)
        val mutes = Memories.MemoryMutes(places = listOf(Memories.MutedPlace(hospital)))
        assertEquals(8, Memories.eligible(library, mutes, utc).size)
    }

    @Test
    fun `a muted person is not offered as a memory`() {
        val library = day("2025-08-30", 8)
        val withPerson = Memories.today(
            library,
            now,
            utc,
            peopleOf = { setOf("person-1") },
            personName = { "Sam" },
        )
        assertTrue(withPerson.any { it.kind == Memories.Kind.PERSON })

        val muted = Memories.today(
            library,
            now,
            utc,
            mutes = Memories.MemoryMutes(personIds = setOf("person-1")),
            peopleOf = { setOf("person-1") },
            personName = { "Sam" },
        )
        assertTrue(muted.none { it.kind == Memories.Kind.PERSON })
    }

    /** A dismissal has to mean something, or the user dismisses the same card every week. */
    @Test
    fun `a dismissed memory does not come back`() {
        val library = day("2025-08-30", 8)
        val first = Memories.today(library, now, utc).single()
        val after = Memories.today(
            library,
            now,
            utc,
            mutes = Memories.MemoryMutes(dismissedTitles = setOf(first.title)),
        )
        assertTrue(after.none { it.title == first.title })
    }

    /** The locked folder is out of every other view, and a memory is a view. */
    @Test
    fun `the locked folder never reaches a memory`() {
        val library = day("2025-08-30", 6) + listOf(photo("2025-08-30T20:00:00Z", hidden = true))
        assertTrue(Memories.eligible(library, Memories.MemoryMutes(), utc).none { it.hidden })
    }

    @Test
    fun `a photograph with no date cannot be in a memory`() {
        val undated = photo("2025-08-30T09:00:00Z").copy(takenAt = null)
        assertTrue(Memories.eligible(listOf(undated), Memories.MemoryMutes(), utc).isEmpty())
    }

    // ---------------------------------------------------------- selection

    /** Eleven frames of the same plate of food is what makes a memory feel automatic. */
    @Test
    fun `near-identical frames are collapsed`() {
        val burst = (0 until 10).map { photo("2025-08-30T09:0$it:00Z", phash = 0b1010L) }
        // A hash with every bit flipped: a different scene, not another frame of the same one.
        val different = photo("2025-08-30T11:00:00Z", phash = 0L.inv())
        val chosen = Memories.select(burst + different)
        assertEquals(2, chosen.size)
    }

    /** If the user marked two similar frames, they meant both. */
    @Test
    fun `a favourite survives deduplication`() {
        val burst = (0 until 5).map {
            photo("2025-08-30T09:0$it:00Z", phash = 0b1010L, favourite = it == 3, id = "b$it")
        }
        val chosen = Memories.select(burst)
        assertTrue(chosen.any { it.id == "b3" }, "the favourite was deduplicated away")
    }

    @Test
    fun `a memory is never longer than anyone would watch`() {
        val many = (0 until 200).map { photo("2025-08-30T09:00:00Z", phash = distinctHash(it), id = "m$it") }
        assertTrue(Memories.select(many).size <= Memories.MAX_ITEMS)
    }

    /** A memory of one morning of a week-long trip is a memory of the wrong thing. */
    @Test
    fun `a long trip is sampled across its span, not truncated`() {
        val week = (0 until 100).map {
            val dayOf = 10 + it / 15
            val hour = (9 + it % 12).toString().padStart(2, '0')
            photo("2025-08-${dayOf.toString().padStart(2, '0')}T$hour:00:00Z", phash = distinctHash(it), id = "w$it")
        }
        val chosen = Memories.select(week)
        assertTrue(chosen.size <= Memories.MAX_ITEMS)
        val firstDay = chosen.first().takenAt!!
        val lastDay = chosen.last().takenAt!!
        assertTrue((lastDay - firstDay).inWholeDays >= 4, "the memory only covers ${lastDay - firstDay}")
    }

    @Test
    fun `the chosen pictures stay in the order they were taken`() {
        val mixed = (0 until 12).map {
            photo("2025-08-30T${(9 + it).toString().padStart(2, '0')}:00:00Z", phash = distinctHash(it))
        }
        val chosen = Memories.select(mixed.shuffled())
        assertEquals(chosen.sortedBy { it.takenAt!! }, chosen)
    }

    // --------------------------------------------------------------- cards

    @Test
    fun `the grid is offered a few memories, not all of them`() {
        val library = (1..8).flatMap { yearsBack ->
            day("${2026 - yearsBack}-08-30", 6)
        }
        assertTrue(Memories.today(library, now, utc).size <= Memories.MAX_CARDS)
    }

    @Test
    fun `a recent memory leads an older one`() {
        val library = day("2025-08-30", 8) + day("2018-08-30", 8)
        val titles = Memories.today(library, now, utc).map { it.title }
        assertEquals("A year ago today", titles.first())
    }

    @Test
    fun `an empty library offers nothing`() {
        assertTrue(Memories.today(emptyList(), now, utc).isEmpty())
        assertTrue(Memories.today(day("2025-08-30", 2), now, utc).isEmpty())
    }

    // ----------------------------------------------------------- no names

    /**
     * There is no geocoder — PRD.md R8 forbids the network for the life of the product — so
     * a memory describes when and how far, never where. "Barcelona" would be a guess.
     */
    @Test
    fun `a trip memory names no place`() {
        val atHome = (0 until 80).map {
            photo("2026-06-${(1 + it / 12).toString().padStart(2, '0')}T09:0${it % 6}:00Z", london, id = "h$it")
        }
        val away = (0 until 12).map {
            val hour = (9 + it % 8).toString().padStart(2, '0')
            photo("2026-08-1${it % 5}T$hour:00:00Z", lisbon, phash = distinctHash(it), id = "t$it")
        }
        val library = atHome + away
        val trips = Memories.tripMemories(library)
        assertTrue(trips.isNotEmpty(), "no trip was found")
        val trip = trips.first()
        assertFalse(trip.title.contains("Lisbon"))
        assertTrue(trip.title.contains("away") || trip.title.contains("day"), trip.title)
        assertTrue(trip.subtitle!!.contains("away"), trip.subtitle!!)
    }

    /**
     * Inventing "Person 3" as a memory title is worse than having no memory: there is no
     * source for a person's name but the user.
     */
    @Test
    fun `a person the user has not named gets no memory`() {
        val library = day("2025-08-30", 8)
        val memories = Memories.today(library, now, utc, peopleOf = { setOf("person-1") }, personName = { null })
        assertTrue(memories.none { it.kind == Memories.Kind.PERSON })
    }

    /**
     * Face clustering off means no person memories at all — not computed and filtered. The
     * only way to be sure something never leaves is not to make it.
     */
    @Test
    fun `no face clustering means no person memories`() {
        val library = day("2025-08-30", 8)
        assertTrue(Memories.today(library, now, utc).none { it.kind == Memories.Kind.PERSON })
    }
}
