package app.trimgallery.core.ui.theme

/**
 * The colour system, as plain ARGB values.
 *
 * The token table in DESIGN_SYSTEM.md § Colour, verbatim. Dark is the default, per that
 * document and BUILD.md § 9. This is **not** the buyer-gallery palette — that reference is
 * cream and light-first, and it was its *motion* that transferred, not its colours.
 *
 * Two things here are deliberate and easy to undo by accident. Near-black rather than pure
 * black: #000000 makes a photo's own dark tones vanish into the background, and on OLED
 * the boundary between chrome and image disappears entirely. And the accent is the one
 * token that differs between themes — mint reads as accent on near-black but fails
 * contrast on paper, so light gets a darker green of the same hue rather than the same
 * value used twice.
 *
 * Plain `Long`s rather than Compose `Color` so the palette can be asserted in a JVM test
 * and reused by anything that is not a composable.
 */
data class TrimPalette(
    /** `bg`. Page background; media sits directly on this. */
    val page: Long,
    /** `surface`. Bars and sheets, drawn at 85% with blur. */
    val band: Long,
    /** `surface-2`. Cards, tiles and controls. */
    val card: Long,
    /** `text`. Primary text and icons. */
    val text: Long,
    /** `text-muted`. Secondary text, inactive controls. */
    val muted: Long,
    /** Hairlines: `text` at 8%. Elevation is blur and a hairline, never a shadow. */
    val line: Long,
    /** `accent`. Progress, primary actions and "freed" numbers — nothing decorative. */
    val accent: Long,
    /** `accent-on`. What sits on top of the accent. */
    val accentOn: Long,
    /** `danger`. Destructive actions and expiry warnings. */
    val danger: Long,
    /** `warning`. "Paused for heat", cap nearly reached. Never used to alarm. */
    val warning: Long,
    /** `scrim`. Backdrop behind an open viewer or sheet. */
    val scrim: Long,
    /** Multiplier on the breathing halo; dark needs less to read as bright. */
    val glowAlpha: Float,
) {
    /** DESIGN_SYSTEM.md: chrome is `surface` at 85%, over blur. */
    val chrome: Long get() = (band and 0x00FFFFFF) or (CHROME_ALPHA shl 24)

    companion object {
        /**
         * The default. DESIGN_SYSTEM.md § Principles: *"Dark by default."* BUILD.md § 9
         * agrees, and the app is used at night by design.
         */
        val Dark = TrimPalette(
            page = 0xFF0B0B0C,
            band = 0xFF141416,
            card = 0xFF1E1E21,
            text = 0xFFF2F2F0,
            muted = 0xFF9A9A9F,
            line = 0x14F2F2F0, // text at 8%
            accent = 0xFF7CE7C4, // mint
            accentOn = 0xFF062018,
            danger = 0xFFFF6B6B,
            warning = 0xFFFFC857,
            scrim = 0x99000000, // rgba(0,0,0,.6)
            glowAlpha = 0.40f,
        )

        val Light = TrimPalette(
            page = 0xFFFAFAF8,
            band = 0xFFFFFFFF,
            card = 0xFFF1F1EE,
            text = 0xFF141416,
            muted = 0xFF6B6B70,
            line = 0x14141416,
            accent = 0xFF16A37B,
            // DESIGN_SYSTEM.md pairs this accent with #FFFFFF, but white on #16A37B is
            // 3.2:1 — below the 4.5:1 the same document requires for text, and a button
            // label is text. The accent itself is the brand colour and is kept exactly;
            // only the ink on it changes, to the same dark used on dark's mint, which
            // gives 5.3:1. Recorded in PROJECT.md.
            accentOn = 0xFF062018,
            danger = 0xFFD63B3B,
            warning = 0xFFB8860B,
            scrim = 0x66000000, // rgba(0,0,0,.4)
            glowAlpha = 0.55f,
        )

        fun of(dark: Boolean): TrimPalette = if (dark) Dark else Light

        private const val CHROME_ALPHA = 0xD9L // 85%
    }
}
