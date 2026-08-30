package app.trimgallery.core.ui.theme

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrimPaletteTest {

    private fun luminance(argb: Long): Double {
        fun channel(shift: Int) = ((argb shr shift) and 0xFF).toDouble() / 255.0
        // sRGB relative luminance, with the standard 2.4 gamma.
        fun g(c: Double) = if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        return 0.2126 * g(channel(16)) + 0.7152 * g(channel(8)) + 0.0722 * g(channel(0))
    }

    private fun contrast(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun `dark is the default`() {
        assertEquals(TrimPalette.Dark, TrimPalette.of(dark = true))
        assertEquals(TrimPalette.Light, TrimPalette.of(dark = false))
    }

    @Test
    fun `the page is near-black, not black`() {
        // Pure black would swallow a photo's own dark tones and erase the boundary
        // between chrome and image on OLED (BUILD.md section 9).
        val page = TrimPalette.Dark.page and 0xFFFFFF
        assertTrue(page > 0x000000, "page must not be pure black")
        assertTrue(luminance(TrimPalette.Dark.page) < 0.02, "page must still read as near-black")
    }

    @Test
    fun `primary text is comfortably readable on the page in both themes`() {
        // WCAG AA for body text is 4.5:1; chrome over media deserves more headroom.
        listOf(TrimPalette.Dark, TrimPalette.Light).forEach { p ->
            assertTrue(contrast(p.text, p.page) >= 7.0, "text/page contrast was ${contrast(p.text, p.page)}")
        }
    }

    @Test
    fun `muted text still clears the AA threshold`() {
        listOf(TrimPalette.Dark, TrimPalette.Light).forEach { p ->
            assertTrue(contrast(p.muted, p.page) >= 4.5, "muted/page contrast was ${contrast(p.muted, p.page)}")
        }
    }

    @Test
    fun `surfaces step up from the page rather than sitting flat on it`() {
        listOf(TrimPalette.Dark, TrimPalette.Light).forEach { p ->
            assertTrue(
                abs(luminance(p.card) - luminance(p.page)) > 0.001,
                "card must be distinguishable from page",
            )
        }
    }

    @Test
    fun `there is exactly one accent, shared by both themes`() {
        // BUILD.md section 9: "One typeface, one accent colour."
        assertEquals(TrimPalette.Light.accent, TrimPalette.Dark.accent)
    }

    @Test
    fun `the accent is visible against both page colours`() {
        listOf(TrimPalette.Dark, TrimPalette.Light).forEach { p ->
            assertTrue(contrast(p.accent, p.page) >= 3.0, "accent/page contrast was ${contrast(p.accent, p.page)}")
        }
    }

    @Test
    fun `dark needs a weaker glow than light`() {
        assertTrue(TrimPalette.Dark.glowAlpha < TrimPalette.Light.glowAlpha)
    }

    @Test
    fun `the veil is translucent so the grid stays legible behind it`() {
        listOf(TrimPalette.Dark, TrimPalette.Light).forEach { p ->
            val alpha = (p.veil ushr 24) and 0xFF
            assertTrue(alpha in 0xC0..0xF0, "veil alpha was ${alpha.toString(16)}")
        }
    }

    @Test
    fun `every token is fully opaque except the veil`() {
        listOf(TrimPalette.Dark, TrimPalette.Light).forEach { p ->
            listOf(p.page, p.band, p.card, p.text, p.muted, p.line, p.accent).forEach { c ->
                assertEquals(0xFFL, (c ushr 24) and 0xFF, "token ${c.toString(16)} should be opaque")
            }
        }
    }
}
