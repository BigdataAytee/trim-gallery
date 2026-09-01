package app.trimgallery.engine.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import app.trimgallery.core.model.MediaRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One frame from a video, for the grid.
 *
 * Coil draws the photographs and cannot draw these. Its `VideoFrameDecoder` is registered
 * and works, but it can only reach a `content://` document through Coil's own fetch
 * pipeline, which materialises the stream into a temp file so `MediaMetadataRetriever` has
 * something seekable. **That copies the whole video to show one frame** — a gigabyte read
 * per tile, which on a real library is why video tiles were black: the load did not fail
 * loudly, it simply never finished.
 *
 * So videos take this path instead, and it asks for the cheapest thing first:
 *
 * 1. **`DocumentsContract.getDocumentThumbnail`.** The document provider usually has one
 *    already — MediaStore keeps thumbnails for everything in DCIM — and returns it without
 *    opening the video at all.
 * 2. **`MediaMetadataRetriever` over a file descriptor.** Not a path and not a copy: the
 *    retriever seeks the descriptor itself, so it reads the header and one GOP rather than
 *    the file.
 *
 * Both results are cached to disk as JPEG, so the second launch does neither.
 *
 * @param parallelism how many frames may be extracted at once. Each one holds a hardware
 *   decoder, and a grid that flung through two hundred tiles would otherwise ask for two
 *   hundred at once and get `MediaCodec` resource errors — the same "the foreground wins
 *   the hardware" concern BUILD.md § 2 rule 2 raises for the night pass, pointed at
 *   ourselves.
 */
class VideoThumbnails(
    private val context: Context,
    parallelism: Int = DEFAULT_PARALLELISM,
    private val directory: File = File(context.cacheDir, DIRECTORY),
) {

    private val permits = Semaphore(parallelism)

    /**
     * The frame, or null when this device will not give one.
     *
     * Null is a real answer, not a failure to report: some providers have no thumbnail and
     * some files have no decodable frame. The caller draws its placeholder and moves on —
     * what it must never do is draw black and call that a picture.
     */
    suspend fun frame(ref: MediaRef, mtime: Long, sizePx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val cached = File(directory, VideoThumbnailKey.of(ref, mtime, sizePx))
        readCached(cached)?.let { return@withContext it }

        // The permit covers only the extraction. Reading a cached file is a few hundred
        // microseconds and must not queue behind a decoder.
        val bitmap = permits.withPermit { extract(ref, sizePx) } ?: return@withContext null

        writeCached(cached, bitmap)
        bitmap
    }

    private fun readCached(file: File): Bitmap? = runCatching {
        if (file.exists()) BitmapFactory.decodeFile(file.path) else null
    }.getOrNull()

    private fun writeCached(file: File, bitmap: Bitmap) {
        runCatching {
            directory.mkdirs()
            // To a temporary name, then renamed: a process killed mid-write must not leave
            // a truncated JPEG that the next launch decodes as a grey smear.
            val temp = File(directory, "${file.name}.part")
            temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
            temp.renameTo(file)
        }
    }

    private fun extract(ref: MediaRef, sizePx: Int): Bitmap? {
        val uri = runCatching { Uri.parse(ref.value) }.getOrNull() ?: return null
        return fromProvider(uri, sizePx) ?: fromRetriever(uri, sizePx)
    }

    /** What the document provider already has. No decode, and usually no file read. */
    private fun fromProvider(uri: Uri, sizePx: Int): Bitmap? = runCatching {
        DocumentsContract.getDocumentThumbnail(context.contentResolver, uri, Point(sizePx, sizePx), null)
    }.getOrNull()

    /**
     * A frame decoded from the file itself, over a descriptor so nothing is copied.
     *
     * `OPTION_CLOSEST_SYNC` at one second in: the first frame of a video shot from a pocket
     * is often black or a blur, and a sync frame a second in costs the same to reach.
     */
    private fun fromRetriever(uri: Uri, sizePx: Int): Bitmap? = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(descriptor.fileDescriptor)
                retriever.getScaledFrameAtTime(
                    FRAME_AT_US,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    sizePx,
                    sizePx,
                )
            }
        }
    }.onFailure { if (it is CancellationException) throw it }.getOrNull()

    private companion object {
        const val DIRECTORY = "video-thumbs"
        const val QUALITY = 85
        const val FRAME_AT_US = 1_000_000L

        /**
         * Four at a time.
         *
         * Enough to fill a scrolling grid without stalling on one slow file, and far below
         * the number of decoder instances a phone will hand out at once.
         */
        const val DEFAULT_PARALLELISM = 4
    }
}
