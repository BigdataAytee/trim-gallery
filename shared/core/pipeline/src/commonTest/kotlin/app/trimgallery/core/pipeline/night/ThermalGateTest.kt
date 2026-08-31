package app.trimgallery.core.pipeline.night

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThermalGateTest {

    /**
     * A clock that jumps well past the pause floor between readings.
     *
     * The tests below are about the *hysteresis*, which is a property of the readings and
     * not of time. Advancing generously keeps them measuring that, and leaves the floor to
     * the tests written for it further down.
     */
    private var clock = 0L

    private fun ThermalGate.cooled(headroom: Float): Boolean {
        clock += ThermalGate.MINIMUM_PAUSE_MS * 2
        return update(headroom, clock)
    }

    @Test
    fun `the thresholds are the ones BUILD md section 11 fixes`() {
        assertEquals(0.7f, ThermalGate.PAUSE_ABOVE)
        assertEquals(0.5f, ThermalGate.RESUME_BELOW)
        assertEquals(5_000L, ThermalGate.POLL_INTERVAL_MS)
        assertEquals(30, ThermalGate.FORECAST_SECONDS)
    }

    @Test
    fun `a cool phone works and a hot one stands down`() {
        val gate = ThermalGate()
        assertFalse(gate.cooled(0.2f))
        assertTrue(gate.cooled(0.8f))
    }

    @Test
    fun `the band between the thresholds holds the previous decision`() {
        // The whole point of two thresholds. A single one on a value hovering around it
        // starts and stops the encoder several times a second, which heats the phone more
        // than running steadily would.
        val gate = ThermalGate()
        assertTrue(gate.cooled(0.75f), "0.75 is above the pause threshold")
        assertTrue(gate.cooled(0.6f), "still paused: 0.6 has not reached the resume threshold")
        assertTrue(gate.cooled(0.55f))
        assertFalse(gate.cooled(0.45f), "0.45 is below the resume threshold")
        assertFalse(gate.cooled(0.6f), "still running: 0.6 has not reached the pause threshold")
    }

    @Test
    fun `hovering exactly on a threshold does not flap`() {
        val gate = ThermalGate()
        repeat(20) { assertFalse(gate.cooled(0.7f), "0.7 is not *above* 0.7") }
        gate.cooled(0.71f)
        repeat(20) { assertTrue(gate.cooled(0.5f), "0.5 is not *below* 0.5") }
    }

    @Test
    fun `pauses are counted once per stand-down, not once per reading`() {
        // USER_JOURNEY.md section 14 shows this as "Paused for heat 3x last night". A
        // count that ticked on every 5-second poll would read "Paused for heat 400x".
        val gate = ThermalGate()
        repeat(10) { gate.cooled(0.9f) }
        assertEquals(1, gate.pauseCount)
        gate.cooled(0.1f)
        repeat(10) { gate.cooled(0.9f) }
        assertEquals(2, gate.pauseCount)
    }

    @Test
    fun `a device with no thermal sensing keeps working`() {
        // getThermalHeadroom returns NaN where it is unsupported. Refusing to work on
        // every such phone would be a worse failure than trusting one that has never
        // reported being hot.
        val gate = ThermalGate()
        assertFalse(gate.cooled(Float.NaN))
        assertEquals(0, gate.pauseCount)
    }

    @Test
    fun `an unreadable sensor does not resume a phone that was already hot`() {
        val gate = ThermalGate()
        assertTrue(gate.cooled(0.9f))
        assertTrue(gate.cooled(Float.NaN), "no information is not the same as good news")
    }

    @Test
    fun `a gate whose resume is not cooler than its pause is refused`() {
        // Such a gate would flap by construction.
        assertFailsWith<IllegalArgumentException> { ThermalGate(pauseAbove = 0.5f, resumeBelow = 0.7f) }
        assertFailsWith<IllegalArgumentException> { ThermalGate(pauseAbove = 0.5f, resumeBelow = 0.5f) }
    }

    // ------------------------------------------------------------- pause floor

    /**
     * Heat is a reason to stop immediately and always. The floor only ever delays
     * resumption, never protection — a gate that made a phone wait to *start* protecting
     * itself would be worse than no gate.
     */
    @Test
    fun `the floor never delays a pause, only a resume`() {
        val gate = ThermalGate()
        assertTrue(gate.update(0.9f, nowMs = 0), "a hot phone stands down on the reading that says so")
        assertEquals(1, gate.pauseCount)
    }

    @Test
    fun `a pause holds for its minimum even once the phone is cool`() {
        val gate = ThermalGate()
        assertTrue(gate.update(0.9f, nowMs = 0))
        assertTrue(gate.update(0.1f, nowMs = 1_000), "cool, but one second into a sixty-second floor")
        assertTrue(gate.update(0.1f, nowMs = 59_999))
        assertFalse(gate.update(0.1f, nowMs = 60_000), "the floor is reached")
    }

    @Test
    fun `the floor is configurable, because the right number is a device question`() {
        val gate = ThermalGate(minimumPauseMs = 10_000)
        gate.update(0.9f, nowMs = 0)
        assertTrue(gate.update(0.1f, nowMs = 9_999))
        assertFalse(gate.update(0.1f, nowMs = 10_000))
    }

    @Test
    fun `a floor of zero is the old behaviour exactly`() {
        val gate = ThermalGate(minimumPauseMs = 0)
        assertTrue(gate.update(0.9f, nowMs = 0))
        assertFalse(gate.update(0.1f, nowMs = 0), "with no floor, one cool reading resumes")
    }

    @Test
    fun `a gate with a negative floor is refused`() {
        assertFailsWith<IllegalArgumentException> { ThermalGate(minimumPauseMs = -1) }
    }

    /** The floor measures from the pause, so a second pause restarts it. */
    @Test
    fun `each pause gets its own floor`() {
        val gate = ThermalGate()
        gate.update(0.9f, nowMs = 0)
        assertFalse(gate.update(0.1f, nowMs = 60_000))
        assertTrue(gate.update(0.9f, nowMs = 70_000), "hot again")
        assertTrue(gate.update(0.1f, nowMs = 100_000), "30 s into the second floor, not 100")
        assertFalse(gate.update(0.1f, nowMs = 130_000))
    }

    @Test
    fun `reset clears the floor along with everything else`() {
        val gate = ThermalGate()
        gate.update(0.9f, nowMs = 500_000)
        gate.reset()
        assertFalse(gate.isPaused)
        assertEquals(0, gate.pauseCount)
        assertFalse(gate.update(0.1f, nowMs = 500_001), "a reset gate is not still inside a floor")
    }
}
