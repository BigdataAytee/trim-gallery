package app.trimgallery.core.pipeline.photo

import app.trimgallery.core.model.QualityTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * BUILD.md § 5: *"Gate with SSIMULACRA2 ≥ 85–90. Full binary search per file."*
 */
class PhotoQualitySearchTest {

    /** A well-behaved image: score rises with quality, crossing the gate at [crossover]. */
    private fun scorer(crossover: Int, tried: MutableList<Int> = mutableListOf()) =
        PhotoQualitySearch.Scorer { quality ->
            tried += quality
            // A smooth curve through the crossover, so equality cases are exercised.
            90.0 + (quality - crossover) * 0.5
        }

    @Test
    fun `the targets map the two settings onto SSIMULACRA2's scale`() {
        val search = PhotoQualitySearch()
        assertEquals(90.0, search.targetFor(QualityTarget.STANDARD))
        assertEquals(85.0, search.targetFor(QualityTarget.COMPACT))
    }

    @Test
    fun `it finds the lowest quality that clears the gate`() = runTest {
        val tried = mutableListOf<Int>()
        val outcome = PhotoQualitySearch().search(90.0, scorer(crossover = 78, tried = tried))

        val found = assertIs<PhotoQualitySearch.Outcome.Found>(outcome)
        assertTrue(found.score >= 90.0, "the winner must clear the gate")
        // Within the convergence width of the true crossover.
        assertTrue(found.quality in 78..80, "settled on ${found.quality}, expected about 78")
    }

    @Test
    fun `it does not settle on something below the gate`() = runTest {
        // The one outcome that must never happen: replacing a photograph with a copy the
        // user can see is worse.
        (60..92 step 4).forEach { crossover ->
            val outcome = PhotoQualitySearch().search(90.0, scorer(crossover))
            if (outcome is PhotoQualitySearch.Outcome.Found) {
                assertTrue(outcome.score >= 90.0, "crossover $crossover settled at ${outcome.score}")
            }
        }
    }

    @Test
    fun `an image that cannot clear the gate at any quality is left alone`() = runTest {
        val outcome = PhotoQualitySearch().search(90.0) { 40.0 }
        val unreachable = assertIs<PhotoQualitySearch.Outcome.NotReachable>(outcome)
        assertEquals(40.0, unreachable.bestScore)
    }

    @Test
    fun `the top of the range is actually tried before giving up`() = runTest {
        // Otherwise an image whose crossover sits above the first probe but below the
        // ceiling would be reported unreachable without the ceiling ever being measured.
        val tried = mutableListOf<Int>()
        val config = PhotoQualitySearch.Config(maxQuality = 95)
        val outcome = PhotoQualitySearch(config).search(90.0, scorer(crossover = 94, tried = tried))

        assertTrue(95 in tried, "the ceiling was never tried: $tried")
        assertIs<PhotoQualitySearch.Outcome.Found>(outcome)
    }

    @Test
    fun `a typical phone JPEG converges in a handful of probes`() = runTest {
        // BUILD.md § 5 calls this "milliseconds each", but a search that spent thirty
        // probes on every photo in a library would still be the slow part of the night.
        val tried = mutableListOf<Int>()
        PhotoQualitySearch().search(90.0, scorer(crossover = 84, tried = tried))
        assertTrue(tried.size <= 5, "took ${tried.size} probes: $tried")
    }

    @Test
    fun `the probe budget is a hard cap`() = runTest {
        var calls = 0
        val config = PhotoQualitySearch.Config(maxProbes = 3)
        // A pathological scorer that never converges.
        PhotoQualitySearch(config).search(90.0) { calls += 1; if (calls % 2 == 0) 95.0 else 10.0 }
        assertTrue(calls <= 4, "spent $calls probes against a budget of 3 plus the ceiling")
    }

    @Test
    fun `every probe is inside the configured range`() = runTest {
        val tried = mutableListOf<Int>()
        val config = PhotoQualitySearch.Config(minQuality = 50, maxQuality = 90, startQuality = 70)
        PhotoQualitySearch(config).search(90.0, scorer(crossover = 65, tried = tried))
        tried.forEach { assertTrue(it in 50..90, "probed $it, outside 50..90") }
    }

    @Test
    fun `an incoherent configuration is refused rather than searched`() {
        listOf(
            { PhotoQualitySearch.Config(minQuality = 90, maxQuality = 50) },
            { PhotoQualitySearch.Config(minQuality = 40, maxQuality = 95, startQuality = 99) },
            { PhotoQualitySearch.Config(minQuality = 0, maxQuality = 95) },
        ).forEach { build ->
            var threw = false
            try {
                build()
            } catch (e: IllegalArgumentException) {
                threw = true
            }
            assertTrue(threw, "an invalid config was accepted")
        }
    }
}
