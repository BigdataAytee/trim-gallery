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
    fun `the accent is one hue, tuned per theme rather than reused as one value`() {
        // DESIGN_SYSTEM.md gives mint on dark and a darker green on light. One accent
        // colour (BUILD.md section 9) means one hue, not one hex: mint on paper fails
        // contrast, and a palette that fails contrast is not a palette.
        assertTrue(contrast(TrimPalette.Light.accent, TrimPalette.Light.page) >= 3.0)
        assertTrue(contrast(TrimPalette.Dark.accent, TrimPalette.Dark.page) >= 3.0)
    }

    @Test
    fun `what sits on the accent is readable against it`() {
        listOf(TrimPalette.Dark, TrimPalette.Light).forEach { p ->
            assertTrue(
                contrast(p.accentOn, p.accent) >= 4.5,
                "accentOn/accent contrast was ${contrast(p.accentOn, p.accent)}",
            )
        }
    }

    @Test
    fun `danger and warning are distinguishable from the accent and from each other`() {
        listOf(TrimPalette.Dark, TrimPalette.Light).forEach { p ->
            assertTrue(p.danger != p.warning && p.danger != p.accent)
            assertTrue(contrast(p.danger, p.page) >= 3.0, "danger/page was ${contrast(p.danger, p.page)}")
            assertTrue(contrast(p.warning, p.page) >= 3.0, "warning/page was ${contrast(p.warning, p.page)}")
        }
    }

    @Test
    fun `chrome is the surface at 85 percent, so media shows through it`() {
        listOf(TrimPalette.Dark, TrimPalette.Light).forEach { p ->
            assertEquals(0xD9L, (p.chrome ushr 24) and 0xFF)
            assertEquals(p.band and 0xFFFFFF, p.chrome and 0xFFFFFF)
        }
    }

    @Test
    fun `hairlines are the text colour at 8 percent, not a solid grey`() {
        // DESIGN_SYSTEM.md: elevation is blur plus a 1-px hairline, never a shadow. A
        // solid line would band visibly over media.
        listOf(TrimPalette.Dark, TrimPalette.Light).forEach { p ->
            assertEquals(p.text and 0xFFFFFF, p.line and 0xFFFFFF)
            assertEquals(0x14L, (p.line ushr 24) and 0xFF)
        }
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
    fun `the scrim is translucent, and heavier on dark than on light`() {
        // rgba(0,0,0,.6) against rgba(0,0,0,.4): a dark UI needs more separation between
        // the viewer and the grid behind it, because both are already dark.
        assertTrue((TrimPalette.Dark.scrim ushr 24) and 0xFF > (TrimPalette.Light.scrim ushr 24) and 0xFF)
        listOf(TrimPalette.Dark, TrimPalette.Light).forEach { p ->
            val alpha = (p.scrim ushr 24) and 0xFF
            assertTrue(alpha in 0x40..0xC0, "scrim alpha was ${alpha.toString(16)}")
        }
    }

    @Test
    fun `every solid token is fully opaque`() {
        listOf(TrimPalette.Dark, TrimPalette.Light).forEach { p ->
            listOf(p.page, p.band, p.card, p.text, p.muted, p.accent, p.accentOn, p.danger, p.warning).forEach { c ->
                assertEquals(0xFFL, (c ushr 24) and 0xFF, "token ${c.toString(16)} should be opaque")
            }
        }
    }
}
