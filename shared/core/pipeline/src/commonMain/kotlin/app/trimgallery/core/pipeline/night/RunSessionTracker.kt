package app.trimgallery.core.pipeline.night

import app.trimgallery.core.model.RunSession
import app.trimgallery.core.model.StopReason

/**
 * One night's tally, as it happens.
 *
 * This is what the morning card and the History screen are built from
 * (USER_JOURNEY.md § 4), so it records what actually happened rather than what was
 * planned: files that were skipped and files that failed are counted separately, because
 * "nothing to do" and "three things went wrong" look identical in a total.
 *
 * Every number is folded in as each file finishes rather than summed at the end. iOS can
 * end a background task at any instant and Android can lose the window, so a tally that
 * only existed at the end would be lost exactly on the nights it most needs explaining.
 */
class RunSessionTracker(private val id: String, private val startedAtMs: Long, private val budget: NightBudget) {
    private var filesDone = 0
    private var filesSkipped = 0
    private var filesFailed = 0
    private var bytesFreed = 0L
    private var energyWh = 0.0

    fun recordDone(bytesSaved: Long, wh: Double = 0.0) {
        filesDone += 1
        // Never let a file that grew subtract from the night's total: it was not replaced,
        // so it freed nothing, and a negative contribution would understate real work.
        bytesFreed += bytesSaved.coerceAtLeast(0)
        energyWh += wh
    }

    fun recordSkipped() {
        filesSkipped += 1
    }

    fun recordFailed() {
        filesFailed += 1
    }

    /**
     * A snapshot, safe to persist at any point.
     *
     * [thermalPauses] comes from the gate rather than being counted here, so the number
     * cannot drift from the one that actually drove the pausing.
     */
    fun snapshot(nowMs: Long, stopReason: StopReason?, thermalPauses: Int): RunSession = RunSession(
        id = id,
        startedAt = startedAtMs,
        finishedAt = if (stopReason == null) null else nowMs,
        stopReason = stopReason,
        filesDone = filesDone,
        filesSkipped = filesSkipped,
        filesFailed = filesFailed,
        bytesFreed = bytesFreed,
        minutesWorked = budget.minutesWorked(nowMs),
        energyWh = energyWh,
        thermalPauses = thermalPauses,
        seen = false,
    )

    /** True when the night achieved nothing the user would want a card about. */
    fun isEmpty(): Boolean = filesDone == 0 && filesSkipped == 0 && filesFailed == 0
}
