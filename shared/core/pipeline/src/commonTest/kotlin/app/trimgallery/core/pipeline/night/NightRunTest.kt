package app.trimgallery.core.pipeline.night

import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.model.MediaRef
import app.trimgallery.core.model.RunSession
import app.trimgallery.core.model.StopReason
import app.trimgallery.engine.GuardResult
import app.trimgallery.engine.Guards
import app.trimgallery.engine.PauseReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ARCHITECTURE.md § 7's loop, with virtual time so a five-minute encode and a five-second
 * poll can both be exercised in milliseconds.
 */
class NightRunTest {

    private fun item(id: String, size: Long = 100_000_000) = MediaItem(
        id = id,
        platformRef = MediaRef("ref-$id"),
        name = "$id.mp4",
        kind = MediaKind.VIDEO,
        codec = "h264",
        width = 1920,
        height = 1080,
        fps = 30.0,
        bitrate = 20_000_000,
        size = size,
        duration = 60_000,
        takenAt = null,
        location = null,
        cameraModel = null,
        phash = null,
        sha256 = null,
        mtime = 0,
    )

    /** Answers whatever the test tells it to, in sequence, repeating the last answer. */
    private class ScriptedGuards(private vararg val script: GuardResult) : Guards {
        var checks = 0
            private set
        override val thermalHeadroom: StateFlow<Float> = MutableStateFlow(0.1f)
        override var thermalPauses: Int = 0
        override suspend fun check(): GuardResult {
            val verdict = script.getOrElse(checks) { script.last() }
            checks += 1
            if (verdict is GuardResult.Pause && verdict.reason == PauseReason.THERMAL) thermalPauses += 1
            return verdict
        }
    }

    private class Recorder : NightRun.Checkpoint {
        val saved = mutableListOf<RunSession>()
        override suspend fun save(session: RunSession) {
            saved += session
        }
    }

    private fun run(
        guards: Guards,
        items: List<MediaItem>,
        step: NightRun.Step,
        checkpoint: Recorder = Recorder(),
        capMinutes: Int = 60,
        onInterrupted: NightRun.OnInterrupted = NightRun.OnInterrupted { _, _ -> },
        nowMs: () -> Long,
    ): suspend () -> RunSession {
        val budget = NightBudget(capMinutes)
        val tracker = RunSessionTracker("run-1", startedAtMs = 0, budget = budget)
        val night = NightRun(guards, tracker, budget, nowMs)
        val queue = items.toMutableList()
        return { night.run({ queue.removeFirstOrNull() }, step, checkpoint, onInterrupted) }
    }

    @Test
    fun `an undisturbed night works through the queue and finishes complete`() = runTest {
        val checkpoint = Recorder()
        val session = run(
            guards = ScriptedGuards(GuardResult.Proceed),
            items = listOf(item("a"), item("b"), item("c")),
            step = { NightRun.Outcome.Done(bytesSaved = 10_000_000) },
            checkpoint = checkpoint,
            nowMs = { currentTime },
        )()

        assertEquals(StopReason.COMPLETE, session.stopReason)
        assertEquals(3, session.filesDone)
        assertEquals(30_000_000, session.bytesFreed)
        // ARCHITECTURE.md § 7: checkpoint after every file, plus the final write.
        assertEquals(4, checkpoint.saved.size)
    }

    @Test
    fun `skipped and failed files are counted apart from done ones`() = runTest {
        // "Nothing to do" and "three things went wrong" look identical in a total.
        var call = 0
        val session = run(
            guards = ScriptedGuards(GuardResult.Proceed),
            items = listOf(item("a"), item("b"), item("c")),
            step = {
                when (call++) {
                    0 -> NightRun.Outcome.Done(1_000)
                    1 -> NightRun.Outcome.Skipped
                    else -> NightRun.Outcome.Failed
                }
            },
            nowMs = { currentTime },
        )()

        assertEquals(1, session.filesDone)
        assertEquals(1, session.filesSkipped)
        assertEquals(1, session.filesFailed)
    }

    @Test
    fun `a file that grew never subtracts from the night's total`() {
        val budget = NightBudget(60)
        val tracker = RunSessionTracker("r", 0, budget)
        tracker.recordDone(bytesSaved = -5_000)
        assertEquals(0, tracker.snapshot(0, StopReason.COMPLETE, 0).bytesFreed)
    }

    @Test
    fun `an unplugged phone ends the run before any file starts`() = runTest {
        var stepsRun = 0
        val session = run(
            guards = ScriptedGuards(GuardResult.Stop(PauseReason.NOT_CHARGING)),
            items = listOf(item("a")),
            step = {
                stepsRun += 1
                NightRun.Outcome.Done(0)
            },
            nowMs = { currentTime },
        )()

        assertEquals(StopReason.UNPLUGGED, session.stopReason)
        assertEquals(0, stepsRun)
    }

    @Test
    fun `a pause waits and then carries on when it clears`() = runTest {
        val guards = ScriptedGuards(
            GuardResult.Pause(PauseReason.THERMAL),
            GuardResult.Pause(PauseReason.THERMAL),
            GuardResult.Proceed,
        )
        val session = run(
            guards = guards,
            items = listOf(item("a")),
            step = { NightRun.Outcome.Done(1_000) },
            nowMs = { currentTime },
        )()

        assertEquals(StopReason.COMPLETE, session.stopReason)
        assertEquals(1, session.filesDone)
        assertEquals(2, session.thermalPauses)
    }

    @Test
    fun `a pause that never clears hands the window back rather than holding it`() = runTest {
        // Sitting stood down while still holding a WorkManager window stops the OS
        // scheduling anything else and burns the battery this app exists to protect.
        val session = run(
            guards = ScriptedGuards(GuardResult.Pause(PauseReason.THERMAL)),
            items = listOf(item("a")),
            step = { NightRun.Outcome.Done(1_000) },
            nowMs = { currentTime },
        )()

        assertEquals(StopReason.THERMAL, session.stopReason)
        assertEquals(0, session.filesDone)
        // 30 minutes of patience at a 5-second poll.
        assertTrue(currentTime >= 30 * 60 * 1000L, "gave up after only ${currentTime}ms")
    }

    @Test
    fun `paused time does not count against the nightly cap`() = runTest {
        val budget = NightBudget(capMinutes = 60)
        val tracker = RunSessionTracker("r", 0, budget)
        val guards = ScriptedGuards(
            *Array(100) { GuardResult.Pause(PauseReason.THERMAL) },
            GuardResult.Proceed,
        )
        val night = NightRun(guards, tracker, budget, { currentTime })
        val queue = mutableListOf(item("a"))

        val session = night.run({ queue.removeFirstOrNull() }, { NightRun.Outcome.Done(1) }, Recorder())

        // Over eight minutes of wall clock elapsed standing down; none of it is work.
        assertTrue(currentTime > 8 * 60 * 1000L)
        assertTrue(session.minutesWorked < 1.0, "budget was charged ${session.minutesWorked} min for waiting")
        assertEquals(1, session.filesDone)
    }

    // ------------------------------------------------- guards during a long file

    @Test
    fun `a guard fires during an encode and cancels it, rather than waiting it out`() = runTest {
        // A full encode is minutes long. Checking only between files means a phone that is
        // picked up keeps working for the rest of the file — the complaint BUILD.md rule 7
        // exists to prevent.
        var cancelled = false
        var completed = false
        val interrupted = mutableListOf<Pair<String, PauseReason>>()
        var interruptedAt = -1L

        val guards = object : Guards {
            override val thermalHeadroom: StateFlow<Float> = MutableStateFlow(0.1f)
            override var thermalPauses: Int = 0
            private var checks = 0
            override suspend fun check(): GuardResult {
                checks += 1
                // Proceed for the first check, then the user picks the phone up.
                return if (checks <= 1) GuardResult.Proceed else GuardResult.Pause(PauseReason.FOREGROUND)
            }
        }

        val budget = NightBudget(60)
        val tracker = RunSessionTracker("r", 0, budget)
        val night = NightRun(guards, tracker, budget, { currentTime })
        val queue = mutableListOf(item("a"))

        val session = night.run(
            queue = { queue.removeFirstOrNull() },
            step = {
                try {
                    delay(5 * 60 * 1000L) // a five-minute encode
                    completed = true
                    NightRun.Outcome.Done(1)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    cancelled = true
                    throw e
                }
            },
            checkpoint = Recorder(),
            onInterrupted = { item, reason ->
                interrupted += item.id to reason
                interruptedAt = currentTime
            },
        )

        assertTrue(cancelled, "the step was not cancelled")
        assertTrue(!completed, "the encode ran to completion despite the guard")
        // Within one poll interval, not five minutes into the encode.
        assertTrue(interruptedAt in 0..30_000L, "took ${interruptedAt}ms to stand down")
        assertEquals(listOf("a" to PauseReason.FOREGROUND), interrupted)
        // Nothing was wrong with the file: it is neither skipped nor failed.
        assertEquals(0, session.filesDone)
        assertEquals(0, session.filesSkipped)
        assertEquals(0, session.filesFailed)
        // The phone stayed in use, so the run eventually hands the window back with the
        // reason it was standing down.
        assertEquals(StopReason.FOREGROUND, session.stopReason)
    }

    @Test
    fun `the run being torn down is not mistaken for a guard pause`() = runTest {
        // An OS-revoked window cancels the whole scope. Reporting that as "paused for
        // heat" would put a reason in History that never happened.
        val started = CompletableDeferred<Unit>()
        val guards = ScriptedGuards(GuardResult.Proceed)
        val budget = NightBudget(60)
        val tracker = RunSessionTracker("r", 0, budget)
        val night = NightRun(guards, tracker, budget, { currentTime })
        val queue = mutableListOf(item("a"))

        val job = backgroundScope.launch {
            night.run(
                { queue.removeFirstOrNull() },
                {
                    started.complete(Unit)
                    delay(60 * 60 * 1000L)
                    NightRun.Outcome.Done(1)
                },
                Recorder(),
            )
        }
        started.await()
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
    }

    @Test
    fun `a checkpoint is written while paused, so a lost window still explains itself`() = runTest {
        val checkpoint = Recorder()
        run(
            guards = ScriptedGuards(GuardResult.Pause(PauseReason.STORAGE_LOW), GuardResult.Proceed),
            items = listOf(item("a")),
            step = { NightRun.Outcome.Done(1) },
            checkpoint = checkpoint,
            nowMs = { currentTime },
        )()

        assertTrue(checkpoint.saved.isNotEmpty())
        assertEquals(null, checkpoint.saved.first().finishedAt, "an in-progress run has not finished")
        assertEquals(StopReason.COMPLETE, checkpoint.saved.last().stopReason)
    }
}
