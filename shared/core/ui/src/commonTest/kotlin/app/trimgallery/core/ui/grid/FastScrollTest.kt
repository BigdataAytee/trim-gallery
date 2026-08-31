package app.trimgallery.core.ui.grid

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FastScrollTest {

    private val today = LocalDate(2026, 8, 30)

    private fun section(label: String, year: Int, month: Int, count: Int) = DateSections.Section(
        key = "$year-$month",
        label = label,
        date = LocalDate(year, month, 1),
        items = List(count) { "$label-$it" },
    )

    private val library = listOf(
        section("August", 2026, 8, 40),
        section("July", 2026, 7, 5),
        section("June", 2026, 6, 200), // a holiday: far more than its neighbours
        section("May", 2026, 5, 3),
    )

    @Test
    fun `an empty library has no scrubber`() {
        assertEquals(emptyList(), FastScroll.ticks(emptyList<DateSections.Section<String>>(), today))
    }

    @Test
    fun `start indices accumulate across sections`() {
        assertEquals(listOf(0, 40, 45, 245), FastScroll.startIndices(library).toList())
    }

    @Test
    fun `the thumb maps to an item, and the last item is reachable`() {
        assertEquals(0, FastScroll.indexAt(library, 0f))
        assertEquals(247, FastScroll.indexAt(library, 1f))
        assertTrue(FastScroll.indexAt(library, 0.5f) in 0..247)
    }

    @Test
    fun `the thumb position and the item index agree in both directions`() {
        listOf(0, 39, 40, 44, 100, 247).forEach { index ->
            val round = FastScroll.indexAt(library, FastScroll.fractionOf(library, index))
            assertTrue(
                kotlin.math.abs(round - index) <= 1,
                "index $index round-tripped to $round",
            )
        }
    }

    @Test
    fun `a thumb outside the track is clamped rather than crashing`() {
        assertEquals(0, FastScroll.indexAt(library, -5f))
        assertEquals(247, FastScroll.indexAt(library, 5f))
    }

    @Test
    fun `sectionOf finds the run containing an item`() {
        assertEquals(0, FastScroll.sectionOf(library, 0))
        assertEquals(0, FastScroll.sectionOf(library, 39))
        assertEquals(1, FastScroll.sectionOf(library, 40))
        assertEquals(2, FastScroll.sectionOf(library, 45))
        assertEquals(3, FastScroll.sectionOf(library, 247))
    }

    @Test
    fun `every section gets a tick when they all fit`() {
        val ticks = FastScroll.ticks(library, today, maxTicks = 12)
        assertEquals(listOf("Aug", "Jul", "Jun", "May"), ticks.map { it.label })
        assertEquals(listOf(0, 40, 45, 245), ticks.map { it.itemIndex })
    }

    @Test
    fun `ticks are capped when there are more sections than room`() {
        val many = (1..60).map { section("M$it", 2020 + it % 6, (it % 12) + 1, 10) }
        val ticks = FastScroll.ticks(many, today, maxTicks = 8)
        assertTrue(ticks.size <= 8, "got ${ticks.size} ticks")
        assertTrue(ticks.isNotEmpty())
    }

    @Test
    fun `ticks are spaced by item position, not by section count`() {
        // The June holiday holds 200 of 248 items, so most of the track belongs to it.
        // Taking every Nth section instead would bunch every label into the top fifth.
        val ticks = FastScroll.ticks(library, today, maxTicks = 4)
        assertTrue(ticks.all { it.fraction in 0f..1f })
        assertEquals(ticks.map { it.fraction }.sorted(), ticks.map { it.fraction })
    }

    @Test
    fun `repeated labels are collapsed`() {
        // A decade at year zoom would otherwise print the same year several times.
        val repeated = (1..10).map { section("June", 2026, 6, 20) }
        val ticks = FastScroll.ticks(repeated, today, maxTicks = 10)
        assertEquals(1, ticks.size, "labels were ${ticks.map { it.label }}")
    }

    @Test
    fun `the scrubber shortens months in the current year and shows the year otherwise`() {
        assertEquals("Aug", DateSections.scrubberLabel(LocalDate(2026, 8, 1), today))
        assertEquals("2024", DateSections.scrubberLabel(LocalDate(2024, 8, 1), today))
    }

    @Test
    fun `a single item library still produces a usable scrubber`() {
        val one = listOf(section("August", 2026, 8, 1))
        assertEquals(1, FastScroll.ticks(one, today).size)
        assertEquals(0, FastScroll.indexAt(one, 1f))
        assertEquals(0f, FastScroll.fractionOf(one, 0))
    }
}
