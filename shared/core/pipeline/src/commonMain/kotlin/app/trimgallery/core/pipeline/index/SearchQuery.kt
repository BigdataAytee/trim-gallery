package app.trimgallery.core.pipeline.index

/**
 * What the user typed, turned into something the index can answer.
 *
 * USER_JOURNEY.md § 7: *"type 'beach', 'dog', 'receipt', 'Mum', '2023', 'Lagos' → results
 * grid with chips for people/places/dates/text."* One box, six kinds of answer, and no
 * syntax to learn — so the parsing has to infer intent from the words themselves.
 *
 * Deliberately conservative about inference. A term that *might* be a year is searched as a
 * year **and** as a word: "2019" could be a date or the number on a race bib, and a search
 * box that guessed wrong would simply return nothing with no way for the user to say what
 * they meant.
 */
object SearchQuery {

    /** What a term could be looking for. A term often carries several. */
    enum class Facet { LABEL, TEXT, PERSON, PLACE, DATE }

    /**
     * A year the app will accept as a date.
     *
     * Not "any four digits": 1826 is not a photograph anyone has, and treating it as a year
     * would silently drop the text match that is the only thing the user could have meant.
     * The lower bound is roughly when consumer digital cameras began.
     */
    val PLAUSIBLE_YEARS = 1990..2100

    data class Term(
        val text: String,
        val facets: Set<Facet>,
        /** Set when the term parsed as a year. */
        val year: Int? = null,
    )

    data class Parsed(
        val raw: String,
        val terms: List<Term>,
    ) {
        val isEmpty: Boolean get() = terms.isEmpty()

        fun termsFor(facet: Facet): List<Term> = terms.filter { facet in it.facets }
    }

    /**
     * @param knownPeople names the user has given to face clusters.
     * @param knownPlaces place names the index holds.
     *
     * Both are passed in rather than guessed at. "Mum" is only a person because the user
     * named a cluster that; "Lagos" is only a place because a photograph was taken there.
     * Inferring either from the shape of the word would put every capitalised noun in the
     * people facet.
     */
    fun parse(
        raw: String,
        knownPeople: Set<String> = emptySet(),
        knownPlaces: Set<String> = emptySet(),
    ): Parsed {
        val people = knownPeople.map { it.lowercase() }.toSet()
        val places = knownPlaces.map { it.lowercase() }.toSet()

        val terms = raw.split(SEPARATORS)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { word ->
                val lower = word.lowercase()
                val year = lower.toIntOrNull()?.takeIf { it in PLAUSIBLE_YEARS }

                val facets = buildSet {
                    // Every word is a candidate label and a candidate piece of text: OCR
                    // finds "receipt" on a receipt, and the labeller finds "dog" on a dog.
                    add(Facet.LABEL)
                    add(Facet.TEXT)
                    if (lower in people) add(Facet.PERSON)
                    if (lower in places) add(Facet.PLACE)
                    if (year != null) add(Facet.DATE)
                }

                Term(lower, facets, year)
            }

        return Parsed(raw, terms)
    }

    private val SEPARATORS = Regex("""[\s,]+""")
}
