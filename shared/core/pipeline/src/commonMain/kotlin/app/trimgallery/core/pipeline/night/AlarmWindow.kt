package app.trimgallery.core.pipeline.night

import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atDate
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * When the night has to be over.
 *
 * BUILD.md § 6 lists *"30 min before alarm"* among the stop conditions, and BUILD.md § 9
 * gives the user a "stop by" time as well. Both exist for the same reason: the phone has
 * to be cool, charged and quiet by the time its owner picks it up, and an encoder that
 * runs until 06:59 leaves a warm phone at 07:00.
 *
 * Thirty minutes because that is roughly how long a phone takes to shed the heat of a
 * sustained encode and to top the last few percent back up.
 */
object AlarmWindow {

    val LEAD = 30.minutes

    /**
     * The instant work must stop, or null when nothing bounds it.
     *
     * The earlier of the two bounds wins: they are both promises, and the stricter promise
     * is the one that has to hold.
     */
    fun deadline(
        now: Instant,
        nextAlarm: Instant?,
        stopBy: LocalTime?,
        zone: TimeZone,
    ): Instant? {
        val alarmBound = alarmDeadline(now, nextAlarm)
        val stopByBound = stopBy?.let { nextOccurrence(now, it, zone) }
        return listOfNotNull(alarmBound, stopByBound).minOrNull()
    }

    /**
     * [LEAD] before the next alarm.
     *
     * An alarm at or before [now] is ignored rather than treated as overdue. `AlarmManager`
     * should not report one, but a stale value that *was* honoured would stop every night
     * from then on — a bug that looks exactly like "the app just stopped working".
     */
    private fun alarmDeadline(now: Instant, nextAlarm: Instant?): Instant? {
        if (nextAlarm == null || nextAlarm <= now) return null
        return nextAlarm - LEAD
    }

    /**
     * The next time the wall clock reads [time], strictly after [now].
     *
     * "Strictly" matters: the pass usually starts late in the evening, so a stop-by of
     * 06:00 means six tomorrow morning, not six this morning, which is already past. Going
     * through the calendar rather than adding milliseconds is what makes this right across
     * a daylight-saving change, where a day is 23 or 25 hours long.
     */
    fun nextOccurrence(now: Instant, time: LocalTime, zone: TimeZone): Instant {
        val local = now.toLocalDateTime(zone)
        val today = time.atDate(local.date).toInstant(zone)
        if (today > now) return today
        // The same wall-clock time on the following day, resolved through the calendar.
        return time.atDate(local.date.plusDays()).toInstant(zone)
    }

    /** True once [deadline] has arrived. A null deadline never arrives. */
    fun reached(now: Instant, deadline: Instant?): Boolean = deadline != null && now >= deadline

    /** How long is left, or null when nothing bounds the run. */
    fun remaining(now: Instant, deadline: Instant?): kotlin.time.Duration? =
        deadline?.let { if (it <= now) kotlin.time.Duration.ZERO else it - now }

    private fun kotlinx.datetime.LocalDate.plusDays(): kotlinx.datetime.LocalDate =
        kotlinx.datetime.LocalDate.fromEpochDays(toEpochDays() + 1)
}
