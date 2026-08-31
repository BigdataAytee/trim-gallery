package app.trimgallery.core.ui.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaFormattingTest {

    @Test
    fun `sizes use the same decimal units the phone shows`() {
        // 2^20 would make the app disagree with the file manager for the same file.
        assertEquals("500 B", MediaFormatting.bytes(500))
        assertEquals("1 KB", MediaFormatting.bytes(1_000))
        assertEquals("380 MB", MediaFormatting.bytes(380_000_000))
        assertEquals("6.2 GB", MediaFormatting.bytes(6_200_000_000))
    }

    @Test
    fun `the optimised line reads exactly as BUILD md section 9 specifies`() {
        assertEquals(
            "Optimised · was 380 MB, now 165 MB",
            MediaFormatting.optimisedLine(380_000_000, 165_000_000),
        )
    }

    @Test
    fun `an untouched file has no optimised line at all`() {
        // Null rather than empty, so a caller cannot render a blank row where a fact goes.
        assertNull(MediaFormatting.optimisedLine(null, null))
        assertNull(MediaFormatting.optimisedLine(380_000_000, null))
    }

    @Test
    fun `a file that did not shrink is never described as optimised`() {
        assertNull(MediaFormatting.optimisedLine(100, 100))
        assertNull(MediaFormatting.optimisedLine(100, 120))
    }

    @Test
    fun `freed reads as the Space screen expects`() {
        assertEquals("Freed 6.2 GB", MediaFormatting.freedLine(6_200_000_000))
    }

    @Test
    fun `a night that freed nothing does not claim to have freed nothing`() {
        // "Freed 0 B" dresses up a failure as an achievement.
        assertNull(MediaFormatting.freedLine(0))
        assertNull(MediaFormatting.freedLine(-10))
    }

    @Test
    fun `the saved percentage rounds down so the app never overstates`() {
        // 56.6% must read as 56, not 57.
        assertEquals(56, MediaFormatting.savedPercent(380_000_000, 165_000_000))
        assertEquals(0, MediaFormatting.savedPercent(100, 100))
        assertEquals(0, MediaFormatting.savedPercent(0, 0))
        assertEquals(0, MediaFormatting.savedPercent(100, 150))
    }

    @Test
    fun `durations pad seconds and add hours only when needed`() {
        assertEquals("0:00", MediaFormatting.duration(0))
        assertEquals("0:08", MediaFormatting.duration(8_000))
        assertEquals("1:05", MediaFormatting.duration(65_000))
        assertEquals("1:00:00", MediaFormatting.duration(3_600_000))
        assertEquals("2:03:04", MediaFormatting.duration(7_384_000))
    }

    @Test
    fun `a negative size does not print a stray minus inside the unit`() {
        assertTrue(MediaFormatting.bytes(-5_000).startsWith("5"))
    }
}
