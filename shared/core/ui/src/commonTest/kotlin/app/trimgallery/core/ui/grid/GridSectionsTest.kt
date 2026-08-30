package app.trimgallery.core.ui.grid

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class GridZoomTest {

    @Test
    fun `zooming in shows fewer, larger tiles`() {
        assertTrue(GridZoom.DAY.columns < GridZoom.MONTH.columns)
        assertTrue(GridZoom.MONTH.columns < GridZoom.YEAR.columns)
    }

    @Test
    fun `the default is the day grid`() {
        assertEquals(GridZoom.DAY, GridZoom.Default)
    }

    @Test
    fun `zooming past either end stays put`() {
        assertEquals(GridZoom.DAY, GridZoom.DAY.zoomedIn)
        assertEquals(GridZoom.YEAR, GridZoom.YEAR.zoomedOut)
    }

    @Test
    fun `a small pinch does not change level`() {
        // Without a deadband the grid flickers while the fingers are still moving.
        listOf(0.9f, 1f, 1.2f, 1.3f).forEach {
            assertEquals(GridZoom.MONTH, GridZoom.step(GridZoom.MONTH, it), "scale=$it")
        }
    }

    @Test
    fun `a deliberate pinch changes level`() {
        assertEquals(GridZoom.DAY, GridZoom.step(GridZoom.MONTH, 1.5f))
        assertEquals(GridZoom.YEAR, GridZoom.step(GridZoom.MONTH, 0.6f))
    }

    @Test
    fun `a pinch never skips a level`() {
        // Crossing two thresholds at once is a fumble, and skipping loses the user's place.
        assertEquals(GridZoom.MONTH, GridZoom.step(GridZoom.YEAR, 100f))
        assertEquals(GridZoom.MONTH, GridZoom.step(GridZoom.DAY, 0.001f))
    }
}

class DateSectionsTest {

    private val tz = TimeZone.UTC
    private val today = LocalDate(2026, 8, 30)

    private fun at(year: Int, month: Int, day: Int): Instant =
        Instant.fromEpochSeconds(LocalDate(year, month, day).toEpochDays() * SECONDS_PER_DAY)

    private data class Item(val id: String, val takenAt: Instant?)

    private fun sections(items: List<Item>, zoom: GridZoom) =
        DateSections.sections(items, zoom, tz, today) { it.takenAt }

    @Test
    fun `an empty library has no sections`() {
        assertEquals(emptyList(), sections(emptyList(), GridZoom.DAY))
    }

    @Test
    fun `consecutive items on the same day share a section`() {
        val items = listOf(
            Item("a", at(2026, 8, 30)),
            Item("b", at(2026, 8, 30)),
            Item("c", at(2026, 8, 29)),
        )
        val result = sections(items, GridZoom.DAY)
        assertEquals(2, result.size)
        assertEquals(listOf("a", "b"), result[0].items.map { it.id })
        assertEquals(listOf("c"), result[1].items.map { it.id })
    }

    @Test
    fun `zooming out merges days into months and months into years`() {
        val items = listOf(
            Item("a", at(2026, 8, 30)),
            Item("b", at(2026, 8, 2)),
            Item("c", at(2026, 3, 5)),
            Item("d", at(2025, 12, 1)),
        )
        assertEquals(4, sections(items, GridZoom.DAY).size)
        assertEquals(3, sections(items, GridZoom.MONTH).size)
        assertEquals(2, sections(items, GridZoom.YEAR).size)
    }

    @Test
    fun `every item survives every zoom level`() {
        val items = (1..40).map { Item("i$it", at(2026, (it % 12) + 1, (it % 28) + 1)) }
        GridZoom.entries.forEach { zoom ->
            val flattened = sections(items, zoom).flatMap { it.items }
            assertEquals(items.size, flattened.size, "zoom=$zoom")
            assertEquals(items.map { it.id }, flattened.map { it.id }, "zoom=$zoom lost order")
        }
    }

    @Test
    fun `today and yesterday are named at day level`() {
        assertEquals("Today", DateSections.label(today, GridZoom.DAY, today))
        assertEquals("Yesterday", DateSections.label(LocalDate(2026, 8, 29), GridZoom.DAY, today))
    }

    @Test
    fun `today and yesterday are not used for months or years`() {
        // At month zoom "Today" would name a range far larger than the word implies.
        assertEquals("August", DateSections.label(today, GridZoom.MONTH, today))
        assertEquals("2026", DateSections.label(today, GridZoom.YEAR, today))
    }

    @Test
    fun `the year is dropped in the current year and kept otherwise`() {
        assertEquals("2 August", DateSections.label(LocalDate(2026, 8, 2), GridZoom.DAY, today))
        assertEquals("2 August 2024", DateSections.label(LocalDate(2024, 8, 2), GridZoom.DAY, today))
        assertEquals("March", DateSections.label(LocalDate(2026, 3, 1), GridZoom.MONTH, today))
        assertEquals("March 2024", DateSections.label(LocalDate(2024, 3, 1), GridZoom.MONTH, today))
    }

    @Test
    fun `items with no date get their own section rather than being dropped`() {
        // A screenshot copied from a backup often has no EXIF date. Hiding it would be
        // worse than showing it in a section that says so.
        val items = listOf(Item("dated", at(2026, 8, 30)), Item("undated", null))
        val result = sections(items, GridZoom.DAY)
        assertEquals(2, result.size)
        assertEquals("No date", result[1].label)
        assertEquals(listOf("undated"), result[1].items.map { it.id })
    }

    @Test
    fun `section keys are stable and distinguish levels`() {
        val date = LocalDate(2026, 8, 30)
        val keys = GridZoom.entries.map { DateSections.keyOf(date, it) }
        assertEquals(keys.size, keys.toSet().size, "levels must not collide: $keys")
        assertEquals(DateSections.keyOf(date, GridZoom.DAY), DateSections.keyOf(date, GridZoom.DAY))
    }

    @Test
    fun `the same month in different years is not merged`() {
        val items = listOf(Item("a", at(2026, 8, 1)), Item("b", at(2025, 8, 1)))
        assertEquals(2, sections(items, GridZoom.MONTH).size)
    }

    private companion object {
        const val SECONDS_PER_DAY = 86_400L
    }
}
