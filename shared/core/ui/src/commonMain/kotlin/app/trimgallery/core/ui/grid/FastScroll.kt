package app.trimgallery.core.ui.grid

import kotlinx.datetime.LocalDate

/**
 * The date scrubber down the edge of the grid (BUILD.md § 9: "Grid with fast-scroll date
 * bar").
 *
 * The hard part is that the mapping between the thumb and the library is not linear in
 * *sections* — a busy holiday week holds more photos than a quiet year — so the thumb
 * has to track item position while the labels track dates. Both directions live here so
 * they cannot drift apart.
 */
object FastScroll {

    /** A label the scrubber shows, and where it sits along the track. */
    data class Tick(val label: String, val fraction: Float, val itemIndex: Int)

    /**
     * Builds the scrubber for [sections].
     *
     * @param maxTicks how many labels fit down the track. Ticks are chosen by item
     *   position rather than by taking every Nth section, so the labels stay evenly
     *   spaced on screen even when section sizes vary wildly — which is the normal case.
     */
    fun <T> ticks(
        sections: List<DateSections.Section<T>>,
        today: LocalDate,
        maxTicks: Int = DEFAULT_MAX_TICKS,
    ): List<Tick> {
        if (sections.isEmpty() || maxTicks <= 0) return emptyList()

        val total = sections.sumOf { it.items.size }
        if (total == 0) return emptyList()

        val starts = startIndices(sections)

        if (sections.size <= maxTicks) {
            return sections.mapIndexed { i, section ->
                Tick(
                    label = DateSections.scrubberLabel(section.date, today),
                    fraction = starts[i].toFloat() / total,
                    itemIndex = starts[i],
                )
            }.dedupeLabels()
        }

        // Walk evenly along the *items* and take whichever section each step lands in.
        return (0 until maxTicks).map { step ->
            val targetIndex = (step.toFloat() / maxTicks * total).toInt()
            val sectionIndex = sectionAt(starts, targetIndex)
            Tick(
                label = DateSections.scrubberLabel(sections[sectionIndex].date, today),
                fraction = starts[sectionIndex].toFloat() / total,
                itemIndex = starts[sectionIndex],
            )
        }.dedupeLabels()
    }

    /** The flattened item index a thumb at [fraction] of the track points at. */
    fun <T> indexAt(sections: List<DateSections.Section<T>>, fraction: Float): Int {
        val total = sections.sumOf { it.items.size }
        if (total == 0) return 0
        // The last item must be reachable, so the top of the track maps to total - 1.
        return (fraction.coerceIn(0f, 1f) * total).toInt().coerceIn(0, total - 1)
    }

    /** Where the thumb sits when item [index] is at the top of the viewport. */
    fun <T> fractionOf(sections: List<DateSections.Section<T>>, index: Int): Float {
        val total = sections.sumOf { it.items.size }
        if (total <= 1) return 0f
        return (index.toFloat() / total).coerceIn(0f, 1f)
    }

    /** The section containing flattened item [index]. */
    fun <T> sectionOf(sections: List<DateSections.Section<T>>, index: Int): Int =
        sectionAt(startIndices(sections), index)

    /** First flattened item index of each section. */
    fun <T> startIndices(sections: List<DateSections.Section<T>>): IntArray {
        val starts = IntArray(sections.size)
        var running = 0
        sections.forEachIndexed { i, section ->
            starts[i] = running
            running += section.items.size
        }
        return starts
    }

    /** Binary search for the section whose run contains [index]. */
    private fun sectionAt(starts: IntArray, index: Int): Int {
        if (starts.isEmpty()) return 0
        var low = 0
        var high = starts.lastIndex
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (starts[mid] <= index) low = mid else high = mid - 1
        }
        return low
    }

    /**
     * Collapses runs of identical labels.
     *
     * At year zoom a decade of photos would otherwise print "2019" eight times down the
     * track, which tells the user nothing and looks broken.
     */
    private fun List<Tick>.dedupeLabels(): List<Tick> {
        val out = mutableListOf<Tick>()
        for (tick in this) {
            if (out.lastOrNull()?.label != tick.label) out += tick
        }
        return out
    }

    private const val DEFAULT_MAX_TICKS = 12
}
