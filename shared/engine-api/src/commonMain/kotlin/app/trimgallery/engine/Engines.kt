package app.trimgallery.engine

import app.trimgallery.core.model.FaceEmbedding
import app.trimgallery.core.model.FolderGrant
import app.trimgallery.core.model.Label
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaRef
import app.trimgallery.core.model.Settings
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

    /**
     * The same, over a file the app produced.
     *
     * Verification has to compare the original against the *encoded output*, which is a
     * temp file and deliberately has no `MediaRef` — shared code must not be able to
     * name a place in the user's library that it could then hand to something that
     * writes. Two overloads keeps that separation intact (milestone 4).
     */
    suspend fun decodeWindow(file: TempFile, start: Ms, len: Ms, width: Int): YuvWindow
}

/**
 * Reads container metadata for a file in the user's library, without decoding a frame.
 *
 * BUILD.md § 5: *"Triage (metadata only, no decode). Read codec, resolution, fps, bitrate,
 * duration from container … Read camera-written encoder metadata from `udta` where
 * present."*
 *
 * Separate from `LibraryStorage.scan`, and deliberately so. A scan of a granted tree is one
 * cursor query over thousands of rows; opening every one of those files to read its header
 * would turn a second into a minute on a large library. The pass therefore scans cheaply,
 * diffs, and reads containers only for the handful of files that actually changed.
 */
interface ContainerReader {
    /** null when the file cannot be parsed; triage then skips it as an unknown format. */
    suspend fun read(ref: MediaRef): ContainerFacts?
}

/**
 * Reads a finished temp file back the way a player would.
 *
 * The verify gate is not "the encoder said it succeeded" — it is BUILD.md § 5's *"confirm
 * the file opens and reports full duration"*. That means re-opening the muxed output with
 * the platform extractor and asking it, rather than trusting the numbers the encode
 * returned.
 */
fun interface OutputProbe {
    /** null when the file cannot be opened or holds no readable track. */
    suspend fun probe(file: TempFile): ProbedOutput?
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

/**
 * The still-image codecs (BUILD.md § 5, milestone 7).
 *
 * Bytes in, bytes out. Nothing here opens a file: the photo path reads the original
 * through `LibraryStorage.openRead` and writes its result to app-private scratch, and the
 * only thing that ever touches the user's copy is `Replacer` (see the safe-replace skill).
 */
interface PhotoCodec {
    /** Decodes to pixels, for the SSIMULACRA2 gate. null when the file cannot be read. */
    suspend fun decode(src: ByteArray): Image?

    /** JPEG → JPEG at [q] (1–100). The default path, and the one BUILD.md § 5 names first. */
    suspend fun jpegli(src: ByteArray, q: Int): ByteArray

    /** JPEG → HEIC at [q], through the platform writer (`HeifWriter` / `CGImageDestination`). */
    suspend fun heic(src: Image, q: Int): ByteArray

    /**
     * JPEG → JPEG XL, losslessly.
     *
     * The "reversible mode" setting: the original JPEG can be reconstructed bit for bit,
     * which is the one path in this app where "lossless" is literally true.
     */
    suspend fun jxlRecompress(src: ByteArray): ByteArray

    /** oxipng. Lossless by construction, so BUILD.md § 5 gives it no quality gate. */
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

    /** Writes [bytes] to a new app-private scratch file. The photo path produces bytes. */
    suspend fun writeTemp(bytes: ByteArray): TempFile

    /**
     * Throws away a temp file the app made and no longer wants.
     *
     * The counterpart to [tempFile], and the only delete in this interface. It is safe
     * because a `TempFile` is app-private by construction — a rejected encode has to go
     * somewhere, and a night that verifies a thousand files and deletes none of the
     * rejects fills the disk by morning.
     */
    suspend fun discard(file: TempFile)
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

    /** The live reading, for the Space screen. Higher is hotter; NaN where unsupported. */
    val thermalHeadroom: StateFlow<Float>

    /**
     * How many times this run has stood down for heat.
     *
     * Not in the § 5 sketch (recorded in PROJECT.md): SCHEMA.md gives `run_session` a
     * `thermal_pauses` column, and USER_JOURNEY.md § 14 shows it as *"Paused for heat 3×
     * last night"*. Reading it from whatever did the pausing is the only way the number
     * cannot drift from the behaviour it describes.
     */
    val thermalPauses: Int
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

/**
 * The settings store (ARCHITECTURE.md § 12).
 *
 * A port rather than shared code because the storage differs — `androidx.datastore` on
 * Android — while the *rules* about what may be stored do not: everything written here has
 * already been through `SettingsPolicy.sanitise`, and everything read comes back through it
 * again so that a tier change clamps values that were legal when they were written.
 *
 * [settings] is a flow because the Settings screen, the guards and the scheduler all read
 * the same values and must not disagree about them: a night pass holding a copy taken at
 * 22:00 would keep working after the user turned it off at 23:00.
 */
interface SettingsStore {
    val settings: Flow<Settings>

    /** The current values, for a caller that needs them once rather than continuously. */
    suspend fun read(): Settings

    /**
     * Applies a change.
     *
     * Takes a transform rather than a whole `Settings` so that two screens saving at once
     * cannot lose each other's field — the read and the write happen inside the store's own
     * transaction.
     */
    suspend fun update(transform: (Settings) -> Settings): Settings
}
