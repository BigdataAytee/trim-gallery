package app.trimgallery.core.pipeline.night

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThermalReadingTest {

    private fun gate() = ThermalGate()

    /** iOS polls nothing; the OS notifies. Five seconds apart is a busy but plausible rate. */
    private val pollMs = ThermalGate.POLL_INTERVAL_MS

    private var clock = 0L

    /** One reading, five seconds after the last. */
    private fun ThermalGate.poll(state: ThermalState): Boolean {
        clock += pollMs
        return update(state.headroom, clock)
    }

    private fun ThermalGate.poll(headroom: Float): Boolean {
        clock += pollMs
        return update(headroom, clock)
    }

    /** ARCHITECTURE.md § 6: run at nominal/fair, pause at serious/critical. */
    @Test
    fun `the four states produce exactly the behaviour the spec asks for`() {
        val gate = gate()
        assertFalse(gate.poll(ThermalState.NOMINAL))
        assertFalse(gate.poll(ThermalState.FAIR))
        assertTrue(gate.poll(ThermalState.SERIOUS))
        assertTrue(gate.poll(ThermalState.CRITICAL))
    }

    /**
     * ARCHITECTURE.md § 6 says fair runs, and it does — once the pause floor has passed.
     * The floor delays the resume by a minute; it does not change which states run.
     */
    @Test
    fun `cooling from serious back to fair starts work again`() {
        val gate = gate()
        assertTrue(gate.poll(ThermalState.SERIOUS))
        assertTrue(gate.poll(ThermalState.FAIR), "inside the floor")
        clock += ThermalGate.MINIMUM_PAUSE_MS
        assertFalse(gate.poll(ThermalState.FAIR), "the floor has passed")
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
        assertEquals(android.poll(0.8f), ios.poll(ThermalState.SERIOUS))
        assertEquals(android.poll(0.45f), ios.poll(ThermalState.FAIR))
        assertEquals(android.pauseCount, ios.pauseCount)
    }

    /**
     * The reason `ThermalGate` grew a pause floor.
     *
     * iOS has no state between the two thresholds, so the hysteresis has nothing to bite
     * on: before the floor, an OS signal oscillating between fair and serious produced a
     * pause and a resume *per oscillation* — a "paused for heat 400×" line in the user's
     * History for a phone that was merely sitting near a threshold.
     *
     * The floor damps it because it does not care what shape the reading is. Six
     * oscillations at the 5-second poll rate span 60 s, so the user sees one stand-down.
     */
    @Test
    fun `a flapping iOS signal produces at most one pause per floor window`() {
        val gate = gate()
        // 12 readings at 5 s each: one minute of the sensor going back and forth.
        repeat(6) {
            gate.poll(ThermalState.SERIOUS)
            gate.poll(ThermalState.FAIR)
        }
        assertEquals(1, gate.pauseCount, "the floor should have absorbed the oscillation")
        assertTrue(gate.isPaused, "and left the pass stood down while it was going on")
    }

    @Test
    fun `a signal that flaps for longer pauses once per window, not once per swing`() {
        val gate = gate()
        // Five minutes of oscillation at the poll rate: 30 swings, 5 floor windows.
        repeat(30) {
            gate.poll(ThermalState.SERIOUS)
            gate.poll(ThermalState.FAIR)
        }
        assertTrue(gate.pauseCount <= 5, "expected at most one pause per minute, got ${gate.pauseCount}")
        assertTrue(gate.pauseCount >= 1)
    }

    /** A phone that genuinely cools loses the floor and nothing more. */
    @Test
    fun `a real cooldown resumes one floor after the pause`() {
        val gate = gate()
        assertTrue(gate.poll(ThermalState.SERIOUS))
        // Fair readings for the whole minute, then one more.
        repeat((ThermalGate.MINIMUM_PAUSE_MS / pollMs).toInt() - 1) {
            assertTrue(gate.poll(ThermalState.FAIR), "still inside the floor")
        }
        assertFalse(gate.poll(ThermalState.FAIR), "the floor has passed and the phone is fair")
    }

    /**
     * The alternative, kept ready and now doing less work.
     *
     * Holding fair between the thresholds damps the oscillation by *shape* where the floor
     * damps it by *time*. With the floor in place this is belt and braces rather than the
     * only defence, which is why it stays the non-default: ARCHITECTURE.md § 6 says fair
     * runs, and now it can.
     */
    @Test
    fun `holding fair between the thresholds damps it completely`() {
        val gate = gate()
        repeat(3) {
            gate.poll(ThermalState.SERIOUS)
            gate.poll(ThermalState.HELD_FAIR)
        }
        assertEquals(1, gate.pauseCount)
        assertTrue(gate.poll(ThermalState.HELD_FAIR), "held fair should keep the pass stood down")
        // Past the floor, so what resumes is decided by the reading alone — and held fair
        // is between the thresholds, so it still does not.
        clock += ThermalGate.MINIMUM_PAUSE_MS
        assertTrue(gate.poll(ThermalState.HELD_FAIR), "still between the thresholds")
        assertFalse(gate.poll(ThermalState.NOMINAL))
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
