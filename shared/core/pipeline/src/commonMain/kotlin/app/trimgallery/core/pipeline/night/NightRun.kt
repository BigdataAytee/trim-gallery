package app.trimgallery.core.pipeline.night

import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.RunSession
import app.trimgallery.core.model.StopReason
import app.trimgallery.engine.GuardResult
import app.trimgallery.engine.Guards
import app.trimgallery.engine.PauseReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The loop the night actually runs (ARCHITECTURE.md § 7).
 *
 * ```
 * for item in queue (largest saving first):
 *     guards.check(); thermal polled every 5 s inside long steps
 *     <step>
 *     RunSession.record(result); checkpoint DB      // iOS may kill the task at any point
 * ```
 *
 * Two things here are worth more than the loop:
 *
 * 1. **Guards are checked *during* a file, not only between them.** A full encode is
 *    minutes long. Checking only at the boundaries means a phone that is picked up, or
 *    that starts to overheat, keeps working for the rest of the file — the exact
 *    complaint BUILD.md rule 7 and rule 6 exist to prevent.
 * 2. **A pause gives the window back rather than holding it.** Standing down while still
 *    holding a WorkManager window stops the OS scheduling anything else and burns the
 *    battery this app exists to protect, so a pause that outlasts [Config.maxPausedMs]
 *    ends the run and lets the scheduler try again later.
 *
 * Platform-free: no WorkManager, no BGProcessingTask, no codec. It drives whatever [Step]
 * it is given, which is what lets the whole thing be tested with virtual time.
 */
class NightRun(
    private val guards: Guards,
    private val tracker: RunSessionTracker,
    private val budget: NightBudget,
    private val nowMs: () -> Long,
    private val config: Config = Config(),
) {

    data class Config(
        /** BUILD.md § 11 polls thermal every 5 s; the other guards ride along with it. */
        val pollIntervalMs: Long = ThermalGate.POLL_INTERVAL_MS,

        /**
         * How long the pass will wait, stood down, before handing the window back.
         *
         * Half an hour: long enough to sit out a warm patch or a glance at the phone,
         * short enough that a device left in a hot car does not hold a wakelock all night.
         */
        val maxPausedMs: Long = 30 * 60 * 1000L,
    )

    /** What one file's work produced. The pipeline decides; this only records. */
    sealed interface Outcome {
        data class Done(val bytesSaved: Long, val energyWh: Double = 0.0) : Outcome
        data object Skipped : Outcome
        data object Failed : Outcome
    }

    /** The queue, largest estimated saving first (BUILD.md § 6). */
    fun interface Queue {
        /** The next item, or null when there is nothing left to do. */
        suspend fun next(): MediaItem?
    }

    /** One file's worth of work. Must be cancellable: the guards cancel it mid-encode. */
    fun interface Step {
        suspend fun run(item: MediaItem): Outcome
    }

    /**
     * Persists progress after every file.
     *
     * ARCHITECTURE.md § 7 calls for this explicitly because iOS may end the task at any
     * instant; on Android the window can be revoked just as abruptly. A night that only
     * wrote its result at the end would lose everything on exactly the nights that went
     * wrong.
     */
    fun interface Checkpoint {
        suspend fun save(session: RunSession)
    }

    /**
     * An item that was interrupted mid-file, so the pipeline can put it back.
     *
     * ARCHITECTURE.md § 9: `any → PAUSED (guard) → same stage`. The item is not failed and
     * not skipped — nothing was wrong with it.
     */
    fun interface OnInterrupted {
        suspend fun interrupted(item: MediaItem, reason: PauseReason)
    }

    @Suppress("LongParameterList")
    suspend fun run(
        queue: Queue,
        step: Step,
        checkpoint: Checkpoint,
        onInterrupted: OnInterrupted = OnInterrupted { _, _ -> },
    ): RunSession {
        var pausedForMs = 0L

        while (true) {
            when (val verdict = guards.check()) {
                is GuardResult.Stop -> return finish(GuardChain.stopReasonFor(verdict.reason), checkpoint)

                is GuardResult.Pause -> {
                    budget.pause(nowMs())
                    checkpoint.save(snapshot(null))
                    if (pausedForMs >= config.maxPausedMs) {
                        return finish(GuardChain.stopReasonFor(verdict.reason), checkpoint)
                    }
                    delay(config.pollIntervalMs)
                    pausedForMs += config.pollIntervalMs
                    continue
                }

                GuardResult.Proceed -> pausedForMs = 0
            }

            val item = queue.next() ?: return finish(StopReason.COMPLETE, checkpoint)

            budget.resume(nowMs())
            when (val result = guarded(item, step)) {
                is Guarded.Finished -> when (val outcome = result.outcome) {
                    is Outcome.Done -> tracker.recordDone(outcome.bytesSaved, outcome.energyWh)
                    Outcome.Skipped -> tracker.recordSkipped()
                    Outcome.Failed -> tracker.recordFailed()
                }

                is Guarded.Interrupted -> {
                    budget.pause(nowMs())
                    // Nothing was wrong with the file — it goes back in the queue at the
                    // stage it reached, not into the skipped or failed list.
                    withContext(NonCancellable) { onInterrupted.interrupted(item, result.reason) }
                }
            }

            checkpoint.save(snapshot(null))
        }
    }

    private sealed interface Guarded {
        data class Finished(val outcome: Outcome) : Guarded
        data class Interrupted(val reason: PauseReason) : Guarded
    }

    /**
     * Runs one file with a guard poll alongside it.
     *
     * The poll cancels the step rather than waiting for it, because "pause within seconds"
     * (USER_JOURNEY.md § 3) is not achievable any other way when a step is a five-minute
     * encode. A cancellation that did *not* come from the poll — the whole run being torn
     * down — is rethrown, so an OS-revoked window is never mistaken for a thermal pause.
     */
    private suspend fun guarded(item: MediaItem, step: Step): Guarded = coroutineScope {
        var tripped: PauseReason? = null

        val work = async { step.run(item) }
        val watchdog = launch {
            while (isActive) {
                delay(config.pollIntervalMs)
                val verdict = guards.check()
                if (verdict !is GuardResult.Proceed) {
                    tripped = when (verdict) {
                        is GuardResult.Pause -> verdict.reason
                        is GuardResult.Stop -> verdict.reason
                        GuardResult.Proceed -> null
                    }
                    work.cancel(CancellationException("guard: $tripped"))
                    break
                }
            }
        }

        try {
            val outcome = work.await()
            Guarded.Finished(outcome)
        } catch (e: CancellationException) {
            val reason = tripped ?: throw e
            Guarded.Interrupted(reason)
        } finally {
            watchdog.cancel()
        }
    }

    private suspend fun finish(reason: StopReason, checkpoint: Checkpoint): RunSession {
        budget.pause(nowMs())
        val session = snapshot(reason)
        // The last write of the night, and the one the morning card reads. Uncancellable
        // for the same reason the replace rollback is: the run is usually ending *because*
        // something was taken away.
        withContext(NonCancellable) { checkpoint.save(session) }
        return session
    }

    private fun snapshot(reason: StopReason?): RunSession =
        tracker.snapshot(nowMs(), reason, thermalPauses = guards.thermalPauses)
}
