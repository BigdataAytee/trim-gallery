package app.trimgallery.core.pipeline.night

/**
 * How much of tonight is left.
 *
 * BUILD.md § 6: *"nightly cap (default 60 min of work)"*. The cap is on **work**, not on
 * elapsed time, and the distinction is the whole reason this is a class rather than a
 * subtraction: a pass that spends five hours plugged in but stood down for heat for four
 * of them has used one hour of its budget, not five. Counting wall-clock time would let a
 * hot night quietly consume a cool one's allowance.
 *
 * Not thread-safe, and does not need to be: ARCHITECTURE.md § 8 runs the pass on one
 * coroutine and the budget is only touched from it.
 */
class NightBudget(private val capMinutes: Int) {

    init {
        require(capMinutes > 0) { "a nightly cap of $capMinutes minutes leaves no time to work" }
    }

    private var workedMs = 0L
    private var runningSince: Long? = null

    val capMs: Long get() = capMinutes * MS_PER_MINUTE

    /** Work actually done, including any stretch currently in progress. */
    fun workedMs(nowMs: Long): Long = workedMs + (runningSince?.let { nowMs - it } ?: 0L)

    fun remainingMs(nowMs: Long): Long = (capMs - workedMs(nowMs)).coerceAtLeast(0)

    fun exhausted(nowMs: Long): Boolean = remainingMs(nowMs) == 0L

    /** The clock starts. Calling this while already running is a no-op, not a double count. */
    fun resume(nowMs: Long) {
        if (runningSince == null) runningSince = nowMs
    }

    /** The clock stops — a guard paused the pass, or the run ended. */
    fun pause(nowMs: Long) {
        val since = runningSince ?: return
        workedMs += (nowMs - since).coerceAtLeast(0)
        runningSince = null
    }

    /** Minutes worked, for `RunSession.minutes_worked`. */
    fun minutesWorked(nowMs: Long): Double = workedMs(nowMs).toDouble() / MS_PER_MINUTE

    private companion object {
        const val MS_PER_MINUTE = 60_000L
    }
}
