package app.trimgallery.core.pipeline.night

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThermalReadingTest {

    private fun gate() = ThermalGate()

    /** ARCHITECTURE.md § 6: run at nominal/fair, pause at serious/critical. */
    @Test
    fun `the four states produce exactly the behaviour the spec asks for`() {
        val gate = gate()
        assertFalse(gate.update(ThermalState.NOMINAL.headroom))
        assertFalse(gate.update(ThermalState.FAIR.headroom))
        assertTrue(gate.update(ThermalState.SERIOUS.headroom))
        assertTrue(gate.update(ThermalState.CRITICAL.headroom))
    }

    @Test
    fun `cooling from serious back to fair starts work again`() {
        val gate = gate()
        assertTrue(gate.update(ThermalState.SERIOUS.headroom))
        assertFalse(gate.update(ThermalState.FAIR.headroom))
    }

    @Test
    fun `the states are ordered by heat`() {
        val ordered = ThermalState.entries.map { it.headroom }
        assertEquals(ordered.sorted(), ordered)
    }

    /**
     * The point of the shared mapping: one gate, one hysteresis, one set of thresholds. A
     * second gate for iOS would drift, and a user would be told "paused for heat" on one
     * phone and not the other at the same temperature.
     */
    @Test
    fun `both platforms pause at the same policy`() {
        val android = gate()
        val ios = gate()
        // Android's continuous reading crossing 0.7, and iOS reporting serious.
        assertEquals(android.update(0.8f), ios.update(ThermalState.SERIOUS.headroom))
        assertEquals(android.update(0.45f), ios.update(ThermalState.FAIR.headroom))
        assertEquals(android.pauseCount, ios.pauseCount)
    }

    /**
     * The consequence worth knowing about: iOS has no state between the thresholds, so the
     * hysteresis does nothing there and an oscillating OS signal is a pause per oscillation.
     * Asserted rather than hoped, so the trade-off is visible if the field test finds it.
     */
    @Test
    fun `an oscillating iOS signal is not damped by the hysteresis`() {
        val gate = gate()
        repeat(3) {
            gate.update(ThermalState.SERIOUS.headroom)
            gate.update(ThermalState.FAIR.headroom)
        }
        assertEquals(3, gate.pauseCount)
    }

    /** And the alternative, kept ready: fair between the thresholds means "carry on as you were". */
    @Test
    fun `holding fair between the thresholds damps it completely`() {
        val gate = gate()
        repeat(3) {
            gate.update(ThermalState.SERIOUS.headroom)
            gate.update(ThermalState.HELD_FAIR)
        }
        assertEquals(1, gate.pauseCount)
        assertTrue(gate.update(ThermalState.HELD_FAIR), "held fair should keep the pass stood down")
        assertFalse(gate.update(ThermalState.NOMINAL.headroom))
    }

    // ---------------------------------------------------------- the boundary

    @Test
    fun `the raw values match the platform enum's order`() {
        assertEquals(ThermalState.NOMINAL, ThermalState.ofRawValue(0))
        assertEquals(ThermalState.FAIR, ThermalState.ofRawValue(1))
        assertEquals(ThermalState.SERIOUS, ThermalState.ofRawValue(2))
        assertEquals(ThermalState.CRITICAL, ThermalState.ofRawValue(3))
    }

    /**
     * A state a future OS adds must not stop the night pass on every phone that has it. The
     * gate still pauses the moment a reading it does understand says so.
     */
    @Test
    fun `an unrecognised state is treated as no information`() {
        for (raw in listOf(-1, 4, 99)) {
            assertEquals(ThermalState.NOMINAL, ThermalState.ofRawValue(raw), "$raw")
        }
    }
}
