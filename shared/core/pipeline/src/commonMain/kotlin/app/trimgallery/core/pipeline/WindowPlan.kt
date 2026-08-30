package app.trimgallery.core.pipeline

/**
 * Which slices of a video get decoded, and at what size.
 *
 * BUILD.md § 5 is specific: the search decodes *one* 5-second window from the middle
 * (three for files over three minutes), scores at 720p, and — the part that actually
 * makes the search affordable — decodes that window **once** and reuses it for every
 * candidate setting. Verification then samples three windows at the start, middle and
 * end.
 *
 * Pure arithmetic, so the edges are testable: a clip shorter than one window, a clip too
 * short to hold three non-overlapping windows, a clip of exactly the threshold length.
 */
object WindowPlan {

    /** BUILD.md § 5. Long enough to contain motion, short enough to decode quickly. */
    const val WINDOW_MS = 5_000L

    /** Above this, one window in the middle is no longer representative. */
    const val MULTI_WINDOW_THRESHOLD_MS = 180_000L

    /**
     * Scoring height. BUILD.md § 5: *"Score with XPSNR at 720p."*
     *
     * Scoring at source resolution would make the search cost scale with the very files
     * that most need it, and XPSNR's own block sizing already adapts to resolution, so a
     * fixed scoring height keeps thresholds comparable across a mixed library.
     */
    const val SCORING_HEIGHT = 720

    /** A slice of the source, in presentation time. */
    data class Window(val startMs: Long, val lengthMs: Long) {
        val endMs: Long get() = startMs + lengthMs
    }

    /**
     * Windows to decode for the search.
     *
     * One from the middle, or three spread across the file once it is long enough that
     * the middle stops standing for the whole thing.
     */
    fun probeWindows(durationMs: Long): List<Window> = when {
        durationMs <= 0 -> emptyList()
        durationMs > MULTI_WINDOW_THRESHOLD_MS -> spread(durationMs, count = 3)
        else -> listOf(centred(durationMs))
    }

    /**
     * Windows to verify against (BUILD.md § 5): start, middle and end.
     *
     * Three regardless of length: a re-encode can fail in one part of a file and hold up
     * elsewhere — a hard cut, a dark scene — and verifying only the middle would miss it.
     */
    fun verifyWindows(durationMs: Long): List<Window> =
        if (durationMs <= 0) emptyList() else spread(durationMs, count = 3)

    /** One window centred on the file, clamped to what actually exists. */
    private fun centred(durationMs: Long): Window {
        val length = minOf(WINDOW_MS, durationMs)
        val start = ((durationMs - length) / 2).coerceAtLeast(0)
        return Window(start, length)
    }

    /**
     * [count] windows spread evenly, without running past the end.
     *
     * When the file cannot hold that many without overlapping, fewer are returned rather
     * than overlapping ones: scoring the same frames twice would weight them twice in the
     * average and quietly bias the result.
     */
    private fun spread(durationMs: Long, count: Int): List<Window> {
        val length = minOf(WINDOW_MS, durationMs)
        if (durationMs < length * count) {
            val fits = (durationMs / length).toInt().coerceAtLeast(1)
            if (fits <= 1) return listOf(centred(durationMs))
            return spread(durationMs, fits)
        }

        // Evenly spaced starts, the last one ending exactly at the end of the file.
        val span = durationMs - length
        return (0 until count).map { i ->
            val start = if (count == 1) span / 2 else span * i / (count - 1)
            Window(start, length)
        }
    }

    /** Total frames a plan will decode, for logging and for the nightly cap. */
    fun totalMs(windows: List<Window>): Long = windows.sumOf { it.lengthMs }
}
