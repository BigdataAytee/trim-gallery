package app.trimgallery.engine.android

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import app.trimgallery.core.model.MediaFlags
import app.trimgallery.core.model.MediaRef
import app.trimgallery.engine.ContainerFacts
import app.trimgallery.engine.ContainerReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads a file's header so triage can judge it (BUILD.md § 5, milestone 6).
 *
 * *"Triage (metadata only, no decode)."* Nothing here decodes a frame: `MediaExtractor`
 * parses the container and reports per-track formats, and `MediaMetadataRetriever` reads
 * the tag boxes. Both stop at the headers.
 *
 * Called only for files the scan found to be new or changed — a header read per file across
 * a hundred-thousand-item library would turn a second into a minute, which is why
 * `TriageStep` diffs first.
 */
class ContainerReaderAndroid(private val context: Context) : ContainerReader {

    override suspend fun read(ref: MediaRef): ContainerFacts? = withContext(Dispatchers.IO) {
        val uri = Uri.parse(ref.value)
        when {
            isImage(uri) -> readImage(uri)
            else -> readVideo(uri)
        }
    }

    // ------------------------------------------------------------------ video

    private fun readVideo(uri: Uri): ContainerFacts? {
        val extractor = MediaExtractor()
        val retriever = MediaMetadataRetriever()
        try {
            context.contentResolver.openFileDescriptor(uri, "r").use { descriptor ->
                descriptor ?: return null
                extractor.setDataSource(descriptor.fileDescriptor)
                retriever.setDataSource(descriptor.fileDescriptor)
            }

            var video: MediaFormat? = null
            var hasAudio = false
            for (track in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(track)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                when {
                    video == null && mime.startsWith("video/") -> video = format
                    mime.startsWith("audio/") -> hasAudio = true
                }
            }
            val track = video ?: return null

            return ContainerFacts(
                codec = track.getString(MediaFormat.KEY_MIME)?.substringAfter('/'),
                width = track.optInt(MediaFormat.KEY_WIDTH),
                height = track.optInt(MediaFormat.KEY_HEIGHT),
                fps = track.optInt(MediaFormat.KEY_FRAME_RATE).takeIf { it > 0 }?.toDouble(),
                bitrate = retriever.long(MediaMetadataRetriever.METADATA_KEY_BITRATE),
                durationMs = retriever.long(MediaMetadataRetriever.METADATA_KEY_DURATION),
                hasAudio = hasAudio,
                flags = MediaFlags(hdr = isHdr(track)),
                cameraModel = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR),
                takenAtEpochMs = null,
                writer = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_WRITER),
            )
        } catch (@Suppress("SwallowedException", "TooGenericExceptionCaught") e: Exception) {
            // Any failure to parse is the answer: triage skips it as an unknown format
            // rather than the pass failing on one bad file.
            return null
        } finally {
            extractor.release()
            retriever.release()
        }
    }

    /**
     * HDR, from the track's colour description rather than from the file name.
     *
     * BUILD.md § 2.5 skips HDR video in v1 — the metrics are not calibrated for it and a
     * re-encode would flatten the range. HLG and PQ are the two transfers phones shoot, and
     * an HDR clip and an SDR clip are both `.mp4`, so this is the only place the truth is.
     */
    private fun isHdr(format: MediaFormat): Boolean {
        val transfer = format.optInt(MediaFormat.KEY_COLOR_TRANSFER)
        if (transfer == MediaFormat.COLOR_TRANSFER_HLG || transfer == MediaFormat.COLOR_TRANSFER_ST2084) return true
        // Dolby Vision has its own codec mime rather than a colour hint.
        return format.getString(MediaFormat.KEY_MIME)?.contains("dolby-vision", ignoreCase = true) == true
    }

    // ------------------------------------------------------------------ image

    private fun readImage(uri: Uri): ContainerFacts? {
        val mime = context.contentResolver.getType(uri)
        val exif = runCatching {
            context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
        }.getOrNull()

        // `getLatLong()`, not `getLatLong(output)`. The overload that fills an array takes a
        // `FloatArray` — a float holds about seven significant digits, and a latitude needs
        // nine to be right to the metre, so filling one would quietly move every photo a
        // little. The no-argument form returns doubles, and null when there is no fix.
        val latLong = exif?.latLong

        return ContainerFacts(
            codec = mime?.substringAfter('/'),
            width = exif?.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0) ?: 0,
            height = exif?.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0) ?: 0,
            fps = null,
            bitrate = null,
            durationMs = null,
            flags = MediaFlags(
                // BUILD.md § 2.5. A JPEG carrying a gainmap is an Ultra HDR photo, and
                // re-encoding it drops the map and the brightness with it.
                ultraHdr = exif?.hasAttribute(ULTRA_HDR_MARKER) == true,
                raw = mime in RAW_MIMES,
            ),
            cameraModel = exif?.getAttribute(ExifInterface.TAG_MODEL),
            takenAtEpochMs = null,
            latitude = latLong?.get(0),
            longitude = latLong?.get(1),
            writer = exif?.getAttribute(ExifInterface.TAG_SOFTWARE),
        )
    }

    private fun isImage(uri: Uri): Boolean = context.contentResolver.getType(uri)?.startsWith("image/") == true

    private fun MediaFormat.optInt(key: String): Int = if (containsKey(key)) getInteger(key) else 0

    private fun MediaMetadataRetriever.long(key: Int): Long? = extractMetadata(key)?.toLongOrNull()

    private companion object {
        /**
         * The EXIF tag Ultra HDR JPEGs carry alongside their gainmap.
         *
         * Read as a marker rather than parsed: triage only needs to know the file has one,
         * because BUILD.md § 2.5 skips it either way.
         */
        const val ULTRA_HDR_MARKER = "GainMapVersion"

        val RAW_MIMES = setOf(
            "image/x-adobe-dng",
            "image/dng",
            "image/x-canon-cr2",
            "image/x-nikon-nef",
            "image/x-sony-arw",
            "image/x-panasonic-rw2",
        )
    }
}
