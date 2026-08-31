package app.trimgallery.core.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The design system for every Trim Gallery screen (ARCHITECTURE.md § 11).
 *
 * Built on Compose foundation rather than Material 3. BUILD.md § 9 specifies the look
 * directly — dark by default, media on near-black, **one typeface, one accent colour** —
 * and Material's theming, dynamic colour and component defaults would all have to be
 * overridden to reach it. A gallery is mostly bespoke surfaces over photographs; there is
 * little for Material to contribute and a lot for it to get in the way of.
 */
@Immutable
data class TrimColors(
    val page: Color,
    val band: Color,
    val card: Color,
    val text: Color,
    val muted: Color,
    val line: Color,
    val accent: Color,
    val accentOn: Color,
    val danger: Color,
    val warning: Color,
    val chrome: Color,
    val glowAlpha: Float,
    val scrim: Color,
) {
    companion object {
        fun from(palette: TrimPalette) = TrimColors(
            page = Color(palette.page),
            band = Color(palette.band),
            card = Color(palette.card),
            text = Color(palette.text),
            muted = Color(palette.muted),
            line = Color(palette.line),
            accent = Color(palette.accent),
            accentOn = Color(palette.accentOn),
            danger = Color(palette.danger),
            warning = Color(palette.warning),
            chrome = Color(palette.chrome),
            glowAlpha = palette.glowAlpha,
            scrim = Color(palette.scrim),
        )
    }
}

/**
 * One typeface, six roles (DESIGN_SYSTEM.md § Typography). BUILD.md § 9 asks for a single
 * family; the scale does the work that extra families usually would.
 */
@Immutable
data class TrimTypography(
    val display: TextStyle,
    val title: TextStyle,
    val heading: TextStyle,
    val body: TextStyle,
    val label: TextStyle,
    val caption: TextStyle,
) {
    companion object {
        /**
         * Built from [TrimType], so the scale lives in one Compose-free place and is
         * asserted on the JVM rather than eyeballed.
         *
         * Tabular figures throughout (DESIGN_SYSTEM.md § Typography): the morning card
         * counts "Freed 6.2 GB" up over 800 ms, and proportional digits make the whole
         * line jitter while it does.
         */
        fun from(family: FontFamily) = TrimTypography(
            display = style(family, TrimType.DISPLAY),
            title = style(family, TrimType.TITLE),
            heading = style(family, TrimType.HEADING),
            body = style(family, TrimType.BODY),
            label = style(family, TrimType.LABEL),
            caption = style(family, TrimType.CAPTION),
        )

        private fun style(family: FontFamily, role: TrimType) = TextStyle(
            fontFamily = family,
            fontSize = role.size.sp,
            lineHeight = role.lineHeight.sp,
            fontWeight = FontWeight(role.weight),
            fontFeatureSettings = TABULAR_FIGURES,
        )

        private const val TABULAR_FIGURES = "tnum"
    }
}

val LocalTrimColors: ProvidableCompositionLocal<TrimColors> =
    staticCompositionLocalOf { TrimColors.from(TrimPalette.Dark) }

val LocalTrimTypography: ProvidableCompositionLocal<TrimTypography> =
    staticCompositionLocalOf { TrimTypography.from(FontFamily.SansSerif) }

/**
 * Whether the viewer has asked for reduced motion.
 *
 * A composition local rather than an `expect`/`actual`: the platform host reads its own
 * accessibility setting and provides it, which keeps this module free of platform code
 * and lets a test drive both paths without a device.
 */
val LocalReduceMotion: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }

object TrimTheme {
    val colors: TrimColors
        @Composable get() = LocalTrimColors.current

    val typography: TrimTypography
        @Composable get() = LocalTrimTypography.current

    val reduceMotion: Boolean
        @Composable get() = LocalReduceMotion.current
}

/**
 * @param dark defaults to true — BUILD.md § 9 opens dark, and the app is used at night
 *   by design. A setting flips it; the system preference does not, because a gallery
 *   that follows the OS would show the user's photos on a different ground each morning.
 */
@Composable
fun TrimTheme(
    dark: Boolean = true,
    reduceMotion: Boolean = false,
    fontFamily: FontFamily = FontFamily.SansSerif,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalTrimColors provides TrimColors.from(TrimPalette.of(dark)),
        LocalTrimTypography provides TrimTypography.from(fontFamily),
        LocalReduceMotion provides reduceMotion,
        content = content,
    )
}
