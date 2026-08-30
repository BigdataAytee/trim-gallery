package app.trimgallery.optimiser

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Milestone 1 (BUILD.md section 13, step 1): decode → encode → mux one file with
 * Media3 Transformer, audio passed through untouched, output playable.
 *
 * Deliberately *not* here yet: bitrate probe and search (milestone 3), VMAF
 * verification and safe replace (milestone 4), scheduling and thermal limits
 * (milestone 5). This encoder writes to app-private storage and never touches the
 * source — see the `safe-replace` skill, whose central rule is that an original stays
 * read-only until a single final rename that this milestone does not perform.
 */
@UnstableApi
class TransformerEncoder(private val context: Context) {

    private val _progress = MutableStateFlow(0)

    /** Export progress, 0..100. Reset to 0 at the start of each [encode]. */
    val progress: StateFlow<Int> = _progress.asStateFlow()

    /** Where the encode ended up, and what it cost. */
    data class Result(
        val output: File,
        val sourceSizeBytes: Long,
        val outputSizeBytes: Long,
        val durationMs: Long,
        val elapsedMs: Long,
        val videoMimeType: String?,
        val audioMimeType: String?,
    ) {
        /** Output size as a fraction of the source; below 1.0 means space was saved. */
        val sizeFactor: Double
            get() = if (sourceSizeBytes > 0) outputSizeBytes.toDouble() / sourceSizeBytes else Double.NaN
    }

    /**
     * Encodes [source] to HEVC at [targetBitrate], writing to [output].
     *
     * **Must be called on the main thread.** Transformer is single-threaded and
     * asserts that `start`, `cancel` and `getProgress` all happen on the thread that
     * built it.
     *
     * Cancelling the calling coroutine cancels the export and deletes the partial
     * output: a cancelled job must never leave a half-written file behind. The same
     * applies on failure.
     */
    suspend fun encode(
        source: Uri,
        output: File,
        targetBitrate: Int = DEFAULT_TARGET_BITRATE,
    ): Result = coroutineScope {
        val startedAt = System.currentTimeMillis()
        val sourceSize = sourceSizeBytes(source)

        _progress.value = 0
        output.parentFile?.mkdirs()
        output.delete()

        val finished = CompletableDeferred<Result>()
        val transformer = buildTransformer(targetBitrate, finished, output, sourceSize, startedAt)
        val poller = launch { pollProgress(transformer) }

        try {
            transformer.start(composition(source), output.absolutePath)
            finished.await()
        } catch (t: Throwable) {
            // Covers cancellation of the calling coroutine as well as export failure.
            runCatching { transformer.cancel() }
            output.delete()
            throw t
        } finally {
            poller.cancel()
        }
    }

    private suspend fun CoroutineScope.pollProgress(transformer: Transformer) {
        val holder = ProgressHolder()
        while (isActive) {
            when (transformer.getProgress(holder)) {
                Transformer.PROGRESS_STATE_AVAILABLE -> _progress.value = holder.progress
                Transformer.PROGRESS_STATE_NOT_STARTED -> return
                else -> Unit // unavailable for this input; leave the last known value
            }
            delay(PROGRESS_POLL_MS)
        }
    }

    private fun buildTransformer(
        targetBitrate: Int,
        finished: CompletableDeferred<Result>,
        output: File,
        sourceSize: Long,
        startedAt: Long,
    ): Transformer = Transformer.Builder(context)
        // HEVC on every device. AV1 is milestone 12, and only where MediaCodecList
        // advertises a hardware AV1 encoder.
        .setVideoMimeType(MimeTypes.VIDEO_H265)
        .setEncoderFactory(
            DefaultEncoderFactory.Builder(context)
                .setVideoEncoderSelector(HardwareOnlyEncoderSelector)
                .setRequestedVideoEncoderSettings(
                    VideoEncoderSettings.Builder()
                        .setBitrate(targetBitrate)
                        // BUILD.md section 5: 2-second GOP.
                        .setiFrameIntervalSeconds(GOP_SECONDS)
                        .build(),
                )
                // Without this, Media3 quietly falls back to another encoder — which on
                // a device with no hardware HEVC encoder means a software one. Failing
                // is the correct behaviour: the file is skipped with a reason.
                .setEnableFallback(false)
                .build(),
        )
        .addListener(
            object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    finished.complete(
                        Result(
                            output = output,
                            sourceSizeBytes = sourceSize,
                            outputSizeBytes = output.length(),
                            durationMs = exportResult.durationMs,
                            elapsedMs = System.currentTimeMillis() - startedAt,
                            videoMimeType = exportResult.videoMimeType,
                            audioMimeType = exportResult.audioMimeType,
                        ),
                    )
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    finished.completeExceptionally(exportException)
                }
            },
        )
        .build()

    /**
     * One item, video re-encoded, **audio transmuxed**.
     *
     * `setTransmuxAudio(true)` is the audio-passthrough requirement of milestone 1:
     * the audio track is copied sample for sample. Re-encoding it would lose quality
     * for a saving that rounds to nothing against the video track.
     */
    private fun composition(source: Uri): Composition {
        val item = EditedMediaItem.Builder(MediaItem.fromUri(source)).build()
        return Composition.Builder(EditedMediaItemSequence.Builder(item).build())
            .setTransmuxAudio(true)
            .build()
    }

    private fun sourceSizeBytes(source: Uri): Long =
        runCatching {
            context.contentResolver.openAssetFileDescriptor(source, "r")?.use { it.length }
        }.getOrNull()?.takeIf { it >= 0 } ?: 0L

    companion object {
        /**
         * Bitrate used until the probe-and-search loop lands in milestone 3.
         *
         * 6 Mbps is a placeholder for "clearly below a phone's H.264 capture bitrate",
         * enough to prove the pipeline end to end. It is not a quality decision:
         * milestone 3 replaces it with a per-file search scored by XPSNR, and
         * milestone 4 gates the result on VMAF before anything is replaced.
         */
        const val DEFAULT_TARGET_BITRATE: Int = 6_000_000

        /** BUILD.md section 5: 2-second GOP. */
        const val GOP_SECONDS: Float = 2f

        private const val PROGRESS_POLL_MS = 250L
    }
}
