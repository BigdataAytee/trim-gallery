package app.trimgallery.core.pipeline.night

import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class AlarmWindowTest {

    private val utc = TimeZone.UTC
    private val london = TimeZone.of("Europe/London")

    @Test
    fun `work stops thirty minutes before the alarm`() {
        val now = Instant.parse("2026-08-30T02:00:00Z")
        val alarm = Instant.parse("2026-08-30T07:00:00Z")
        assertEquals(
            Instant.parse("2026-08-30T06:30:00Z"),
            AlarmWindow.deadline(now, alarm, stopBy = null, zone = utc),
        )
    }

    @Test
    fun `an alarm already inside the lead time stops work now`() {
        val now = Instant.parse("2026-08-30T06:50:00Z")
        val alarm = Instant.parse("2026-08-30T07:00:00Z")
        val deadline = AlarmWindow.deadline(now, alarm, stopBy = null, zone = utc)
        assertTrue(AlarmWindow.reached(now, deadline))
    }

    @Test
    fun `a stale alarm in the past is ignored, not treated as overdue`() {
        // AlarmManager should not report one, but a stale value that was honoured would
        // stop every night from then on — a bug that looks exactly like "it just stopped
        // working".
        val now = Instant.parse("2026-08-30T02:00:00Z")
        val yesterday = Instant.parse("2026-08-29T07:00:00Z")
        assertNull(AlarmWindow.deadline(now, yesterday, stopBy = null, zone = utc))
    }

    @Test
    fun `no alarm and no stop-by means nothing bounds the run`() {
        val now = Instant.parse("2026-08-30T02:00:00Z")
        assertNull(AlarmWindow.deadline(now, null, null, utc))
        assertFalse(AlarmWindow.reached(now, null))
    }

    @Test
    fun `a stop-by later tonight is tonight`() {
        val now = Instant.parse("2026-08-30T02:00:00Z")
        assertEquals(
            Instant.parse("2026-08-30T06:00:00Z"),
            AlarmWindow.deadline(now, null, LocalTime(6, 0), utc),
        )
    }

    @Test
    fun `a stop-by that has already passed today means tomorrow`() {
        // The pass usually starts late in the evening, so "stop by 06:00" means six
        // tomorrow morning, not six this morning, which is already behind us.
        val now = Instant.parse("2026-08-30T23:00:00Z")
        assertEquals(
            Instant.parse("2026-08-31T06:00:00Z"),
            AlarmWindow.deadline(now, null, LocalTime(6, 0), utc),
        )
    }

    @Test
    fun `a stop-by at exactly now means tomorrow, not immediately`() {
        // Strictly after: otherwise starting a run at exactly the stop-by time would end
        // it before a single file ran, every night.
        val now = Instant.parse("2026-08-30T06:00:00Z")
        assertEquals(
            Instant.parse("2026-08-31T06:00:00Z"),
            AlarmWindow.deadline(now, null, LocalTime(6, 0), utc),
        )
    }

    @Test
    fun `the earlier of the two bounds wins`() {
        val now = Instant.parse("2026-08-30T02:00:00Z")
        val alarm = Instant.parse("2026-08-30T07:00:00Z") // deadline 06:30
        // Stop-by is stricter.
        assertEquals(
            Instant.parse("2026-08-30T05:00:00Z"),
            AlarmWindow.deadline(now, alarm, LocalTime(5, 0), utc),
        )
        // The alarm is stricter.
        assertEquals(
            Instant.parse("2026-08-30T06:30:00Z"),
            AlarmWindow.deadline(now, alarm, LocalTime(8, 0), utc),
        )
    }

    @Test
    fun `the stop-by is a wall-clock time, and survives a clock change`() {
        // Europe/London springs forward at 01:00 UTC on 2026-03-29, so that night is 23
        // hours long. A stop-by of 06:00 must still mean six on the clock in the room —
        // adding 24 hours' worth of milliseconds would land at 07:00 local.
        val now = Instant.parse("2026-03-28T23:30:00Z") // 23:30 local, before the change
        val deadline = AlarmWindow.deadline(now, null, LocalTime(6, 0), london)
        assertEquals(Instant.parse("2026-03-29T05:00:00Z"), deadline) // 06:00 BST
    }

    @Test
    fun `remaining time counts down and never goes negative`() {
        val now = Instant.parse("2026-08-30T02:00:00Z")
        assertEquals(4.hours, AlarmWindow.remaining(now, Instant.parse("2026-08-30T06:00:00Z")))
        assertEquals(kotlin.time.Duration.ZERO, AlarmWindow.remaining(now, Instant.parse("2026-08-30T01:00:00Z")))
        assertNull(AlarmWindow.remaining(now, null))
    }

    @Test
    fun `the lead is thirty minutes`() {
        // Roughly how long a phone takes to shed the heat of a sustained encode and top
        // the last few percent back up.
        assertEquals(30.minutes, AlarmWindow.LEAD)
    }

    /**
     * iOS has no alarm API at all (ARCHITECTURE.md § 6), so the whole deadline there is the
     * user's own "stop by" time. Asserted rather than assumed, because the port depends on
     * this path already working: had `deadline` required an alarm to produce an answer, the
     * night pass on iOS would simply never stop.
     */
    @Test
    fun `with no alarm source the stop-by time is the whole deadline`() {
        val now = Instant.parse("2026-08-30T22:00:00Z")
        val deadline = AlarmWindow.deadline(
            now = now,
            nextAlarm = null,
            stopBy = LocalTime(6, 0),
            zone = utc,
        )
        assertEquals(Instant.parse("2026-08-31T06:00:00Z"), deadline)
        assertFalse(AlarmWindow.reached(now, deadline))
        assertTrue(AlarmWindow.reached(Instant.parse("2026-08-31T06:30:00Z"), deadline))
    }

    /** And a platform with neither is unbounded, which is the guards' problem, not this one's. */
    @Test
    fun `no alarm and no stop-by is no deadline`() {
        val now = Instant.parse("2026-08-30T22:00:00Z")
        assertNull(AlarmWindow.deadline(now, nextAlarm = null, stopBy = null, zone = TimeZone.UTC))
        assertFalse(AlarmWindow.reached(now, null))
    }
}
