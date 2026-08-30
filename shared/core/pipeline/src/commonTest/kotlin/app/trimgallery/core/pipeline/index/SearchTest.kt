package app.trimgallery.core.pipeline.index

import app.trimgallery.core.model.MediaFlags
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.model.MediaRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * USER_JOURNEY.md § 7 gives one box and six kinds of answer. These tests are about the
 * inference that makes that work without the user learning any syntax.
 */
class SearchTest {

    private val now = Instant.parse("2026-08-30T12:00:00Z").toEpochMilliseconds()

    private fun item(
        id: String,
        name: String = "$id.jpg",
        takenAt: String? = "2026-01-01T00:00:00Z",
        hidden: Boolean = false,
    ) = MediaItem(
        id = id,
        platformRef = MediaRef("ref-$id"),
        name = name,
        kind = MediaKind.PHOTO,
        codec = "jpeg",
        width = 4032,
        height = 3024,
        fps = null,
        bitrate = null,
        size = 4_000_000,
        duration = null,
        takenAt = takenAt?.let(Instant::parse),
        location = null,
        cameraModel = null,
        flags = MediaFlags(hidden = hidden),
        phash = null,
        sha256 = null,
        mtime = 0,
    )

    // ------------------------------------------------------------------ parsing

    @Test
    fun `every word is a candidate label and a candidate piece of text`() {
        // The labeller finds "dog" on a dog; OCR finds "receipt" on a receipt. The user
        // should not have to say which they meant.
        val parsed = SearchQuery.parse("receipt")
        assertEquals(setOf(SearchQuery.Facet.LABEL, SearchQuery.Facet.TEXT), parsed.terms.single().facets)
    }

    @Test
    fun `a name is only a person because the user named a cluster that`() {
        assertTrue(SearchQuery.Facet.PERSON !in SearchQuery.parse("mum").terms.single().facets)
        val known = SearchQuery.parse("Mum", knownPeople = setOf("Mum"))
        assertTrue(SearchQuery.Facet.PERSON in known.terms.single().facets)
    }

    @Test
    fun `a place is only a place because a photograph was taken there`() {
        val parsed = SearchQuery.parse("Lagos", knownPlaces = setOf("Lagos"))
        assertTrue(SearchQuery.Facet.PLACE in parsed.terms.single().facets)
    }

    @Test
    fun `a year is searched as a year and as a word`() {
        // "2019" could be a date or the number on a race bib. Guessing wrong returns
        // nothing, with no way for the user to say what they meant.
        val term = SearchQuery.parse("2019").terms.single()
        assertEquals(2019, term.year)
        assertTrue(SearchQuery.Facet.DATE in term.facets)
        assertTrue(SearchQuery.Facet.TEXT in term.facets)
    }

    @Test
    fun `an implausible year is only a word`() {
        // 1826 is not a photograph anyone has, and treating it as a date would silently
        // drop the text match that is the only thing the user could have meant.
        val term = SearchQuery.parse("1826").terms.single()
        assertEquals(null, term.year)
        assertTrue(SearchQuery.Facet.DATE !in term.facets)
    }

    @Test
    fun `whitespace and punctuation split terms, and an empty query is empty`() {
        assertEquals(3, SearchQuery.parse("beach, dog  2023").terms.size)
        assertTrue(SearchQuery.parse("   ").isEmpty)
        assertTrue(SearchQuery.parse("").isEmpty)
    }

    @Test
    fun `terms are matched case-insensitively`() {
        assertEquals("mum", SearchQuery.parse("MUM", knownPeople = setOf("mum")).terms.single().text)
    }

    // ------------------------------------------------------------------ ranking

    private fun evidence(
        id: String,
        labels: Map<String, Float> = emptyMap(),
        text: Set<String> = emptySet(),
        people: Set<String> = emptySet(),
        places: Set<String> = emptySet(),
        takenAt: String? = "2026-01-01T00:00:00Z",
        hidden: Boolean = false,
        name: String = "$id.jpg",
    ) = SearchRanker.Evidence(item(id, name, takenAt, hidden), labels, text, people, places)

    @Test
    fun `a named person outranks a hesitant label`() {
        // They told us who that is; the classifier is guessing.
        val query = SearchQuery.parse("mum", knownPeople = setOf("mum"))
        val hits = SearchRanker.rank(
            query,
            listOf(evidence("label", labels = mapOf("mum" to 0.4f)), evidence("person", people = setOf("Mum"))),
            now,
        )
        assertEquals("person", hits.first().item.id)
    }

    @Test
    fun `label confidence is respected`() {
        val query = SearchQuery.parse("dog")
        val hits = SearchRanker.rank(
            query,
            listOf(evidence("maybe", labels = mapOf("dog" to 0.3f)), evidence("certain", labels = mapOf("dog" to 0.98f))),
            now,
        )
        assertEquals("certain", hits.first().item.id)
    }

    @Test
    fun `matching every term beats matching one term well`() {
        // "beach 2023" wants the beach photographs from 2023, not every beach.
        val query = SearchQuery.parse("beach 2023")
        val hits = SearchRanker.rank(
            query,
            listOf(
                evidence("beachOnly", labels = mapOf("beach" to 1.0f), takenAt = "2019-07-01T00:00:00Z"),
                evidence("both", labels = mapOf("beach" to 0.7f), takenAt = "2023-07-01T00:00:00Z"),
            ),
            now,
        )
        assertEquals("both", hits.first().item.id)
    }

    @Test
    fun `recency separates equal matches without overturning strong ones`() {
        val query = SearchQuery.parse("dog")
        val equal = SearchRanker.rank(
            query,
            listOf(
                evidence("old", labels = mapOf("dog" to 0.9f), takenAt = "2017-01-01T00:00:00Z"),
                evidence("new", labels = mapOf("dog" to 0.9f), takenAt = "2026-06-01T00:00:00Z"),
            ),
            now,
        )
        assertEquals("new", equal.first().item.id, "recency should break a tie")

        // But a weak recent match must not outrank a strong old one.
        val unequal = SearchRanker.rank(
            SearchQuery.parse("mum", knownPeople = setOf("mum")),
            listOf(
                evidence("strongOld", people = setOf("Mum"), takenAt = "2016-01-01T00:00:00Z"),
                evidence("weakNew", labels = mapOf("mum" to 0.3f), takenAt = "2026-08-01T00:00:00Z"),
            ),
            now,
        )
        assertEquals("strongOld", unequal.first().item.id)
    }

    @Test
    fun `results carry the facets that matched, for the chips`() {
        val query = SearchQuery.parse("mum lagos", knownPeople = setOf("mum"), knownPlaces = setOf("lagos"))
        val hit = SearchRanker.rank(
            query,
            listOf(evidence("a", people = setOf("Mum"), places = setOf("Lagos"))),
            now,
        ).single()
        assertEquals(setOf(SearchQuery.Facet.PERSON, SearchQuery.Facet.PLACE), hit.matched)
    }

    @Test
    fun `the locked folder never appears in search`() {
        val hits = SearchRanker.rank(
            SearchQuery.parse("dog"),
            listOf(evidence("hidden", labels = mapOf("dog" to 1.0f), hidden = true)),
            now,
        )
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `an item matching nothing is not a result`() {
        val hits = SearchRanker.rank(SearchQuery.parse("cat"), listOf(evidence("a", labels = mapOf("dog" to 1f))), now)
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `a filename match works but never drowns content`() {
        // A user who remembers "IMG_4021" has no other way to find it — but camera names
        // are near-identical across a library.
        val query = SearchQuery.parse("img_4021")
        val byName = SearchRanker.rank(query, listOf(evidence("a", name = "IMG_4021.jpg")), now)
        assertEquals(1, byName.size)

        val mixed = SearchRanker.rank(
            SearchQuery.parse("beach"),
            listOf(
                evidence("named", name = "beach-trip.jpg"),
                evidence("labelled", labels = mapOf("beach" to 0.9f)),
            ),
            now,
        )
        assertEquals("labelled", mixed.first().item.id)
    }

    @Test
    fun `an empty query returns nothing rather than everything`() {
        assertTrue(SearchRanker.rank(SearchQuery.parse(""), listOf(evidence("a")), now).isEmpty())
    }

    @Test
    fun `ordering is stable for identical scores`() {
        // Otherwise the results grid reshuffles as the user scrolls it.
        val query = SearchQuery.parse("dog")
        val candidates = listOf(
            evidence("z", labels = mapOf("dog" to 0.9f)),
            evidence("a", labels = mapOf("dog" to 0.9f)),
        )
        assertEquals(
            SearchRanker.rank(query, candidates, now).map { it.item.id },
            SearchRanker.rank(query, candidates.reversed(), now).map { it.item.id },
        )
    }
}
