package app.trimgallery.core.ui.grid

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Groups a library into the dated runs the grid draws under sticky headers.
 *
 * Kept free of Compose so the grouping, the header wording and the flattened index
 * arithmetic can be unit tested — all three are easy to get subtly wrong and all three
 * are visible on every screenful.
 */
object DateSections {

    /** One run of items sharing a header. */
    data class Section<T>(
        /** Stable across recomposition and across zoom changes within a level. */
        val key: String,
        val label: String,
        val date: LocalDate,
        val items: List<T>,
    )

    /**
     * Splits [items] into sections at [zoom].
     *
     * [items] must already be in the order the grid shows them — newest first. Sorting
     * here would hide a bug in the query rather than fix it, and the query is the only
     * place that knows how the user asked to sort.
     */
    fun <T> sections(
        items: List<T>,
        zoom: GridZoom,
        timeZone: TimeZone,
        today: LocalDate,
        takenAt: (T) -> Instant?,
    ): List<Section<T>> {
        if (items.isEmpty()) return emptyList()

        val result = mutableListOf<Section<T>>()
        var bucket = mutableListOf<T>()
        var currentKey: String? = null
        var currentDate: LocalDate? = null

        fun flush() {
            val key = currentKey ?: return
            val date = currentDate ?: return
            result += Section(key, label(date, zoom, today), date, bucket.toList())
            bucket = mutableListOf()
        }

        for (item in items) {
            val date = takenAt(item)?.toLocalDateTime(timeZone)?.date ?: UNDATED
            val key = keyOf(date, zoom)
            if (key != currentKey) {
                flush()
                currentKey = key
                currentDate = date
            }
            bucket += item
        }
        flush()
        return result
    }

    /** The grouping key for [date] at [zoom]. */
    fun keyOf(date: LocalDate, zoom: GridZoom): String = when (zoom) {
        GridZoom.DAY -> "d:${date.year}-${date.monthNumber}-${date.dayOfMonth}"
        GridZoom.MONTH -> "m:${date.year}-${date.monthNumber}"
        GridZoom.YEAR -> "y:${date.year}"
    }

    /**
     * The header text.
     *
     * "Today" and "Yesterday" only at day level: at month or year level they would name
     * a range far larger than the word implies. The year is dropped for the current year
     * because repeating it on every header is noise.
     */
    fun label(date: LocalDate, zoom: GridZoom, today: LocalDate): String {
        if (date == UNDATED) return "No date"
        return when (zoom) {
            GridZoom.DAY -> when (date) {
                today -> "Today"
                today.minusDaysSafe(1) -> "Yesterday"
                else -> if (date.year == today.year) {
                    "${date.dayOfMonth} ${monthName(date.monthNumber)}"
                } else {
                    "${date.dayOfMonth} ${monthName(date.monthNumber)} ${date.year}"
                }
            }

            GridZoom.MONTH ->
                if (date.year == today.year) {
                    monthName(date.monthNumber)
                } else {
                    "${monthName(date.monthNumber)} ${date.year}"
                }

            GridZoom.YEAR -> date.year.toString()
        }
    }

    /** A short label for the fast-scroll bar, where there is room for very little. */
    fun scrubberLabel(date: LocalDate, today: LocalDate): String = if (date == UNDATED) {
        "—"
    } else if (date.year == today.year) {
        monthName(date.monthNumber).take(SHORT_MONTH)
    } else {
        date.year.toString()
    }

    private fun monthName(month: Int): String = MONTHS[(month - 1).coerceIn(0, MONTHS.lastIndex)]

    private fun LocalDate.minusDaysSafe(days: Int): LocalDate = LocalDate.fromEpochDays(toEpochDays() - days)

    private val MONTHS = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )

    private const val SHORT_MONTH = 3

    /**
     * Items with no capture date sort into their own run rather than being dropped.
     *
     * A screenshot copied from a backup often has no EXIF date; hiding it would be worse
     * than showing it in a section that says so. Epoch day 0 keeps it last in a
     * newest-first list.
     */
    val UNDATED: LocalDate = LocalDate.fromEpochDays(0)
}
