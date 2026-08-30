package app.trimgallery.core.domain.space

import app.trimgallery.core.domain.billing.Entitlements
import app.trimgallery.core.domain.billing.Tier
import app.trimgallery.core.model.RunSession
import app.trimgallery.core.model.StopReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class SpaceScreenTest {

    private val now = Instant.parse("2026-08-19T12:00:00Z")
    private val monthStart = Instant.parse("2026-08-01T00:00:00Z")
    private val nextMonth = Instant.parse("2026-09-01T00:00:00Z")
    private val gb = 1024L * 1024 * 1024

    private fun session(
        id: String,
        startedAt: Instant,
        freed: Long,
        finished: Boolean = true,
        stopReason: StopReason? = StopReason.COMPLETE,
        done: Int = 1,
        wh: Double = 0.0,
    ) = RunSession(
        id = id,
        startedAt = startedAt.toEpochMilliseconds(),
        finishedAt = if (finished) startedAt.toEpochMilliseconds() + 3_600_000 else null,
        stopReason = if (finished) stopReason else null,
        filesDone = done,
        bytesFreed = freed,
        energyWh = wh,
    )

    private fun state(
        sessions: List<RunSession> = emptyList(),
        current: RunSession? = null,
        queueSize: Int = 0,
        projected: Long = 0,
        tier: Tier = Tier.FREE,
        lastOffer: Instant? = null,
        inViewer: Boolean = false,
    ) = SpaceScreen.state(
        sessions, current, queueSize, projected, tier, now, monthStart, nextMonth, lastOffer, inViewer,
    )

    @Test
    fun `the running total is summed from what actually happened`() {
        // Not a counter: a counter drifts from reality the first time a run is killed
        // mid-write, and a number the user cannot trust is worse than no number.
        val s = state(
            listOf(
                session("a", Instant.parse("2025-01-01T00:00:00Z"), 2 * gb),
                session("b", Instant.parse("2026-08-05T00:00:00Z"), 1 * gb),
            ),
        )
        assertEquals(3 * gb, s.totalFreed)
        assertEquals(1 * gb, s.freedThisMonth, "only this month counts against the cap")
    }

    @Test
    fun `the cap ring fills as the month is used up`() {
        val s = state(listOf(session("a", monthStart, Entitlements.FREE_MONTHLY_BYTES / 2)))
        assertEquals(0.5f, s.capFraction)
        assertTrue(!s.capReached)
    }

    @Test
    fun `Pro has no cap to draw`() {
        val s = state(listOf(session("a", monthStart, 900 * gb)), tier = Tier.PRO)
        assertNull(s.monthlyCap)
        assertNull(s.capFraction)
        assertNull(s.capResetsInDays)
        assertTrue(!s.capReached)
    }

    @Test
    fun `the reset countdown rounds up, so it never reads zero while waiting`() {
        assertEquals(13, state().capResetsInDays)
    }

    @Test
    fun `the Pro card appears only when the cap is actually reached`() {
        assertTrue(!state(listOf(session("a", monthStart, gb))).showProOffer)
        assertTrue(state(listOf(session("a", monthStart, 3 * gb))).showProOffer)
    }

    @Test
    fun `the Pro card never interrupts the viewer, and never appears for Pro`() {
        val capped = listOf(session("a", monthStart, 3 * gb))
        assertTrue(!state(capped, inViewer = true).showProOffer)
        assertTrue(!state(capped, tier = Tier.PRO).showProOffer)
    }

    // ------------------------------------------------------------------ progress

    @Test
    fun `no run means an idle ring`() {
        assertIs<SpaceScreen.Progress.Idle>(state().progress)
    }

    @Test
    fun `a working run reports what is done against what is left`() {
        val running = session("n", now, 500_000_000, finished = false, done = 3)
        val progress = assertIs<SpaceScreen.Progress.Working>(state(current = running, queueSize = 7).progress)
        assertEquals(3, progress.done)
        assertEquals(10, progress.total)
        assertEquals(0.3f, progress.fraction)
        assertEquals(500_000_000, progress.bytesFreed)
    }

    @Test
    fun `a paused run is not shown as working`() {
        // A ring that spins while the pass is stood down for heat is a lie the user can
        // catch by feeling the phone.
        val paused = session("n", now, 0, finished = false).copy(stopReason = StopReason.THERMAL)
        val progress = assertIs<SpaceScreen.Progress.Paused>(state(current = paused).progress)
        assertEquals(StopReason.THERMAL, progress.reason)
    }

    @Test
    fun `a finished run is idle, not working`() {
        assertIs<SpaceScreen.Progress.Idle>(state(current = session("n", now, gb)).progress)
    }

    @Test
    fun `an unknown queue length leaves the ring indeterminate rather than wrong`() {
        val running = session("n", now, 0, finished = false, done = 0)
        val progress = assertIs<SpaceScreen.Progress.Working>(state(current = running, queueSize = 0).progress)
        assertNull(progress.fraction)
    }

    // ------------------------------------------------------------------ copy

    @Test
    fun `the projection is always hedged, because it is a projection`() {
        val s = state(projected = 19 * gb)
        assertEquals("About 19 GB more possible", SpaceScreen.projectionLine(s) { "${it / gb} GB" })
    }

    @Test
    fun `nothing to project means no line at all`() {
        assertNull(SpaceScreen.projectionLine(state()) { "$it" })
    }

    @Test
    fun `energy is summed across every night`() {
        val s = state(
            listOf(
                session("a", monthStart, gb, wh = 1.5),
                session("b", monthStart, gb, wh = 2.0),
            ),
        )
        assertEquals(3.5, s.energyWh, 1e-9)
        assertEquals("about 4 Wh", EnergyEstimate.describe(s.energyWh))
    }
}
