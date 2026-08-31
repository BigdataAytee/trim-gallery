package app.trimgallery.core.pipeline.verify

import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.QualityTarget
import app.trimgallery.core.model.SkipReason
import app.trimgallery.core.model.UndoLocation
import app.trimgallery.engine.EncodeOutcome
import app.trimgallery.engine.LibraryStorage
import app.trimgallery.engine.ReplacePlan
import app.trimgallery.engine.Setting
import kotlin.math.roundToInt

/**
 * Everything between "the search picked a setting" and "the Replacer may be called".
 *
 * ARCHITECTURE.md § 7 lays out the chain: *"full hardware encode → VMAF on 3 windows →
 * step-up ≤ 2 → re-check snapshot → `Replacer.replace(plan)`"*. This class owns that
 * middle stretch, and it is the **only** way to obtain a [ReplacePlan]: a plan is issued
 * exclusively by [Result.Ready], so no caller can construct one for a file that never
 * passed verification.
 *
 * Nothing here writes to the user's library, and nothing here can. The only file it
 * deletes is a temp file it asked for itself.
 */
class VerifyPass(
    private val verifier: Verifier,
    private val storage: LibraryStorage,
    private val config: Config = Config(),
) {

    data class Config(
        /**
         * BUILD.md § 5: *"step up one notch and re-encode; max twice"*.
         *
         * A cap, not a target. The night has a minute budget (BUILD.md § 6) and a file
         * that needs three attempts is telling us the predictor's bracket was wrong for
         * its whole family — a problem to fix in the predictor, not to spend a night on.
         */
        val maxStepUps: Int = 2,

        /**
         * How much "one notch" raises the bitrate.
         *
         * Fifteen per cent, chosen against the search's own resolution rather than picked
         * for roundness: `SettingSearch` stops bisecting once the bracket is within 12% of
         * its top, so any step smaller than that lands inside the noise the search already
         * declared indistinguishable and would burn an encode to move nothing.
         */
        val stepUpFactor: Double = 1.15,
    ) {
        init {
            require(maxStepUps >= 0) { "maxStepUps must not be negative" }
            require(stepUpFactor > 1.0) { "a step up must raise the bitrate" }
        }
    }

    /** The size and mtime taken *before* the encode started (safe-replace skill, step 1). */
    data class Snapshot(val size: Long, val mtime: Long)

    /** One encode attempt, run by the caller so this class never touches a codec. */
    fun interface Encode {
        suspend fun run(setting: Setting): EncodeOutcome
    }

    /** What one attempt cost and scored, kept for `Job` and the metrics log (BUILD.md § 14). */
    data class Attempt(val setting: Setting, val outcome: Verifier.Outcome)

    sealed interface Result {
        val attempts: List<Attempt>

        /**
         * Verified, smaller, and the source has not moved. The plan may be replaced.
         */
        data class Ready(
            val plan: ReplacePlan,
            val vmaf: Double,
            val newSize: Long,
            val setting: Setting,
            override val attempts: List<Attempt>,
        ) : Result

        /** Permanently skipped, with the reason the user will be shown (BUILD.md § 9). */
        data class Skipped(val reason: SkipReason, val detail: String, override val attempts: List<Attempt>) : Result

        /**
         * The user edited the file while we were encoding it.
         *
         * Not a failure and not a skip: the item goes back to `NEW` and is queued again,
         * because what we measured is no longer what is on disk (safe-replace skill,
         * step 5).
         */
        data class SourceChanged(val detail: String, override val attempts: List<Attempt>) : Result

        /** The encode itself failed or was interrupted; retried per ARCHITECTURE.md § 13. */
        data class Failed(val detail: String, override val attempts: List<Attempt>) : Result
    }

    /**
     * @param snapshot taken before the first encode; re-checked before the plan is issued.
     * @param chosen the setting the XPSNR search settled on (milestone 3).
     */
    @Suppress("LongParameterList", "ReturnCount")
    suspend fun run(
        item: MediaItem,
        snapshot: Snapshot,
        chosen: Setting,
        undoLocation: UndoLocation,
        originalHasAudio: Boolean,
        target: QualityTarget = QualityTarget.STANDARD,
        careful: Boolean = false,
        encode: Encode,
    ): Result {
        val duration = item.duration
            ?: return Result.Skipped(SkipReason.UNSUPPORTED_CODEC, "no duration", emptyList())

        val attempts = ArrayList<Attempt>()
        var setting = chosen

        // One encode, plus at most `maxStepUps` re-encodes at a higher bitrate.
        repeat(config.maxStepUps + 1) { index ->
            val encoded = when (val outcome = encode.run(setting)) {
                is EncodeOutcome.Success -> outcome
                is EncodeOutcome.NoHardwareEncoder ->
                    return Result.Skipped(SkipReason.NO_HARDWARE_ENCODER, "no hardware encoder", attempts)
                is EncodeOutcome.Interrupted -> return Result.Failed(outcome.reason, attempts)
                is EncodeOutcome.Failed -> return Result.Failed(outcome.reason, attempts)
            }

            val verdict = verifier.verify(
                Verifier.Request(
                    original = item.platformRef,
                    originalSize = snapshot.size,
                    originalDurationMs = duration,
                    originalWidth = item.width,
                    originalHeight = item.height,
                    originalHasAudio = originalHasAudio,
                    encoded = encoded.output,
                    target = target,
                    careful = careful,
                ),
            )
            attempts += Attempt(setting, verdict)

            when (verdict) {
                is Verifier.Outcome.Passed -> {
                    // Step 5 of the safe-replace contract, and the last thing checked
                    // before a plan exists: if the file moved under us while the encode
                    // ran, everything measured above describes a file that is gone.
                    val now = storage.stat(item.platformRef)
                    val moved = changed(now.exists, now.size, now.mtime, snapshot)
                    if (moved != null) {
                        storage.discard(encoded.output)
                        return Result.SourceChanged(moved, attempts)
                    }
                    return Result.Ready(
                        plan = ReplacePlan(
                            original = item.platformRef,
                            mediaId = item.id,
                            replacement = encoded.output,
                            expectedSize = snapshot.size,
                            expectedMtime = snapshot.mtime,
                            undoLocation = undoLocation,
                        ),
                        vmaf = verdict.vmaf,
                        newSize = verdict.newSize,
                        setting = setting,
                        attempts = attempts,
                    )
                }

                is Verifier.Outcome.BelowTarget -> {
                    storage.discard(encoded.output)
                    if (index == config.maxStepUps) {
                        return Result.Skipped(
                            SkipReason.COULD_NOT_REACH_QUALITY,
                            "could not reach quality: best VMAF ${format(verdict.vmaf)} " +
                                "against a target of ${verdict.target} after ${attempts.size} attempts",
                            attempts,
                        )
                    }
                    setting = stepUp(setting)
                }

                is Verifier.Outcome.NotSmaller -> {
                    // Terminal: the ladder's only move is to raise the bitrate, which can
                    // only make an already-larger file larger still.
                    storage.discard(encoded.output)
                    return Result.Skipped(
                        SkipReason.WOULD_NOT_SHRINK,
                        "output ${verdict.newSize} B is not smaller than ${verdict.originalSize} B",
                        attempts,
                    )
                }

                is Verifier.Outcome.Unplayable -> {
                    // Also terminal: a broken mux is not a bitrate problem.
                    storage.discard(encoded.output)
                    return Result.Failed(verdict.detail, attempts)
                }
            }
        }

        // Unreachable: the loop returns on every path. Kept so the compiler does not have
        // to take that on trust.
        return Result.Failed("verification did not terminate", attempts)
    }

    /** One notch up, rounded to whole bits per second. */
    internal fun stepUp(setting: Setting): Setting =
        setting.copy(bitrate = (setting.bitrate * config.stepUpFactor).roundToInt())

    /** Describes how the source moved, or null if it did not. */
    private fun changed(exists: Boolean, size: Long, mtime: Long, snapshot: Snapshot): String? = when {
        !exists -> "the original is gone"
        size != snapshot.size -> "size moved from ${snapshot.size} to $size"
        mtime != snapshot.mtime -> "mtime moved from ${snapshot.mtime} to $mtime"
        else -> null
    }

    private fun format(v: Double): String {
        val hundredths = (v * 100).roundToInt()
        return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
    }
}
