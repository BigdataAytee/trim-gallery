package app.trimgallery.core.pipeline.verify

import app.trimgallery.core.model.MediaRef
import app.trimgallery.core.model.QualityTarget
import app.trimgallery.core.pipeline.Scaling
import app.trimgallery.core.pipeline.WindowPlan
import app.trimgallery.engine.OutputProbe
import app.trimgallery.engine.QualityScorer
import app.trimgallery.engine.TempFile
import app.trimgallery.engine.YuvSource
import kotlin.math.abs

/**
 * The gate that decides whether a re-encode is allowed anywhere near the user's file.
 *
 * BUILD.md § 5: *"VMAF (`vmaf_v0.6.1`, 1080p, `n_subsample=10`) on three 5-second windows:
 * start, middle, end. Confirm the file opens and reports full duration."* BUILD.md rule 3
 * puts it more bluntly: *"Never delete or replace an original until the replacement has
 * been verified."*
 *
 * This class only measures and judges. It never parks, commits or deletes anything —
 * everything it touches is the app's own temp file and a read-only handle to the original.
 * The one thing it can produce is a verdict; `ReplaceSequence` is what acts on it.
 */
class Verifier(
    private val probe: OutputProbe,
    private val yuv: YuvSource,
    private val scorer: QualityScorer,
    private val config: Config = Config(),
) {

    data class Config(
        /**
         * BUILD.md § 5, `n_subsample=10`: score every tenth frame.
         *
         * VMAF is the expensive metric — it is the reason the *search* uses XPSNR at all
         * (PROJECT.md § Speed) — and subsampling is what keeps verification affordable on
         * a phone. Ten is upstream libvmaf's own recommended value for this trade.
         */
        val subsample: Int = 10,

        /**
         * BUILD.md § 5, "1080p".
         *
         * The default `vmaf_v0.6.1` model is trained against 1080p viewed at three screen
         * heights; scoring at another resolution silently changes what a score of 95
         * means. [Scaling] never upscales, so a 720p source is still scored at 720p —
         * measuring the scaler instead of the encoder would be worse than the mismatch.
         */
        val scoringHeight: Int = 1080,

        /**
         * How far the output's reported duration may sit from the original's.
         *
         * Not zero: containers store duration in a timescale that rarely divides the frame
         * period exactly, so a faithful remux routinely lands a frame or two out. A tenth
         * of a second is under three frames at 25fps and nowhere near the truncation this
         * check exists to catch.
         */
        val durationToleranceMs: Long = 100,
    )

    /** Everything needed to judge one encode. */
    data class Request(
        val original: MediaRef,
        val originalSize: Long,
        val originalDurationMs: Long,
        val originalWidth: Int,
        val originalHeight: Int,
        /** Audio is passed through, never re-encoded — so it must survive. */
        val originalHasAudio: Boolean,
        val encoded: TempFile,
        val target: QualityTarget = QualityTarget.STANDARD,
        /** BUILD.md § 5: the opt-in "Careful" setting verifies every window, not three. */
        val careful: Boolean = false,
    )

    /** One scored window, kept so a failure can say *where* the file fell down. */
    data class WindowScore(val startMs: Long, val lengthMs: Long, val vmaf: Double)

    sealed interface Outcome {
        /**
         * Cleared every gate. [vmaf] is the worst window, not the mean.
         */
        data class Passed(val vmaf: Double, val windows: List<WindowScore>, val newSize: Long, val durationMs: Long) :
            Outcome

        /**
         * Below the quality target. The **only** retryable outcome: stepping up the
         * bitrate is what fixes this and nothing else (BUILD.md § 5, "max twice").
         */
        data class BelowTarget(val vmaf: Double, val windows: List<WindowScore>, val target: Int) : Outcome

        /**
         * The output is broken: it does not open, is truncated, or lost a track.
         *
         * Terminal. A higher bitrate does not fix a mux that dropped the audio, and
         * retrying twice would only produce two more broken files.
         */
        data class Unplayable(val detail: String) : Outcome

        /**
         * The output is not smaller than the original.
         *
         * Terminal for the same reason, from the other direction: the ladder's only move
         * is to *raise* the bitrate, which can only make this worse (BUILD.md § 5 skips
         * such a file as `WOULD_NOT_SHRINK`).
         */
        data class NotSmaller(val newSize: Long, val originalSize: Long) : Outcome
    }

    /**
     * Runs the gates in the order ARCHITECTURE.md § 7 fixes: the file opens and is whole,
     * then quality, then size.
     *
     * The cheap size check is deliberately *not* hoisted above the expensive VMAF pass.
     * Skip reasons are shown to the user (BUILD.md § 9, "Skipped list") and must stay
     * honest: a file that is both larger and visibly worse should be reported as the
     * quality failure it is, not as an arithmetic one. Nothing is wasted by the order —
     * [Outcome.NotSmaller] is terminal, so the ladder never re-encodes because of it.
     */
    suspend fun verify(request: Request): Outcome {
        val opened = probe.probe(request.encoded)
            ?: return Outcome.Unplayable("output did not open")

        if (!opened.hasVideo) return Outcome.Unplayable("output has no video track")
        if (request.originalHasAudio && !opened.hasAudio) {
            return Outcome.Unplayable("audio passthrough lost the audio track")
        }
        if (opened.sizeBytes <= 0L) return Outcome.Unplayable("output is empty")

        val drift = abs(opened.durationMs - request.originalDurationMs)
        if (drift > config.durationToleranceMs) {
            return Outcome.Unplayable(
                "output is ${opened.durationMs} ms against ${request.originalDurationMs} ms " +
                    "($drift ms out, tolerance ${config.durationToleranceMs} ms)",
            )
        }

        val scores = score(request)
        if (scores.isEmpty()) return Outcome.Unplayable("nothing to score")

        // The worst window, not the mean. Three windows exist precisely so that a file
        // which falls apart in one place — a hard cut, a dark scene — is caught; averaging
        // that away would defeat the reason for sampling more than one.
        val worst = scores.minOf { it.vmaf }
        val target = request.target.vmaf
        if (worst < target) return Outcome.BelowTarget(worst, scores, target)

        if (opened.sizeBytes >= request.originalSize) {
            return Outcome.NotSmaller(opened.sizeBytes, request.originalSize)
        }

        return Outcome.Passed(worst, scores, opened.sizeBytes, opened.durationMs)
    }

    /** Decodes each window from both files at the scoring size and scores the pair. */
    private suspend fun score(request: Request): List<WindowScore> {
        val windows =
            if (request.careful) {
                WindowPlan.fullFileWindows(request.originalDurationMs)
            } else {
                WindowPlan.verifyWindows(request.originalDurationMs)
            }

        val width = Scaling.widthFor(request.originalWidth, request.originalHeight, config.scoringHeight)

        return windows.map { window ->
            val reference = yuv.decodeWindow(request.original, window.startMs, window.lengthMs, width)
            val distorted = yuv.decodeWindow(request.encoded, window.startMs, window.lengthMs, width)
            WindowScore(
                startMs = window.startMs,
                lengthMs = window.lengthMs,
                vmaf = scorer.vmaf(reference, distorted, config.subsample),
            )
        }
    }
}
