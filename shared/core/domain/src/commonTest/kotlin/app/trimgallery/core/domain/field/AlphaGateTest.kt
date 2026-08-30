package app.trimgallery.core.domain.field

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AlphaGateTest {

    private fun summary(
        nights: Int = 10,
        videoSaving: Double? = 0.42,
        restoreRate: Double? = 0.005,
        thermalPauses: Int = 5,
    ) = FieldMetrics.Summary(
        nights = nights,
        filesDone = 100,
        filesSkipped = 10,
        filesFailed = 1,
        bytesFreed = 20L * 1024 * 1024 * 1024,
        minutesWorked = 300.0,
        energyWh = 90.0,
        filesIndexed = 4_000,
        duplicatesFound = 30,
        thermalPauses = thermalPauses,
        medianVideoSaving = videoSaving,
        medianPhotoSaving = 0.2,
        vmafScores = listOf(95.5, 96.0, 97.2),
        medianRealtimeMultiple = 3.5,
        restoreRate = restoreRate,
    )

    private fun threeGood() = mapOf("a" to summary(), "b" to summary(), "c" to summary())

    private fun criterion(result: AlphaGate.Result, name: String) = result.criteria.single { it.name == name }

    @Test
    fun `three healthy devices pass`() {
        val result = AlphaGate.evaluate(threeGood())
        assertTrue(result.passed, result.report())
        assertTrue(result.failures.isEmpty())
        assertTrue(result.report().startsWith("Alpha gate: PASSED"))
    }

    /** LAUNCH.md says three or more devices; fewer is not a field test. */
    @Test
    fun `two devices are not a field test`() {
        val result = AlphaGate.evaluate(mapOf("a" to summary(), "b" to summary()))
        assertFalse(result.passed)
        assertFalse(criterion(result, "Devices tested").passed)
    }

    @Test
    fun `one unusual night cannot carry a device`() {
        val result = AlphaGate.evaluate(threeGood() + ("d" to summary(nights = 2)))
        assertFalse(criterion(result, "Nights per device").passed)
        assertEquals(2.0, criterion(result, "Nights per device").measured)
    }

    // ------------------------------------------------------------- the numbers

    @Test
    fun `LAUNCH's thresholds are the ones enforced`() {
        assertEquals(0.30, AlphaGate.MIN_MEDIAN_VIDEO_SAVING)
        assertEquals(0.02, AlphaGate.MAX_RESTORE_RATE)
        assertEquals(3, AlphaGate.MIN_DEVICES)
    }

    @Test
    fun `a saving exactly at the threshold passes and a hair under does not`() {
        assertTrue(
            AlphaGate.evaluate(
                mapOf("a" to summary(videoSaving = 0.30), "b" to summary(), "c" to summary()),
            ).passed,
        )
        assertFalse(
            AlphaGate.evaluate(
                mapOf("a" to summary(videoSaving = 0.299), "b" to summary(), "c" to summary()),
            ).passed,
        )
    }

    /** *"restore rate < 2%"* — strictly under, so exactly 2% fails. */
    @Test
    fun `the restore rate is a strict bound`() {
        assertFalse(
            AlphaGate.evaluate(
                mapOf("a" to summary(restoreRate = 0.02), "b" to summary(), "c" to summary()),
            ).passed,
        )
        assertTrue(
            AlphaGate.evaluate(
                mapOf("a" to summary(restoreRate = 0.019), "b" to summary(), "c" to summary()),
            ).passed,
        )
    }

    /**
     * The point of testing on three devices is to find the one that behaves differently.
     * Pooling would let two good phones carry a bad one — which is the failure mode
     * LAUNCH.md's "3+ devices" exists to catch.
     */
    @Test
    fun `the worst device decides, not the average`() {
        val result = AlphaGate.evaluate(
            mapOf(
                "fast" to summary(videoSaving = 0.55),
                "ok" to summary(videoSaving = 0.45),
                "slow" to summary(videoSaving = 0.12),
            ),
        )
        assertFalse(result.passed)
        val failed = criterion(result, "Median video saving")
        assertEquals(0.12, failed.measured)
        assertTrue(failed.explanation.contains("FAILS"), failed.explanation)
    }

    @Test
    fun `a phone fighting the thermal gate fails`() {
        val result = AlphaGate.evaluate(
            threeGood() + ("hot" to summary(nights = 10, thermalPauses = 40)),
        )
        assertFalse(criterion(result, "Thermal pauses per night").passed)
        assertEquals(4.0, criterion(result, "Thermal pauses per night").measured)
    }

    @Test
    fun `pausing for heat now and then is the gate working, not failing`() {
        val result = AlphaGate.evaluate(threeGood() + ("warm" to summary(nights = 10, thermalPauses = 20)))
        assertTrue(criterion(result, "Thermal pauses per night").passed, result.report())
    }

    // --------------------------------------------------------- missing evidence

    /**
     * A build that shipped because nobody measured the restore rate is the exact failure
     * this gate exists to prevent. Silence is not a pass.
     */
    @Test
    fun `an unmeasured criterion fails`() {
        val result = AlphaGate.evaluate(
            mapOf("a" to summary(restoreRate = null), "b" to summary(), "c" to summary()),
        )
        assertFalse(result.passed)
        val restore = criterion(result, "Restore rate")
        assertFalse(restore.passed)
        assertTrue(restore.incomplete)
        assertTrue(restore.explanation.contains("not measured on every device"), restore.explanation)
        assertTrue(restore.explanation.contains("2 of 3"), restore.explanation)
    }

    /**
     * A criterion can fail for two quite different reasons and the report has to say which.
     * Blaming the number when the real problem is missing evidence sends someone off to fix
     * a build that was never wrong.
     */
    @Test
    fun `missing evidence does not read as a bad number`() {
        val result = AlphaGate.evaluate(
            mapOf("a" to summary(restoreRate = null), "b" to summary(), "c" to summary()),
        )
        val restore = criterion(result, "Restore rate")
        assertFalse(restore.explanation.contains("FAILS"), restore.explanation)
    }

    /** A restore rate is a small fraction; rounding 0.5% to "0" looks like missing data. */
    @Test
    fun `small rates keep enough precision to be read`() {
        val report = AlphaGate.evaluate(threeGood()).report()
        assertTrue(report.contains("0.005"), report)
    }

    @Test
    fun `no devices at all fails everything`() {
        val result = AlphaGate.evaluate(emptyMap())
        assertFalse(result.passed)
        assertEquals(result.criteria.size, result.failures.size)
    }

    /** A failing gate has to say what failed and by how much, or the "no" is useless. */
    @Test
    fun `the report names every criterion with its number`() {
        val report = AlphaGate.evaluate(threeGood()).report()
        for (name in listOf(
            "Devices tested",
            "Nights per device",
            "Median video saving",
            "Restore rate",
            "Thermal pauses per night",
        )) {
            assertTrue(report.contains(name), "missing $name in:\n$report")
        }
    }
}
