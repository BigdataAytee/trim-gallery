package app.trimgallery.core.ui.format

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The user-facing numbers.
 *
 * BUILD.md § 9 asks the viewer's info sheet to say `"Optimised · was 380 MB, now 165
 * MB"`, and the Space screen to say `"Freed 6.2 GB"`. PROJECT.md is emphatic that the
 * app must never overstate what it did, so the rounding here is deliberately
 * conservative and the wording never claims a saving that did not happen.
 */
object MediaFormatting {

    /**
     * Decimal units, matching every storage figure the phone itself shows.
     *
     * Using 2^20 would make the app's "165 MB" disagree with the file manager's for the
     * same file, and the user would rightly trust the file manager.
     */
    private const val KB = 1_000L
    private const val MB = 1_000_000L
    private const val GB = 1_000_000_000L

    /** A size, at the precision a person actually reads. */
    fun bytes(value: Long): String {
        val v = abs(value)
        return when {
            v >= GB -> "${oneDecimal(v.toDouble() / GB)} GB"
            v >= MB -> "${v / MB} MB"
            v >= KB -> "${v / KB} KB"
            else -> "$v B"
        }
    }

    /**
     * The optimisation line for the info sheet, or null when the item was never touched.
     *
     * Null rather than an empty string: the caller then cannot accidentally render a
     * blank row where a real fact belongs.
     */
    fun optimisedLine(originalSize: Long?, newSize: Long?): String? {
        if (originalSize == null || newSize == null) return null
        if (newSize >= originalSize) return null
        return "Optimised · was ${bytes(originalSize)}, now ${bytes(newSize)}"
    }

    /**
     * "Freed 6.2 GB" — or nothing at all when the night's work freed nothing.
     *
     * A run that saved no space says so elsewhere; it must not be dressed up as "Freed
     * 0 B".
     */
    fun freedLine(bytesFreed: Long): String? = if (bytesFreed <= 0) null else "Freed ${bytes(bytesFreed)}"

    /**
     * How much smaller the file got, as a percentage.
     *
     * Rounded **down**, so the app never claims a rounder saving than it achieved.
     */
    fun savedPercent(originalSize: Long, newSize: Long): Int {
        if (originalSize <= 0 || newSize >= originalSize) return 0
        return ((originalSize - newSize) * PERCENT / originalSize).toInt()
    }

    /** `0:18`, as on a clip's tile. */
    fun duration(millis: Long): String {
        if (millis <= 0) return "0:00"
        val totalSeconds = (millis / MILLIS_PER_SECOND).toInt()
        val hours = totalSeconds / SECONDS_PER_HOUR
        val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        val seconds = totalSeconds % SECONDS_PER_MINUTE
        return if (hours > 0) {
            "$hours:${minutes.pad()}:${seconds.pad()}"
        } else {
            "$minutes:${seconds.pad()}"
        }
    }

    private fun Int.pad(): String = if (this < TEN) "0$this" else toString()

    private fun oneDecimal(value: Double): String {
        val scaled = (value * TEN).roundToInt()
        return "${scaled / TEN}.${scaled % TEN}"
    }

    private const val PERCENT = 100L
    private const val TEN = 10
    private const val MILLIS_PER_SECOND = 1_000L
    private const val SECONDS_PER_MINUTE = 60
    private const val SECONDS_PER_HOUR = 3_600
}
