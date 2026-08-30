package app.trimgallery.core.pipeline.night

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThermalGateTest {

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
        assertFalse(gate.update(0.2f))
        assertTrue(gate.update(0.8f))
    }

    @Test
    fun `the band between the thresholds holds the previous decision`() {
        // The whole point of two thresholds. A single one on a value hovering around it
        // starts and stops the encoder several times a second, which heats the phone more
        // than running steadily would.
        val gate = ThermalGate()
        assertTrue(gate.update(0.75f), "0.75 is above the pause threshold")
        assertTrue(gate.update(0.6f), "still paused: 0.6 has not reached the resume threshold")
        assertTrue(gate.update(0.55f))
        assertFalse(gate.update(0.45f), "0.45 is below the resume threshold")
        assertFalse(gate.update(0.6f), "still running: 0.6 has not reached the pause threshold")
    }

    @Test
    fun `hovering exactly on a threshold does not flap`() {
        val gate = ThermalGate()
        repeat(20) { assertFalse(gate.update(0.7f), "0.7 is not *above* 0.7") }
        gate.update(0.71f)
        repeat(20) { assertTrue(gate.update(0.5f), "0.5 is not *below* 0.5") }
    }

    @Test
    fun `pauses are counted once per stand-down, not once per reading`() {
        // USER_JOURNEY.md section 14 shows this as "Paused for heat 3x last night". A
        // count that ticked on every 5-second poll would read "Paused for heat 400x".
        val gate = ThermalGate()
        repeat(10) { gate.update(0.9f) }
        assertEquals(1, gate.pauseCount)
        gate.update(0.1f)
        repeat(10) { gate.update(0.9f) }
        assertEquals(2, gate.pauseCount)
    }

    @Test
    fun `a device with no thermal sensing keeps working`() {
        // getThermalHeadroom returns NaN where it is unsupported. Refusing to work on
        // every such phone would be a worse failure than trusting one that has never
        // reported being hot.
        val gate = ThermalGate()
        assertFalse(gate.update(Float.NaN))
        assertEquals(0, gate.pauseCount)
    }

    @Test
    fun `an unreadable sensor does not resume a phone that was already hot`() {
        val gate = ThermalGate()
        assertTrue(gate.update(0.9f))
        assertTrue(gate.update(Float.NaN), "no information is not the same as good news")
    }

    @Test
    fun `a gate whose resume is not cooler than its pause is refused`() {
        // Such a gate would flap by construction.
        assertFailsWith<IllegalArgumentException> { ThermalGate(pauseAbove = 0.5f, resumeBelow = 0.7f) }
        assertFailsWith<IllegalArgumentException> { ThermalGate(pauseAbove = 0.5f, resumeBelow = 0.5f) }
    }
}
