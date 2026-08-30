package app.trimgallery.engine.android

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.EncoderSelector
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import app.trimgallery.core.model.MediaRef
import app.trimgallery.engine.BitrateMode
import app.trimgallery.engine.EncodeOutcome
import app.trimgallery.engine.EncodeSpec
import app.trimgallery.engine.HwEncoder
import app.trimgallery.engine.TempFile
import app.trimgallery.engine.VideoCodec
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Milestone 1 (BUILD.md § 13.1, ARCHITECTURE.md § 15): decode → encode → mux one file
 * with Media3 Transformer, audio passed through, at background codec priority.
 *
 * Constructed only by [MediaCodecFactory], which owns encoder selection — this class
 * never asks for a codec by name.
 *
 * Not here yet: the bitrate search (milestone 3) and verification + safe replace
 * (milestone 4). This writes to app-private storage and never touches the source; see
 * the `safe-replace` skill.
 */
@UnstableApi
class TransformerEncoder(
    private val context: Context,
    private val spec: EncodeSpec,
    private val background: Boolean,
    private val encoderSelector: EncoderSelector,
) : HwEncoder {

    /**
     * Encodes [input] into [out].
     *
     * Transformer is single-threaded and asserts that `start`, `cancel` and
     * `getProgress` all happen on the thread that built it, so the whole exchange is
     * pinned to the main dispatcher regardless of the caller's context.
     *
     * Cancelling the calling coroutine cancels the export and deletes the partial
     * output: a cancelled job never leaves a half-written file behind. The same applies
     * on failure.
     */
    override suspend fun encode(
        input: MediaRef,
        out: TempFile,
        onProgress: (Float) -> Unit,
    ): EncodeOutcome = withContext(Dispatchers.Main) {
        val startedAt = System.currentTimeMillis()
        val output = File(out.path)
        output.parentFile?.mkdirs()
        output.delete()

        val finished = CompletableDeferred<EncodeOutcome>()
        val transformer = buildTransformer(finished, output, startedAt)

        coroutineScope {
            val poller = launch { pollProgress(transformer, onProgress) }
            try {
                transformer.start(composition(input), output.absolutePath)
                finished.await()
            } catch (t: Throwable) {
                // Covers cancellation of the caller as well as export failure.
                runCatching { transformer.cancel() }
                output.delete()
                throw t
            } finally {
                poller.cancel()
            }
        }
    }

    private suspend fun CoroutineScope.pollProgress(transformer: Transformer, onProgress: (Float) -> Unit) {
        val holder = ProgressHolder()
        while (isActive) {
            when (transformer.getProgress(holder)) {
                Transformer.PROGRESS_STATE_AVAILABLE -> onProgress(holder.progress / PERCENT)
                Transformer.PROGRESS_STATE_NOT_STARTED -> return
                else -> Unit // unavailable for this input; leave the last known value
            }
            delay(PROGRESS_POLL_MS)
        }
    }

    private fun buildTransformer(
        finished: CompletableDeferred<EncodeOutcome>,
        output: File,
        startedAt: Long,
    ): Transformer = Transformer.Builder(context)
        .setVideoMimeType(
            when (spec.codec) {
                VideoCodec.HEVC -> MimeTypes.VIDEO_H265
                VideoCodec.AV1 -> MimeTypes.VIDEO_AV1
            },
        )
        .setEncoderFactory(
            DefaultEncoderFactory.Builder(context)
                .setVideoEncoderSelector(encoderSelector)
                .setRequestedVideoEncoderSettings(videoEncoderSettings())
                // Without this Media3 quietly falls back to another encoder, which on a
                // device with no hardware HEVC encoder means a software one. Failing is
                // correct: the file is skipped with a reason (BUILD.md § 2.2).
                .setEnableFallback(false)
                .build(),
        )
        .addListener(
            object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    finished.complete(
                        EncodeOutcome.Success(
                            output = TempFile(output.absolutePath),
                            bytes = output.length(),
                            durationMs = exportResult.durationMs,
                            videoMimeType = exportResult.videoMimeType,
                            audioMimeType = exportResult.audioMimeType,
                            elapsedMs = System.currentTimeMillis() - startedAt,
                        ),
                    )
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    output.delete()
                    finished.complete(exportException.toOutcome())
                }
            },
        )
        .build()

    private fun videoEncoderSettings(): VideoEncoderSettings =
        VideoEncoderSettings.Builder()
            .setBitrate(spec.setting.bitrate)
            .apply {
                if (spec.setting.mode == BitrateMode.CQ) {
                    // Only reachable when CodecCaps.cqSupported said so; the search runs
                    // on VBR because CQ is not universally supported (PROJECT.md).
                    setBitrateMode(android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)
                }
            }
            .setiFrameIntervalSeconds(spec.gopSeconds)
            // Background codec priority. KEY_PRIORITY = 1 is what lets a foreground
            // camera or video call reclaim the hardware from the night job
            // (BUILD.md § 5, `codec-priority` skill). Play-to-compress passes
            // background = false, since the user is watching that encode happen.
            .setEncoderPerformanceParameters(
                VideoEncoderSettings.RATE_UNSET,
                if (background) MediaCodecFactory.PRIORITY_BACKGROUND else MediaCodecFactory.PRIORITY_REALTIME,
            )
            .build()

    /**
     * One item, video re-encoded, **audio transmuxed**.
     *
     * `setTransmuxAudio(true)` is milestone 1's audio-passthrough requirement: the audio
     * track is copied sample for sample. Re-encoding it would lose quality for a saving
     * that rounds to nothing against the video track.
     */
    private fun composition(input: MediaRef): Composition {
        val item = EditedMediaItem.Builder(MediaItem.fromUri(input.value)).build()
        return Composition.Builder(EditedMediaItemSequence.Builder(item).build())
            .setTransmuxAudio(true)
            .build()
    }

    /**
     * Maps an export failure onto the outcomes the pipeline knows how to act on
     * (ARCHITECTURE.md § 13). A reclaim is `Interrupted` and gets retried at 5/15/60 s;
     * it is never a permanent failure, and never a reason to try software.
     */
    private fun ExportException.toOutcome(): EncodeOutcome = when (errorCode) {
        ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
        ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
        -> EncodeOutcome.NoHardwareEncoder

        ExportException.ERROR_CODE_ENCODING_FAILED,
        ExportException.ERROR_CODE_DECODING_FAILED,
        -> EncodeOutcome.Interrupted(message ?: "encoder interrupted")

        else -> EncodeOutcome.Failed(message ?: "export failed (code $errorCode)")
    }

    private companion object {
        const val PROGRESS_POLL_MS = 250L
        const val PERCENT = 100f
    }
}
