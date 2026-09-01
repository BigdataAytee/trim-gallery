package app.trimgallery.core.pipeline.video

import app.trimgallery.core.domain.billing.Tier
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaRef
import app.trimgallery.core.model.Settings
import app.trimgallery.core.model.SkipReason
import app.trimgallery.core.model.UndoLocation
import app.trimgallery.core.pipeline.CodecChoice
import app.trimgallery.core.pipeline.CodecLadder
import app.trimgallery.core.pipeline.Predictor
import app.trimgallery.core.pipeline.ProbeAndSearch
import app.trimgallery.core.pipeline.SettingSearch
import app.trimgallery.core.pipeline.night.NightRun
import app.trimgallery.core.pipeline.verify.VerifyPass
import app.trimgallery.engine.CodecFactory
import app.trimgallery.engine.ContainerReader
import app.trimgallery.engine.EncodeOutcome
import app.trimgallery.engine.EncodeSpec
import app.trimgallery.engine.LibraryStorage
import app.trimgallery.engine.ReplaceResult
import app.trimgallery.engine.Replacer
import app.trimgallery.engine.Setting
import app.trimgallery.engine.VideoCodec
import kotlinx.coroutines.CancellationException

/**
 * One video, from "this file might shrink" to "the smaller one is in its place".
 *
 * ARCHITECTURE.md § 7 names the chain and every link of it already existed and was tested;
 * what was missing was this — the assembly. `NightRun.Step` had no binding, so the night
 * pass was scheduled, woke, and could not do anything: **nothing in this app had ever
 * optimised a file.** The pieces were all built and none of them were joined.
 *
 * The order is not an implementation detail, it is the safety contract (safe-replace
 * skill), and it reads top to bottom in [optimise]:
 *
 * 1. choose the output codec, or skip — never a software encoder (BUILD.md § 2 rule 2)
 * 2. **snapshot size and mtime, before any work**
 * 3. search for the cheapest setting that should clear the quality bar
 * 4. encode the whole file, verify VMAF on three windows, step up at most twice
 * 5. confirm it is smaller, and that the original has not moved
 * 6. and only then hand a [ReplacePlan] to the one component allowed to write
 *
 * This class cannot skip step 6's gate even by mistake: a `ReplacePlan` is issued only by
 * `VerifyPass.Result.Ready`, and `Replacer` is the only writer in the app (a build guard
 * enforces that). Nothing here opens the user's file for writing, and nothing here deletes
 * an original — the original is *parked*, by the Replacer, at replace time.
 *
 * ## Why it serves two callers
 *
 * The night pass runs it over a queue in the background; "Optimise" runs it over one file
 * the user is looking at. Same chain, one difference: [background], which becomes
 * `KEY_PRIORITY = 1` on Android so a foreground camera wins the hardware from the night
 * job. A user-initiated optimise passes false — it *is* the foreground.
 *
 * BUILD.md § 2 rule 2's sibling rule keeps on-battery encoding to explicit user actions.
 * "Optimise" is exactly that: a tap, on one file, with the result shown and undoable. The
 * night pass is what waits for a charger. Recorded in PROJECT.md.
 */
class VideoOptimiseStep(
    private val storage: LibraryStorage,
    private val codecs: CodecFactory,
    private val containers: ContainerReader,
    private val probe: ProbeAndSearch,
    private val verify: VerifyPass,
    private val replacer: Replacer,
    private val facts: Facts,
) {

    /**
     * Everything this step has to ask something else about.
     *
     * One interface rather than seven constructor lambdas, because they are all questions
     * for the same place — the repository — and a caller that had to supply them
     * individually would be able to supply a set that disagreed with itself.
     */
    interface Facts {
        suspend fun settings(): Settings

        suspend fun tier(): Tier

        /** What this family has settled at before, or null the first twenty times. */
        suspend fun prediction(key: Predictor.Key): Predictor.Entry?

        /** The winning bitrate, folded into the family's running mean. */
        suspend fun learn(key: Predictor.Key, winningBps: Int)

        /**
         * Where this file's original goes when it is replaced — the folder's mode.
         *
         * Asked per item rather than read once, because it is a per-folder setting
         * (BUILD.md § 6) and a library can span folders with different answers.
         */
        suspend fun undoLocation(item: MediaItem): UndoLocation

        /** What AV1 has measured on this device, or null where it has never run. */
        suspend fun av1Speed(): CodecChoice.MeasuredSpeed?

        val platform: String
        val device: String
    }

    sealed interface Result {
        /** Replaced. [wasBytes] and [nowBytes] are what the user is shown. */
        data class Optimised(
            val wasBytes: Long,
            val nowBytes: Long,
            val vmaf: Double,
            val setting: Setting,
            val codec: VideoCodec,
            /** Where the original is now. The undo entry names it; nothing else may. */
            val undo: MediaRef,
        ) : Result {
            val savedBytes: Long get() = wasBytes - nowBytes
        }

        /** Permanently not worth doing, with the reason the Skipped list shows. */
        data class Skipped(val reason: SkipReason, val detail: String) : Result

        /** The user edited the file while we worked. Back to `NEW`, requeued, nothing lost. */
        data class SourceChanged(val detail: String) : Result

        /** Something went wrong. The original is untouched — that is the invariant. */
        data class Failed(val detail: String) : Result
    }

    /**
     * The whole chain for one file.
     *
     * @param background false only for an explicit user action, which is then the
     *   foreground and should not be deprioritised behind itself.
     * @param onProgress 0..1 through the current encode. A step-up restarts it at zero,
     *   which is honest: it is a new encode, not a continuation.
     */
    @Suppress("ReturnCount")
    suspend fun optimise(item: MediaItem, background: Boolean = true, onProgress: (Float) -> Unit = {}): Result {
        val settings = facts.settings()

        // 1. Which codec, or none. A source this device cannot encode is skipped here and
        //    never reaches an encoder — BUILD.md § 2 rule 2 has no software fallback.
        val codec = when (
            val choice = CodecChoice.choose(
                item,
                codecs.capabilities(),
                settings,
                facts.tier(),
                facts.av1Speed(),
            )
        ) {
            is CodecChoice.Choice.Skip -> return Result.Skipped(choice.reason, "no hardware encoder for this file")
            is CodecChoice.Choice.Encode -> choice.codec
        }

        // 2. The snapshot the whole safe-replace contract turns on. Before any work, so
        //    that "did this change while we were busy?" covers every second we were busy.
        val before = storage.stat(item.platformRef)
        if (!before.exists) return Result.SourceChanged("the original is gone")

        val container = containers.read(item.platformRef)
            ?: return Result.Skipped(SkipReason.UNSUPPORTED_CODEC, "the container could not be read")

        // 3. The search. Four probe encodes over three short windows, not the whole file.
        val key = Predictor.keyOf(item, facts.platform, facts.device, codec)
        val chosen = when (val found = search(item, codec, settings, key)) {
            is Searched.None -> return found.result
            is Searched.Found -> found.setting
        }

        // 4 and 5. Encode, verify, step up at most twice, re-check the snapshot. The plan
        //    only exists if all of that passed.
        val verified = verify.run(
            item = item,
            snapshot = VerifyPass.Snapshot(size = before.size, mtime = before.mtime),
            chosen = chosen,
            undoLocation = facts.undoLocation(item),
            originalHasAudio = container.hasAudio,
            target = settings.qualityTarget,
            careful = settings.carefulVerify,
        ) { setting -> encode(item, setting, codec, background, onProgress) }

        val ready = when (verified) {
            is VerifyPass.Result.Skipped -> return Result.Skipped(verified.reason, verified.detail)
            is VerifyPass.Result.SourceChanged -> return Result.SourceChanged(verified.detail)
            is VerifyPass.Result.Failed -> return Result.Failed(verified.detail)
            is VerifyPass.Result.Ready -> verified
        }

        // 6. The one write in the app. Everything above this line is read-only.
        return when (val replaced = replacer.replace(ready.plan)) {
            is ReplaceResult.Replaced -> {
                // Learned only from a file that actually landed. A setting that verified and
                // then failed to commit is not evidence about what this family needs.
                facts.learn(key, ready.setting.bitrate)
                Result.Optimised(
                    wasBytes = before.size,
                    nowBytes = replaced.newSize,
                    vmaf = ready.vmaf,
                    setting = ready.setting,
                    codec = codec,
                    undo = replaced.undoRef,
                )
            }

            ReplaceResult.SourceChanged ->
                Result.SourceChanged("the original changed between verifying and replacing")

            is ReplaceResult.RolledBack ->
                Result.Failed("the replace was rolled back: ${replaced.reason}")
        }
    }

    /**
     * The night pass's view of this step.
     *
     * `background = true`, always: the night pass is by definition not the foreground, and
     * a night job that took the hardware at foreground priority would break BUILD.md § 2
     * rule 2's promise that a camera always wins.
     */
    fun asNightStep(): NightRun.Step = NightRun.Step { item ->
        when (val result = optimise(item, background = true)) {
            is Result.Optimised -> NightRun.Outcome.Done(bytesSaved = result.savedBytes)
            is Result.Skipped -> NightRun.Outcome.Skipped
            // Neither is a failure of the file. A source that moved goes back to the queue
            // as `NEW`, which the run's own bookkeeping does; from here both simply are not
            // "done", and `Failed` is what the retry ladder reads.
            is Result.SourceChanged -> NightRun.Outcome.Skipped
            is Result.Failed -> NightRun.Outcome.Failed
        }
    }

    private sealed interface Searched {
        data class Found(val setting: Setting) : Searched

        data class None(val result: Result) : Searched
    }

    private suspend fun search(item: MediaItem, codec: VideoCodec, settings: Settings, key: Predictor.Key): Searched {
        val fallback = CodecLadder.fallbackBounds(item, codec)
        val outcome = try {
            probe.run(
                item = item,
                threshold = CodecLadder.xpsnrThreshold(codec, settings.qualityTarget),
                fallback = fallback,
                prediction = facts.prediction(key),
            ).outcome
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") failed: Throwable) {
            return Searched.None(Result.Failed("the search failed: ${failed.message}"))
        }

        return when (outcome) {
            // VBR, which is the mode the search itself ran on. BUILD.md § 5 allows CQ where
            // the encoder advertises it, but a setting found by bisecting bitrate is a
            // bitrate; handing it to the encoder as a CQ level would be a different number
            // meaning something else (codec-priority skill, "Configuring").
            is SettingSearch.Outcome.Found -> Searched.Found(Setting(bitrate = outcome.bitrateBps))
            is SettingSearch.Outcome.NotReachable -> Searched.None(
                Result.Skipped(
                    SkipReason.COULD_NOT_REACH_QUALITY,
                    "no setting reached the quality bar in ${outcome.probes.size} probes",
                ),
            )
        }
    }

    /**
     * One full-file encode into a fresh app-private temp file.
     *
     * The output never goes beside the original. `LibraryStorage.tempFile()` is app-private
     * by construction, which is what keeps a half-written encode out of the user's folder
     * if the process dies here.
     */
    private suspend fun encode(
        item: MediaItem,
        setting: Setting,
        codec: VideoCodec,
        background: Boolean,
        onProgress: (Float) -> Unit,
    ): EncodeOutcome {
        val spec = EncodeSpec(
            codec = codec,
            setting = setting,
            // The source's own resolution. Trim's promise is a smaller file of the same
            // photograph, not a smaller photograph; the 1080p scaling in `Scaling` is for
            // *measuring* probe windows, and applying it here would quietly downscale
            // everyone's 4K.
            width = item.width,
            height = item.height,
            fps = item.fps ?: 0.0,
        )
        return codecs.encoder(spec, background).encode(item.platformRef, storage.tempFile(), onProgress)
    }
}
