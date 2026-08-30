package app.trimgallery.core.ui.theme

/**
 * The colour system, as plain ARGB values.
 *
 * BUILD.md § 9: *"Dark by default, media on near-black, chrome fades when idle. One
 * typeface, one accent colour."* So this is **not** the buyer-gallery palette — that
 * reference is cream and light-first, and its motion is what transfers, not its colours.
 * The token *structure* is kept (page / band / card / text / muted / line / accent) so
 * the two stay legible side by side.
 *
 * Near-black rather than pure black: #000000 makes a photo's own dark tones vanish into
 * the background, and on OLED the boundary between chrome and image disappears entirely.
 *
 * Plain `Long`s rather than Compose `Color` so the palette can be asserted in a JVM test
 * and reused by anything that is not a composable.
 */
data class TrimPalette(
    /** Page background. Media sits directly on this. */
    val page: Long,
    /** Bars and sheets: one step up from the page. */
    val band: Long,
    /** Cards, tiles and controls. */
    val card: Long,
    /** Primary text and icons. */
    val text: Long,
    /** Secondary text, inactive controls. */
    val muted: Long,
    /** Hairlines and inactive outlines. */
    val line: Long,
    /** The single accent. Used for progress, selection and focus — nothing decorative. */
    val accent: Long,
    /** Multiplier on the breathing halo; dark needs less to read as bright. */
    val glowAlpha: Float,
    /** Backdrop behind an open viewer. */
    val veil: Long,
) {
    companion object {
        /**
         * The default. BUILD.md § 9 opens dark, and the app is used at night by design.
         */
        val Dark = TrimPalette(
            page = 0xFF0B0B0D,
            band = 0xFF141417,
            card = 0xFF1A1A1E,
            text = 0xFFF2F1EE,
            muted = 0xFF8A8A93,
            line = 0xFF2A2A30,
            accent = ACCENT,
            glowAlpha = 0.40f,
            veil = 0xD10B0B0D,
        )

        val Light = TrimPalette(
            page = 0xFFFAFAF8,
            band = 0xFFF1F1EE,
            card = 0xFFFFFFFF,
            text = 0xFF16161A,
            muted = 0xFF6E6E76,
            line = 0xFFDEDEDA,
            accent = ACCENT,
            glowAlpha = 0.55f,
            veil = 0xD1FAFAF8,
        )

        fun of(dark: Boolean): TrimPalette = if (dark) Dark else Light
    }
}

/**
 * One accent colour, shared by both themes so a screenshot of either is recognisably the
 * same app. It matches the launcher icon rather than being picked independently.
 */
private const val ACCENT = 0xFF7C5CFF
