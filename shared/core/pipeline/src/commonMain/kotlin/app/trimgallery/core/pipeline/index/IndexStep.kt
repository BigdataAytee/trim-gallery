package app.trimgallery.core.pipeline.index

import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.model.Settings
import app.trimgallery.engine.Indexer
import app.trimgallery.engine.LibraryStorage
import app.trimgallery.engine.PhotoCodec
import kotlinx.coroutines.CancellationException

/**
 * `IndexStep` from the ARCHITECTURE.md § 7 pass: labels, faces, OCR and hashes for one file.
 *
 * BUILD.md § 7 puts this in the same night pass as the optimisation, which is the reason it
 * is written to be interruptible and to record what it managed rather than what it
 * attempted. A night that is cut short halfway through a library must leave the half it did
 * index usable, and pick up the rest tomorrow.
 *
 * **Everything here stays on the device.** BUILD.md rule 8 and the build guards make that
 * literally true; this is the code that would be tempted otherwise, and it is not.
 */
class IndexStep(
    private val indexer: Indexer,
    private val storage: LibraryStorage,
    private val codec: PhotoCodec,
    private val sink: Sink,
) {

    /** Where index results go. Narrow on purpose, so it can be faked. */
    interface Sink {
        suspend fun labels(item: MediaItem, labels: List<app.trimgallery.core.model.Label>)
        suspend fun faces(item: MediaItem, faces: List<app.trimgallery.core.model.FaceEmbedding>)
        suspend fun text(item: MediaItem, blocks: List<app.trimgallery.core.model.TextBlock>)
        suspend fun hashes(item: MediaItem, phash: Long?, sha256: String?)
        /** Marks the item indexed, so the next pass skips it. */
        suspend fun indexed(item: MediaItem)
    }

    /** Content hashes, which only the platform can compute over a file it can read. */
    fun interface Hasher {
        /** SHA-256 of the file's bytes, hex-encoded, or null when it could not be read. */
        suspend fun sha256(item: MediaItem): String?
    }

    /** What one file's indexing produced, for the night's log. */
    data class Report(
        val labels: Int = 0,
        val faces: Int = 0,
        val textBlocks: Int = 0,
        val hashed: Boolean = false,
        val failures: List<String> = emptyList(),
    )

    /**
     * Indexes one file.
     *
     * @param settings honours the privacy switch: when face clustering is off, no embedding
     *   is computed at all — not computed and discarded, not computed and hidden.
     */
    suspend fun run(item: MediaItem, settings: Settings, hasher: Hasher): Report {
        val failures = mutableListOf<String>()

        val labels = stage(failures, "labels") {
            indexer.labels(item.platformRef).also { sink.labels(item, it) }.size
        }

        // The privacy switch, honoured by not computing rather than by discarding. The only
        // way to be sure a thing never leaves the device is not to make it
        // (USER_JOURNEY.md § 8).
        val faces = if (settings.faceClusteringEnabled) {
            stage(failures, "faces") {
                indexer.faces(item.platformRef).also { sink.faces(item, it) }.size
            }
        } else {
            0
        }

        val textBlocks = stage(failures, "text") {
            indexer.text(item.platformRef).also { sink.text(item, it) }.size
        }

        // Hashes last: they are what duplicate detection needs, and the most likely stage
        // to be interrupted on a large file.
        val hashed = stage(failures, "hashes") {
            val phash = perceptualHash(item)
            val sha = hasher.sha256(item)
            sink.hashes(item, phash, sha)
            if (phash != null || sha != null) 1 else 0
        } > 0

        sink.indexed(item)
        return Report(labels, faces, textBlocks, hashed, failures.toList())
    }

    /**
     * Runs one stage, recording a failure instead of propagating it.
     *
     * Each stage is independently guarded because a file whose OCR throws should still keep
     * its labels: the alternative is that one unusual image costs the user every kind of
     * search on it, and this runs over a hundred thousand files where unusual is certain.
     *
     * The failure list is a parameter rather than a field. It was a field once — on an
     * object the DI graph makes a singleton — which meant one bad file's failures followed
     * every file indexed after it for the rest of the night.
     */
    private suspend inline fun stage(
        failures: MutableList<String>,
        name: String,
        block: () -> Int,
    ): Int = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        failures += "$name: ${e.message}"
        0
    }

    /**
     * The perceptual hash, computed here rather than by the platform.
     *
     * ARCHITECTURE.md § 6 lists it as a shared implementation on both platforms, and it has
     * to be: two devices computing it differently would dissolve a user's duplicate groups
     * the moment their library moved. The platform's only job is to hand over pixels.
     */
    private suspend fun perceptualHash(item: MediaItem): Long? {
        // Video is hashed on its first frame elsewhere; here the pixels come from a still.
        if (item.kind == MediaKind.VIDEO || item.kind == MediaKind.FILE) return null

        val bytes = storage.openRead(item.platformRef).use { source ->
            val chunks = mutableListOf<ByteArray>()
            val buffer = ByteArray(CHUNK)
            var total = 0
            while (true) {
                val read = source.read(buffer, 0, buffer.size)
                if (read <= 0) break
                chunks += buffer.copyOf(read)
                total += read
            }
            val out = ByteArray(total)
            var offset = 0
            chunks.forEach { it.copyInto(out, offset); offset += it.size }
            out
        }

        val image = codec.decode(bytes) ?: return null
        return PerceptualHash.of(grayscale(image.rgba), image.width, image.height)
    }

    /**
     * Luma from packed RGBA, by the Rec. 601 weights.
     *
     * The same weights the metrics use, so a photograph's hash and its quality score are
     * talking about the same brightness.
     */
    private fun grayscale(rgba: ByteArray): ByteArray {
        val out = ByteArray(rgba.size / 4)
        for (i in out.indices) {
            val r = rgba[i * 4].toInt() and 0xFF
            val g = rgba[i * 4 + 1].toInt() and 0xFF
            val b = rgba[i * 4 + 2].toInt() and 0xFF
            out[i] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
        }
        return out
    }

    private companion object { const val CHUNK = 64 * 1024 }
}
