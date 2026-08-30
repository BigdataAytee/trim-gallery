package app.trimgallery.core.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SettingSearchTest {

    private val bounds = SettingSearch.Bounds(lowBps = 1_000_000, highBps = 20_000_000, startBps = 10_000_000)
    private val threshold = 40.0

    /**
     * A stand-in encoder whose quality rises with bitrate, the property milestone 2's
     * metric tests assert on the real thing.
     *
     * [trueCutoff] is the lowest bitrate that clears [threshold], so a test can state the
     * answer and check the search finds it.
     */
    private class Curve(private val trueCutoff: Int, private val slopeDb: Double = 8.0) : SettingSearch.Scorer {
        val tried = mutableListOf<Int>()

        override suspend fun score(bitrateBps: Int): Double {
            tried += bitrateBps
            // Logarithmic in bitrate, like a real rate-quality curve, centred so that
            // exactly `trueCutoff` scores the threshold.
            val ratio = bitrateBps.toDouble() / trueCutoff
            return 40.0 + slopeDb * kotlin.math.log2(ratio)
        }
    }

    @Test
    fun `it finds the cheapest setting that clears the threshold`() = runTest {
        val curve = Curve(trueCutoff = 4_000_000)
        val outcome = SettingSearch().search(bounds, threshold, curve)

        val found = assertIs<SettingSearch.Outcome.Found>(outcome)
        assertTrue(found.score >= threshold, "chose a setting below threshold: ${found.score}")
        assertTrue(
            found.bitrateBps in 4_000_000..6_000_000,
            "expected close to the 4 Mbps cutoff, got ${found.bitrateBps}",
        )
    }

    @Test
    fun `it never returns a setting below the threshold`() = runTest {
        // The whole point: shipping a file the user can see is worse is the one outcome
        // the app must never produce.
        listOf(1_200_000, 2_500_000, 4_000_000, 9_000_000, 15_000_000).forEach { cutoff ->
            val outcome = SettingSearch().search(bounds, threshold, Curve(cutoff))
            if (outcome is SettingSearch.Outcome.Found) {
                assertTrue(outcome.score >= threshold, "cutoff=$cutoff score=${outcome.score}")
            }
        }
    }

    @Test
    fun `the probe budget is a hard cap`() = runTest {
        // BUILD.md expects 3-4 without a prediction; the cap stops a pathological file
        // eating the night's budget.
        listOf(1, 2, 3, 4, 6).forEach { budget ->
            val curve = Curve(trueCutoff = 3_300_000)
            SettingSearch(SettingSearch.Config(maxProbes = budget)).search(bounds, threshold, curve)
            assertTrue(curve.tried.size <= budget, "budget=$budget used ${curve.tried.size}")
        }
    }

    @Test
    fun `a typical search costs three or four probes`() = runTest {
        val curve = Curve(trueCutoff = 3_300_000)
        val outcome = SettingSearch().search(bounds, threshold, curve)
        assertIs<SettingSearch.Outcome.Found>(outcome)
        assertTrue(curve.tried.size in 2..4, "used ${curve.tried.size} probes: ${curve.tried}")
    }

    @Test
    fun `an easy file exits early to the low bound`() = runTest {
        // BUILD.md: "if the first probe is far above threshold, jump to the low bound".
        val curve = Curve(trueCutoff = 900_000) // clears even at the bottom of the bracket
        val outcome = SettingSearch().search(bounds, threshold, curve)

        val found = assertIs<SettingSearch.Outcome.Found>(outcome)
        assertEquals(bounds.lowBps, found.bitrateBps)
        assertEquals(2, curve.tried.size, "should be start then low bound, was ${curve.tried}")
        assertEquals(listOf(bounds.startBps, bounds.lowBps), curve.tried)
    }

    @Test
    fun `an easy file whose low bound still fails is bisected, not abandoned`() = runTest {
        val curve = Curve(trueCutoff = 2_000_000)
        val outcome = SettingSearch().search(bounds, threshold, curve)
        val found = assertIs<SettingSearch.Outcome.Found>(outcome)
        assertTrue(found.bitrateBps > bounds.lowBps)
        assertTrue(found.score >= threshold)
    }

    @Test
    fun `a file that cannot reach the threshold is reported, not fudged`() = runTest {
        // Nothing in the bracket is good enough: the caller skips the file.
        val curve = Curve(trueCutoff = 400_000_000)
        val outcome = SettingSearch().search(bounds, threshold, curve)

        val unreachable = assertIs<SettingSearch.Outcome.NotReachable>(outcome)
        assertTrue(unreachable.bestScore < threshold)
        assertTrue(curve.tried.contains(bounds.highBps), "should have tried the top of the bracket")
    }

    @Test
    fun `it tries the top of the bracket before bisecting upward`() = runTest {
        // The top is the only setting that can rescue the file; bisecting a range with no
        // answer would spend the whole budget finding that out.
        val curve = Curve(trueCutoff = 400_000_000)
        SettingSearch().search(bounds, threshold, curve)
        assertEquals(bounds.highBps, curve.tried[1], "tried ${curve.tried}")
    }

    @Test
    fun `a hard file that only the top of the bracket satisfies still succeeds`() = runTest {
        val curve = Curve(trueCutoff = 19_000_000)
        val outcome = SettingSearch().search(bounds, threshold, curve)
        val found = assertIs<SettingSearch.Outcome.Found>(outcome)
        assertTrue(found.score >= threshold)
    }

    @Test
    fun `it never probes the same bitrate twice`() = runTest {
        listOf(900_000, 2_000_000, 4_000_000, 19_000_000, 400_000_000).forEach { cutoff ->
            val curve = Curve(cutoff)
            SettingSearch().search(bounds, threshold, curve)
            assertEquals(curve.tried.size, curve.tried.toSet().size, "repeats for cutoff=$cutoff: ${curve.tried}")
        }
    }

    @Test
    fun `every probe stays inside the bracket`() = runTest {
        listOf(900_000, 3_000_000, 400_000_000).forEach { cutoff ->
            val curve = Curve(cutoff)
            SettingSearch().search(bounds, threshold, curve)
            curve.tried.forEach {
                assertTrue(it in bounds.lowBps..bounds.highBps, "probed $it outside the bracket")
            }
        }
    }

    @Test
    fun `a narrow bracket converges without wasting probes`() = runTest {
        // What a confident prediction produces: the search should confirm, not re-derive.
        val narrow = SettingSearch.Bounds(lowBps = 4_100_000, highBps = 5_900_000, startBps = 5_000_000)
        val curve = Curve(trueCutoff = 4_800_000)
        val outcome = SettingSearch().search(narrow, threshold, curve)

        assertIs<SettingSearch.Outcome.Found>(outcome)
        assertTrue(curve.tried.size <= 2, "a confident prediction should cost 1-2 probes, used ${curve.tried}")
    }

    @Test
    fun `a degenerate bracket is handled rather than looping`() = runTest {
        val single = SettingSearch.Bounds(lowBps = 5_000_000, highBps = 5_000_000, startBps = 5_000_000)
        val curve = Curve(trueCutoff = 1_000_000)
        val outcome = SettingSearch().search(single, threshold, curve)
        assertIs<SettingSearch.Outcome.Found>(outcome)
        assertEquals(1, curve.tried.size)
    }

    @Test
    fun `probes are reported in bitrate order for the job record`() = runTest {
        val curve = Curve(trueCutoff = 3_300_000)
        val outcome = SettingSearch().search(bounds, threshold, curve)
        val found = assertIs<SettingSearch.Outcome.Found>(outcome)
        assertEquals(found.probes.map { it.bitrateBps }.sorted(), found.probes.map { it.bitrateBps })
    }
}
