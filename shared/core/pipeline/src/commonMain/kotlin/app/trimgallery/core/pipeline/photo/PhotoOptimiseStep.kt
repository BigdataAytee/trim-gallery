package app.trimgallery.core.pipeline.photo

import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.QualityTarget
import app.trimgallery.core.model.Settings
import app.trimgallery.core.model.SkipReason
import app.trimgallery.core.model.UndoLocation
import app.trimgallery.engine.Image
import app.trimgallery.engine.LibraryStorage
import app.trimgallery.engine.PhotoCodec
import app.trimgallery.engine.QualityScorer
import app.trimgallery.engine.ReplacePlan
import app.trimgallery.engine.Source

/**
 * The photo half of the night (BUILD.md § 13.7, ARCHITECTURE.md § 7).
 *
 * The same shape as `VerifyPass` on the video side, and for the same reason: it is the
 * **only** way to obtain a `ReplacePlan` for a still, so no caller can hand the Replacer a
 * photo that never passed its gate. BUILD.md rule 3 — *"never delete or replace an original
 * until the replacement has been verified"* — applies to photographs exactly as it does to
 * video, and photographs are the files people are least willing to lose.
 *
 * Where it differs from video is cost. A probe is a decode, an encode and a metric measured
 * in milliseconds rather than minutes, so the search is a real bisection over the quality
 * range rather than a four-probe budget, and there is no step-up ladder: the search either
 * finds a quality that clears the gate or the file is left alone.
 */
class PhotoOptimiseStep(
    private val storage: LibraryStorage,
    private val codec: PhotoCodec,
    private val scorer: QualityScorer,
    private val search: PhotoQualitySearch = PhotoQualitySearch(),
) {

    sealed interface Result {
        /** Verified, smaller, and the source has not moved. */
        data class Ready(
            val plan: ReplacePlan,
            val route: PhotoRoute,
            val ssim2: Double?,
            val newSize: Long,
            val quality: Int?,
            val probes: Int,
        ) : Result

        data class Skipped(val reason: SkipReason, val detail: String) : Result

        /** The user edited the file while we were working on it. Back to `NEW`, requeued. */
        data class SourceChanged(val detail: String) : Result

        data class Failed(val detail: String) : Result
    }

    @Suppress("ReturnCount")
    suspend fun run(item: MediaItem, settings: Settings, undoLocation: UndoLocation): Result {
        val route = when (val decision = PhotoRouting.decide(item, settings)) {
            is PhotoRouting.Decision.Skip -> return Result.Skipped(decision.reason, "routing")
            is PhotoRouting.Decision.Take -> decision.route
        }

        // The snapshot the safe-replace contract turns on, taken before any work.
        val before = storage.stat(item.platformRef)
        if (!before.exists) return Result.SourceChanged("the original is gone")

        val original = try {
            storage.openRead(item.platformRef).use { it.readAll() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            return Result.Failed("could not read the original: ${e.message}")
        }

        val encoded = when (route) {
            // Lossless paths. BUILD.md § 5 gives them no quality gate because there is no
            // quality question to ask: the pixels that come back are the pixels that went
            // in. They still have to come out smaller.
            PhotoRoute.PNG_REPACK -> lossless { codec.pngOptimise(original) }
            PhotoRoute.JXL_LOSSLESS -> lossless { codec.jxlRecompress(original) }

            PhotoRoute.JPEGLI, PhotoRoute.HEIC -> lossy(original, route, settings.qualityTarget)
        }

        val outcome = when (encoded) {
            is Encoded.Failure -> return encoded.result
            is Encoded.Success -> encoded
        }

        // Never replace a file with a larger one (safe-replace skill, step 4).
        if (outcome.bytes.size.toLong() >= before.size) {
            return Result.Skipped(
                SkipReason.WOULD_NOT_SHRINK,
                "output ${outcome.bytes.size} B is not smaller than ${before.size} B",
            )
        }

        // Re-check the snapshot. Photos are quick, but "quick" is not "atomic", and the
        // whole point of the check is that it costs nothing to make.
        val after = storage.stat(item.platformRef)
        if (!after.exists || after.size != before.size || after.mtime != before.mtime) {
            return Result.SourceChanged("the original changed while it was being optimised")
        }

        val temp = storage.writeTemp(outcome.bytes)

        return Result.Ready(
            plan = ReplacePlan(
                original = item.platformRef,
                mediaId = item.id,
                replacement = temp,
                expectedSize = before.size,
                expectedMtime = before.mtime,
                undoLocation = undoLocation,
            ),
            route = route,
            ssim2 = outcome.ssim2,
            newSize = outcome.bytes.size.toLong(),
            quality = outcome.quality,
            probes = outcome.probes,
        )
    }

    private sealed interface Encoded {
        data class Success(val bytes: ByteArray, val ssim2: Double?, val quality: Int?, val probes: Int) : Encoded

        data class Failure(val result: Result) : Encoded
    }

    private suspend inline fun lossless(encode: () -> ByteArray): Encoded = try {
        Encoded.Success(encode(), ssim2 = null, quality = null, probes = 0)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        Encoded.Failure(Result.Failed("lossless repack failed: ${e.message}"))
    }

    /**
     * Bisects the quality range for the most aggressive setting that still clears
     * SSIMULACRA2.
     *
     * The reference is decoded **once** and reused for every probe, for the same reason the
     * video search caches its window: the decode is a large share of the cost, and paying it
     * per probe would turn a millisecond search into a slow one.
     */
    private suspend fun lossy(original: ByteArray, route: PhotoRoute, target: QualityTarget): Encoded {
        val reference = codec.decode(original)
            ?: return Encoded.Failure(Result.Skipped(SkipReason.UNSUPPORTED_CODEC, "could not decode"))

        // JPEG has no alpha and HEIC as written here has none either, so a transparent
        // source would come back with its transparency silently flattened to whatever
        // happened to be behind it. That is a visible change the quality gate cannot see —
        // SSIMULACRA2 would be comparing the flattened result against a flattened
        // reference and reporting a perfect match.
        if (reference.hasTransparency()) {
            return Encoded.Failure(
                Result.Skipped(SkipReason.UNSUPPORTED_CODEC, "transparency would be lost"),
            )
        }

        var bestBytes: ByteArray? = null
        var bestQuality = -1

        val outcome = try {
            search.search(search.targetFor(target)) { quality ->
                val bytes = encodeAt(original, reference, route, quality)
                val candidate = codec.decode(bytes) ?: return@search Double.NEGATIVE_INFINITY
                val score = scorer.ssim2(reference, candidate)
                // Keep the bytes for the winning probe so the search never has to re-encode
                // at the answer it already tried.
                if (bestQuality == -1 || quality <= bestQuality) {
                    bestBytes = bytes
                    bestQuality = quality
                }
                score
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            return Encoded.Failure(Result.Failed("encoding failed: ${e.message}"))
        }

        return when (outcome) {
            is PhotoQualitySearch.Outcome.NotReachable -> Encoded.Failure(
                Result.Skipped(
                    SkipReason.COULD_NOT_REACH_QUALITY,
                    "best SSIMULACRA2 ${outcome.bestScore} after ${outcome.probes.size} probes",
                ),
            )

            is PhotoQualitySearch.Outcome.Found -> {
                // The winning quality is not necessarily the last one tried, so re-encode
                // at it unless the cached bytes already are it.
                val bytes = if (bestQuality == outcome.quality && bestBytes != null) {
                    bestBytes
                } else {
                    encodeAt(original, reference, route, outcome.quality)
                }
                Encoded.Success(bytes, outcome.score, outcome.quality, outcome.probes.size)
            }
        }
    }

    private suspend fun encodeAt(original: ByteArray, reference: Image, route: PhotoRoute, quality: Int): ByteArray =
        when (route) {
            PhotoRoute.HEIC -> codec.heic(reference, quality)
            else -> codec.jpegli(original, quality)
        }

    /**
     * Whether any pixel is less than fully opaque.
     *
     * A linear pass over one byte in four, which is nothing beside the metric it protects.
     */
    private fun Image.hasTransparency(): Boolean {
        var i = ALPHA_OFFSET
        while (i < rgba.size) {
            if (rgba[i] != OPAQUE) return true
            i += BYTES_PER_PIXEL
        }
        return false
    }

    /** Photos are small enough to hold whole; a 50 MP JPEG is tens of megabytes. */
    private fun Source.readAll(): ByteArray {
        val chunks = mutableListOf<ByteArray>()
        var total = 0
        val buffer = ByteArray(CHUNK)
        while (true) {
            val read = read(buffer, 0, buffer.size)
            if (read <= 0) break
            chunks += buffer.copyOf(read)
            total += read
        }
        val out = ByteArray(total)
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(out, offset)
            offset += chunk.size
        }
        return out
    }

    private companion object {
        const val CHUNK = 64 * 1024
        const val BYTES_PER_PIXEL = 4
        const val ALPHA_OFFSET = 3
        const val OPAQUE = 0xFF.toByte()
    }
}
