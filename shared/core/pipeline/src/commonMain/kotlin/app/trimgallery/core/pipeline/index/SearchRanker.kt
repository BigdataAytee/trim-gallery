package app.trimgallery.core.pipeline.index

import app.trimgallery.core.model.MediaItem

/**
 * Orders search results (BUILD.md § 7, USER_JOURNEY.md § 7).
 *
 * The index can say *whether* a photograph matches; this decides which of four hundred
 * matches the user sees first, which is the part they actually experience. Two principles,
 * and they occasionally disagree:
 *
 * - **A confident match beats a weak one.** A face the user named beats a label the
 *   classifier was 40% sure about.
 * - **Recent beats old, but only among comparable matches.** Sorting purely by date puts
 *   last week's screenshot above the holiday the user is clearly looking for; ignoring date
 *   entirely buries this month under a decade.
 */
object SearchRanker {

    /**
     * What each kind of match is worth.
     *
     * The order is the claim, not the exact numbers. A person the user *named* is the
     * strongest signal in the index — they told us who that is. Text found by OCR is next,
     * because a word visible in the picture is rarely a coincidence. A place is strong but
     * coarse: a whole holiday shares one. Labels are weakest and most numerous; a
     * classifier saying "dog" is a guess, and it is scaled by its own confidence besides.
     */
    const val PERSON_WEIGHT = 4.0
    const val TEXT_WEIGHT = 3.0
    const val PLACE_WEIGHT = 2.0
    const val LABEL_WEIGHT = 1.5

    /**
     * A match on the file's own name.
     *
     * Weakest of all, and included only because a user who remembers "IMG_4021" has no
     * other way to find it. Camera filenames are near-identical across a whole library, so
     * a heavier weight would let a partial name match drown real content matches.
     */
    const val NAME_WEIGHT = 0.5

    /**
     * How much of the final score recency may contribute, at most.
     *
     * A fifth. Enough to separate two otherwise equal matches, not enough to lift a weak
     * match above a strong one — a photograph that merely *might* contain a dog should
     * never outrank one the user has named their mother in, however recent.
     */
    const val RECENCY_WEIGHT = 0.2

    /** Everything the index found for one item. */
    data class Evidence(
        val item: MediaItem,
        /** Label text to the classifier's confidence, 0..1. */
        val labels: Map<String, Float> = emptyMap(),
        /** Words found by OCR, lowercased. */
        val text: Set<String> = emptySet(),
        /** Names of people identified in the photograph. */
        val people: Set<String> = emptySet(),
        /** Place names attached to its location. */
        val places: Set<String> = emptySet(),
    )

    data class Hit(
        val item: MediaItem,
        val score: Double,
        /** Which facets contributed, for the chips USER_JOURNEY.md § 7 shows. */
        val matched: Set<SearchQuery.Facet>,
    )

    /**
     * @param nowMs used to age results. Passed in rather than read, so a test can pin it
     *   and so the ordering does not change while the user is looking at it.
     */
    fun rank(
        query: SearchQuery.Parsed,
        candidates: List<Evidence>,
        nowMs: Long,
    ): List<Hit> {
        if (query.isEmpty) return emptyList()

        return candidates
            .filter { !it.item.hidden }   // the locked folder is excluded from search
            .mapNotNull { evidence -> score(query, evidence, nowMs) }
            .sortedWith(compareByDescending<Hit> { it.score }.thenBy { it.item.id })
    }

    private fun score(query: SearchQuery.Parsed, evidence: Evidence, nowMs: Long): Hit? {
        var total = 0.0
        val matched = mutableSetOf<SearchQuery.Facet>()

        query.terms.forEach { term ->
            if (SearchQuery.Facet.PERSON in term.facets && term.text in evidence.people.lowercased()) {
                total += PERSON_WEIGHT
                matched += SearchQuery.Facet.PERSON
            }
            if (SearchQuery.Facet.TEXT in term.facets && term.text in evidence.text.lowercased()) {
                total += TEXT_WEIGHT
                matched += SearchQuery.Facet.TEXT
            }
            if (SearchQuery.Facet.PLACE in term.facets && term.text in evidence.places.lowercased()) {
                total += PLACE_WEIGHT
                matched += SearchQuery.Facet.PLACE
            }
            if (SearchQuery.Facet.LABEL in term.facets) {
                val confidence = evidence.labels.entries
                    .firstOrNull { it.key.lowercase() == term.text }?.value
                if (confidence != null) {
                    // Scaled by the classifier's own confidence: "probably a dog" should
                    // not rank with "certainly a dog".
                    total += LABEL_WEIGHT * confidence
                    matched += SearchQuery.Facet.LABEL
                }
            }
            if (term.year != null && yearOf(evidence.item) == term.year) {
                total += PLACE_WEIGHT
                matched += SearchQuery.Facet.DATE
            }
            if (term.text in evidence.item.name.lowercase()) {
                total += NAME_WEIGHT
            }
        }

        if (total <= 0.0) return null

        // Every term matched somewhere counts for more than one term matching well: a
        // search for "beach 2023" wants the beach photographs from 2023, not every beach.
        val coverage = query.terms.count { term -> matches(term, evidence) }.toDouble() / query.terms.size
        val recency = recencyOf(evidence.item, nowMs)

        return Hit(
            item = evidence.item,
            score = total * (1.0 + coverage) + recency * RECENCY_WEIGHT,
            matched = matched,
        )
    }

    private fun matches(term: SearchQuery.Term, evidence: Evidence): Boolean =
        term.text in evidence.people.lowercased() ||
            term.text in evidence.text.lowercased() ||
            term.text in evidence.places.lowercased() ||
            evidence.labels.keys.any { it.lowercase() == term.text } ||
            (term.year != null && yearOf(evidence.item) == term.year) ||
            term.text in evidence.item.name.lowercase()

    /**
     * 1.0 for something taken today, falling away over roughly a decade.
     *
     * Linear rather than exponential: an exponential curve makes everything older than a
     * year indistinguishable, and a decade-old library is exactly the case BUILD.md § 3
     * describes.
     */
    private fun recencyOf(item: MediaItem, nowMs: Long): Double {
        val taken = item.takenAt?.toEpochMilliseconds() ?: item.mtime
        if (taken <= 0) return 0.0
        val age = (nowMs - taken).coerceAtLeast(0)
        return (1.0 - age.toDouble() / DECADE_MS).coerceIn(0.0, 1.0)
    }

    private fun yearOf(item: MediaItem): Int? {
        val ms = item.takenAt?.toEpochMilliseconds() ?: item.mtime.takeIf { it > 0 } ?: return null
        // Whole years since the epoch, close enough for a date facet: leap years shift the
        // boundary by hours, and a photograph taken in the first hours of 1 January is
        // already ambiguous by time zone.
        return (EPOCH_YEAR + ms / YEAR_MS).toInt()
    }

    private fun Set<String>.lowercased(): Set<String> = mapTo(mutableSetOf()) { it.lowercase() }

    private const val YEAR_MS = 31_556_952_000L    // the mean Gregorian year
    private const val DECADE_MS = YEAR_MS * 10
    private const val EPOCH_YEAR = 1970L
}
