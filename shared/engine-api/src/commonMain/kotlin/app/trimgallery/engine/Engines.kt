package app.trimgallery.engine

import app.trimgallery.core.model.FaceEmbedding
import app.trimgallery.core.model.FolderGrant
import app.trimgallery.core.model.Label
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaRef
import app.trimgallery.core.model.TextBlock
import app.trimgallery.core.model.UndoEntry
import app.trimgallery.core.model.UndoLocation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * ARCHITECTURE.md § 5 — the whole platform surface, as interfaces.
 *
 * Everything in `shared/core/pipeline` is written against these and tested with fakes
 * (§ 2.7). Nothing here mentions MediaCodec, AVFoundation, SAF or PhotoKit: that is the
 * point. The Android and iOS implementations live in `androidApp/` and `iosApp/`.
 */

/**
 * The only place a video codec is ever created.
 *
 * A build guard enforces it (ARCHITECTURE.md § 14) because BUILD.md § 2.2 — hardware
 * only, never software — is only as strong as its weakest call site.
 */
interface CodecFactory {
    fun capabilities(): CodecCaps

    /** @param background true for night work; Android maps it to `KEY_PRIORITY = 1`. */
    fun encoder(spec: EncodeSpec, background: Boolean): HwEncoder
}

/** A full-file encode: decode → encode → mux, with the audio track passed through. */
interface HwEncoder {
    suspend fun encode(input: MediaRef, out: TempFile, onProgress: (Float) -> Unit): EncodeOutcome
}

/** Encodes a single probe window during the search. Milestone 3. */
interface ProbeEncoder {
    suspend fun encodeWindow(yuv: YuvWindow, setting: Setting): YuvWindow
}

/** Decodes probe windows. Windows are decoded once and cached (PROJECT.md § Speed). */
interface YuvSource {
    suspend fun decodeWindow(ref: MediaRef, start: Ms, len: Ms, width: Int): YuvWindow
}

/**
 * The quality metrics, over the native C ABI (ARCHITECTURE.md § 10).
 *
 * XPSNR drives the search because it is 10–20× cheaper than VMAF; VMAF verifies on
 * sampled windows only; SSIMULACRA2 gates photos.
 */
interface QualityScorer {
    suspend fun xpsnr(a: YuvWindow, b: YuvWindow): Double
    suspend fun vmaf(a: YuvWindow, b: YuvWindow, subsample: Int): Double
    suspend fun ssim2(a: Image, b: Image): Double
}

interface PhotoCodec {
    suspend fun jpegli(src: ByteArray, q: Int): ByteArray
    suspend fun heic(src: Image, q: Int): ByteArray
    suspend fun jxlRecompress(src: ByteArray): ByteArray
    suspend fun pngOptimise(src: ByteArray): ByteArray
}

/** On-device indexing: ML Kit on Android, Vision on iOS. Nothing leaves the device. */
interface Indexer {
    suspend fun labels(ref: MediaRef): List<Label>
    suspend fun faces(ref: MediaRef): List<FaceEmbedding>
    suspend fun text(ref: MediaRef): List<TextBlock>
}

/**
 * Read-only access to the user's library, plus app-owned scratch space.
 *
 * There is no `openWrite`. Writing is `Replacer`'s job and nothing else's.
 */
interface LibraryStorage {
    fun scan(grants: List<FolderGrant>): Flow<MediaItem>
    suspend fun stat(ref: MediaRef): Stat
    suspend fun openRead(ref: MediaRef): Source
    suspend fun tempFile(): TempFile
}

/**
 * The only writer (ARCHITECTURE.md § 5, § 14).
 *
 * Contract, in order (§ 7): copy metadata → park original → commit replacement under
 * the original identity → restore timestamps → notify library → write UndoEntry. Any
 * failure rolls back in reverse; the original is never lost.
 */
interface Replacer {
    suspend fun replace(plan: ReplacePlan): ReplaceResult
}

/** Parks and restores originals. Inside the write boundary, called by `Replacer`. */
interface UndoStore {
    suspend fun park(ref: MediaRef, mode: UndoLocation): UndoEntry
    suspend fun restore(entry: UndoEntry)
    suspend fun sweep(nowEpochMs: Long)
}

/** Carries date, GPS, rotation, colour and EXIF/XMP onto the replacement (BUILD.md § 2.4). */
interface MetadataCopier {
    suspend fun copy(from: MediaRef, to: TempFile)
}

/** The stop conditions, evaluated in the order fixed by ARCHITECTURE.md § 9. */
interface Guards {
    suspend fun check(): GuardResult
    val thermalHeadroom: StateFlow<Float>
}

/** WorkManager on Android, BGProcessingTask on iOS. */
interface NightScheduler {
    fun schedule(constraints: NightConstraints)
    fun cancel()
}

/**
 * Play-to-compress (BUILD.md § 9): the one path allowed to encode on battery, and only
 * on an explicit user action. Exposes a tap on the decoded frames during playback.
 */
interface Player {
    suspend fun tapDecodedFrames(ref: MediaRef, onFrame: suspend (YuvWindow) -> Unit)
}
