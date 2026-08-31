package app.trimgallery.engine.android

import android.media.MediaExtractor
import android.media.MediaFormat
import app.trimgallery.engine.OutputProbe
import app.trimgallery.engine.ProbedOutput
import app.trimgallery.engine.TempFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Re-opens a finished encode the way a player would.
 *
 * BUILD.md § 5 asks for exactly this before anything is replaced: *"Confirm the file opens
 * and reports full duration."* The encoder's own return value is not that confirmation — a
 * muxer can report success and leave a file that stops halfway, or one whose audio track
 * never got written.
 *
 * `MediaExtractor` rather than `MediaMetadataRetriever`: the extractor answers per-track,
 * so a missing audio track is visible rather than inferred, and it fails loudly on a file
 * it cannot parse instead of returning nulls.
 */
class OutputProbeAndroid : OutputProbe {

    override suspend fun probe(file: TempFile): ProbedOutput? = withContext(Dispatchers.IO) {
        val path = File(file.path)
        if (!path.isFile || path.length() == 0L) return@withContext null

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(path.absolutePath)

            var hasVideo = false
            var hasAudio = false
            var longestUs = 0L

            for (track in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(track)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                when {
                    mime.startsWith("video/") -> hasVideo = true
                    mime.startsWith("audio/") -> hasAudio = true
                }
                // The longest track, not the first: the file plays for as long as its
                // longest stream, and a video track that stops early behind a full-length
                // audio track is precisely the truncation this exists to catch.
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    longestUs = maxOf(longestUs, format.getLong(MediaFormat.KEY_DURATION))
                }
            }

            if (!hasVideo && !hasAudio) return@withContext null

            ProbedOutput(
                durationMs = longestUs / MICROS_PER_MS,
                hasVideo = hasVideo,
                hasAudio = hasAudio,
                sizeBytes = path.length(),
            )
        } catch (@Suppress("SwallowedException", "TooGenericExceptionCaught") e: Exception) {
            // Any failure to parse is the answer: this file does not open.
            null
        } finally {
            extractor.release()
        }
    }

    private companion object {
        const val MICROS_PER_MS = 1_000L
    }
}
