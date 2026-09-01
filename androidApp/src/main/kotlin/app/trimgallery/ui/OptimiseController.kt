package app.trimgallery.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.media3.common.util.UnstableApi
import app.trimgallery.core.data.TrimRepository
import app.trimgallery.core.domain.billing.Tier
import app.trimgallery.core.domain.compress.CompressNow
import app.trimgallery.core.domain.compress.OptimiseFlow
import app.trimgallery.core.domain.skip.SkipList
import app.trimgallery.core.model.Job
import app.trimgallery.core.model.JobState
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.Uuid7
import app.trimgallery.core.pipeline.video.VideoOptimiseStep
import app.trimgallery.engine.UndoStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.Job as CoroutineJob

/**
 * Runs one Optimise, and holds the sheet's state while it does.
 *
 * The screen is `OptimiseSheet` and the rules are `OptimiseFlow`; this is the part that has
 * to touch Android — a coroutine scope tied to the composition, the repository, and the step
 * that actually replaces a file. Separated so that the sheet stays testable without a device
 * and the flow stays testable without Compose.
 *
 * ## `background = false`
 *
 * The one deliberate difference from the night pass. `VideoOptimiseStep` maps it to
 * `KEY_PRIORITY = 0`, and an explicit tap *is* the foreground: deprioritising it behind
 * itself would make the user watch a progress ring crawl while the phone waited for work
 * nobody asked for. BUILD.md § 2 rule 1 allows exactly this — one file, on battery, because
 * the user asked for it by name.
 *
 * ## Undo means the original goes back
 *
 * Not "delete the smaller one". `Replacer` parked the original and wrote an `UndoEntry`
 * naming where it went, so a restore is that row plus `UndoStore.restore`. Looking the row
 * up from the database rather than holding it in memory is what lets Undo still work if the
 * process was killed between the replace and the tap.
 */
@Stable
@UnstableApi
// Six collaborators and one clock. They are what running an optimise from a tap needs, and
// each is a parameter so a test can replace exactly one: the emulator this screen is tested
// on has no hardware encoder, so `step` is the one that must be fakeable.
@Suppress("LongParameterList")
class OptimiseController(
    private val scope: CoroutineScope,
    private val run: Run,
    private val undoStore: UndoStore,
    private val repository: TrimRepository,
    private val tier: () -> Tier,
    private val onLibraryChanged: suspend () -> Unit,
    private val clocks: Clocks,
) {

    /**
     * The ambient facts this needs and cannot ask the graph for.
     *
     * One interface rather than three lambdas because they are one question — what time is
     * it, and what id comes next — and because a caller supplying them separately could
     * supply a set that disagrees with itself: a job stamped "now" that lands before the
     * start of the day it is counted against would be invisible to the very limit it is
     * meant to feed.
     */
    /**
     * One optimise, as this screen needs it.
     *
     * An interface rather than the `VideoOptimiseStep` itself for two reasons. It fixes
     * `background = false` in one place — an explicit tap *is* the foreground, and
     * deprioritising it behind itself would make the user watch a ring crawl while the
     * phone served work nobody asked for. And it is the only way this screen can be tested
     * at all: the emulator CI runs on has no hardware encoder, and BUILD.md § 2 rule 2
     * forbids the software one, so the step has to be replaceable.
     */
    fun interface Run {
        suspend fun optimise(item: MediaItem, onProgress: (Float) -> Unit): VideoOptimiseStep.Result
    }

    interface Clocks {
        fun newJobId(): String

        /** Midnight this morning, in the user's own zone, for the free-tier daily count. */
        fun startOfTodayMs(): Long

        fun now(): Instant
    }

    var state: OptimiseFlow.State by mutableStateOf(OptimiseFlow.State.Closed)
        private set

    /**
     * The encode in flight, or null.
     *
     * Aliased, because `Job` in this file means the row in the job table — the thing Space
     * and the free-tier count read — and having the two mean different things on adjacent
     * lines is how the first version of this file failed to compile at all.
     */
    private var running: CoroutineJob? = null

    /** A hold on a tile. Opens the sheet — with an estimate, or with the reason it cannot. */
    fun open(item: MediaItem) {
        scope.launch {
            // Started, not finished: a user who cancels at 99% has already spent the
            // battery, so counting completions would make the daily limit meaningless.
            // `compressNowsSince` counts `job` rows with `user_initiated = 1` — which is
            // why [start] writes one before it begins rather than only when it succeeds.
            val usedToday = repository.compressNowsSince(clocks.startOfTodayMs())
            state = OptimiseFlow.open(item, CompressNow.decide(item, tier(), usedToday))
        }
    }

    /**
     * The tap that lets the app change a file.
     *
     * `OptimiseFlow.start` refuses anything that is not an offer, so a second tap on a slow
     * phone cannot begin a second encode of the same file; the `running` job is belt and
     * braces for the same thing across a recomposition.
     */
    fun start() {
        val offered = state as? OptimiseFlow.State.Offered ?: return
        if (running?.isActive == true) return
        state = OptimiseFlow.start(state)

        running = scope.launch {
            val wasBytes = offered.item.size

            // Written before the encode, not after it, for two reasons that both matter.
            // It is what the free-tier daily count reads, and counting only completions
            // would let a user who cancels at 99% run it all day on a battery already
            // spent. And it is what Space and History read: a run that left no row would
            // free real space and report nothing, which is the complaint this screen exists
            // to answer.
            val job = Job(
                id = clocks.newJobId(),
                mediaId = offered.item.id,
                state = JobState.ENCODING,
                startedAt = clocks.now(),
                originalSize = wasBytes,
                userInitiated = true,
            )
            repository.saveJob(job)

            val result = run.optimise(offered.item) { fraction ->
                state = OptimiseFlow.progress(state, fraction)
            }

            val finished = describe(result)
            repository.saveJob(complete(job, finished))
            state = OptimiseFlow.finish(state, finished)
            onLibraryChanged()
        }
    }

    /** "Keep it" — nothing to do but close. The replace already happened, and verified. */
    fun keep() {
        state = OptimiseFlow.State.Closed
    }

    fun dismiss() {
        state = OptimiseFlow.State.Closed
    }

    /**
     * Put the original back.
     *
     * The entry is found by media id rather than carried from the result, so that a restore
     * works after the process died — which, for a night pass, is the ordinary case. If the
     * row is gone the state does not move: the sheet keeps saying what it said, rather than
     * reporting a restore that did not happen.
     */
    fun undo() {
        val done = state as? OptimiseFlow.State.Done ?: return
        if (!done.mayUndo) return

        scope.launch {
            val entry = repository.undoByMedia()[done.item.id] ?: return@launch
            undoStore.restore(entry)
            state = OptimiseFlow.undone(state)
            onLibraryChanged()
        }
    }

    /**
     * The same row, closed out with what actually happened.
     *
     * `SUCCEEDED` only where the file was replaced. A skip and a failure are both `FAILED`
     * here with their reason attached, because History lists successes and the Skipped
     * screen lists the rest — a skip recorded as a success would tell the user something
     * changed when nothing did.
     */
    private fun complete(job: Job, finished: OptimiseFlow.Finished): Job = when (finished) {
        is OptimiseFlow.Finished.Optimised -> job.copy(
            state = JobState.SUCCEEDED,
            finishedAt = clocks.now(),
            // Corrected to what the step measured, for the same reason the sheet shows that
            // number: this row opened with the size a scan recorded, and Space subtracts
            // these two to report what was freed.
            originalSize = finished.wasBytes,
            newSize = finished.nowBytes,
        )

        is OptimiseFlow.Finished.Skipped -> job.copy(
            state = JobState.FAILED,
            finishedAt = clocks.now(),
            error = finished.detail,
        )

        is OptimiseFlow.Finished.Failed -> job.copy(
            state = JobState.FAILED,
            finishedAt = clocks.now(),
            error = finished.detail,
        )
    }

    /**
     * The step's result, in the words the sheet shows.
     *
     * `SourceChanged` is deliberately not a skip: nothing was wrong with the file and
     * nothing was replaced, so it offers no undo and says what actually happened, which is
     * that the user changed the file while Trim was working on it.
     */
    private fun describe(result: VideoOptimiseStep.Result): OptimiseFlow.Finished = when (result) {
        // `result.wasBytes`, not the item's own `size`. Both mean "how big it was" and they
        // disagree: the item's size is what a library scan recorded, which on a file the
        // user has just edited can be minutes old, while the step's is the `storage.stat()`
        // snapshot taken immediately before the encode — the same number the whole
        // safe-replace contract is checked against. "Was 380 MB" has to be the size the file
        // actually had, and Space subtracts these two to report what was freed.
        is VideoOptimiseStep.Result.Optimised -> OptimiseFlow.Finished.Optimised(
            wasBytes = result.wasBytes,
            nowBytes = result.nowBytes,
            undoRef = result.undo.value,
        )

        is VideoOptimiseStep.Result.Skipped ->
            OptimiseFlow.Finished.Skipped(result.reason, SkipList.explain(result.reason))

        is VideoOptimiseStep.Result.SourceChanged ->
            OptimiseFlow.Finished.Failed("This file changed while Trim was working on it, so nothing was replaced.")

        is VideoOptimiseStep.Result.Failed ->
            OptimiseFlow.Finished.Failed("Trim couldn't finish this one. Your original is untouched.")
    }
}

/**
 * A controller bound to the composition, with the graph's own step and stores.
 *
 * Parameters rather than direct `get()` calls so an instrumented test can hand it a faked
 * step — which is the only way to test this screen at all, since the emulator it runs on has
 * no hardware encoder and BUILD.md rule 2 forbids the software one.
 */
@Composable
@UnstableApi
fun rememberOptimiseController(
    step: VideoOptimiseStep = koinInject(),
    undoStore: UndoStore = koinInject(),
    repository: TrimRepository = koinInject(),
    tier: () -> Tier = { Tier.FREE },
    onLibraryChanged: suspend () -> Unit = {},
    ids: Uuid7 = koinInject(),
): OptimiseController {
    val scope = rememberCoroutineScope()
    return remember(step, undoStore, repository) {
        OptimiseController(
            scope = scope,
            // The one place `background = false` is decided. See `Run`.
            run = { item, onProgress -> step.optimise(item, background = false, onProgress) },
            undoStore = undoStore,
            repository = repository,
            tier = tier,
            onLibraryChanged = onLibraryChanged,
            clocks = AndroidClocks(ids),
        )
    }
}

/** The device's own answers to [OptimiseController.Clocks]. */
private class AndroidClocks(private val ids: Uuid7) : OptimiseController.Clocks {
    override fun newJobId(): String = ids.next(System.currentTimeMillis())
    override fun startOfTodayMs(): Long = midnightThisMorningMs()
    override fun now(): Instant = Clock.System.now()
}

/**
 * Midnight this morning, in the device's own zone.
 *
 * The user's calendar rather than UTC, for the reason the monthly cap uses theirs: a UTC
 * day boundary gives someone in Auckland their five runs back thirteen hours early, and
 * takes them away thirteen hours early from someone in Los Angeles.
 */
private fun midnightThisMorningMs(): Long {
    val zone = TimeZone.currentSystemDefault()
    return Clock.System.now().toLocalDateTime(zone).date.atStartOfDayIn(zone).toEpochMilliseconds()
}
