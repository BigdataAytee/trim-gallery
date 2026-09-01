package app.trimgallery.engine.android

import android.os.Build
import app.trimgallery.core.data.TrimRepository
import app.trimgallery.core.domain.billing.Tier
import app.trimgallery.core.model.FolderMode
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.Settings
import app.trimgallery.core.model.UndoLocation
import app.trimgallery.core.pipeline.CodecChoice
import app.trimgallery.core.pipeline.Predictor
import app.trimgallery.core.pipeline.video.VideoOptimiseStep
import app.trimgallery.engine.SettingsStore

/**
 * Everything `VideoOptimiseStep` has to ask the rest of the app, on Android.
 *
 * The step is shared code and asks its questions through one interface; this answers them
 * from the settings store, the database and the platform's own name for itself. Keeping it
 * on this side is what lets the step stay free of a database, a `Context` and a `Build`.
 *
 * **Not bound yet**, because the step it serves is not bound yet: `YuvSource` and
 * `ProbeEncoder` have no implementation on any platform, so the search and the VMAF gate
 * have nothing to run on. This is written now because it is the half of the wiring that
 * *can* be written without an encoder, and because leaving it out would mean the next
 * change had to do both at once. See `AndroidEngineModule` for the full account of what is
 * missing.
 */
class OptimiseFacts(
    private val repository: TrimRepository,
    private val settingsStore: SettingsStore,
    private val tier: () -> Tier,
) : VideoOptimiseStep.Facts {

    override suspend fun settings(): Settings = settingsStore.read()

    override suspend fun tier(): Tier = tier.invoke()

    override suspend fun prediction(key: Predictor.Key): Predictor.Entry? = repository.prediction(key)

    /**
     * Folds a winning bitrate into the family's running mean.
     *
     * Read-then-write rather than an increment, because `Predictor.learn` keeps a Welford
     * mean and variance and needs the previous entry to update them. Two files of the same
     * family finishing at the same instant could interleave here and lose one sample; that
     * is a slightly less confident prediction, not a wrong one, and the night pass works
     * one file at a time anyway.
     */
    override suspend fun learn(key: Predictor.Key, winningBps: Int) {
        repository.learn(Predictor.learn(repository.prediction(key), key, winningBps))
    }

    /**
     * Where this file's original goes when it is replaced (BUILD.md § 6, safe-replace).
     *
     * Per item, because the mode is per folder and a library can span folders that were
     * answered differently. A file whose grant row cannot be found gets [UndoLocation.BIN]
     * — the mode that never removes anything — because "we do not know what this folder is
     * for" must not resolve to the option that deletes.
     */
    override suspend fun undoLocation(item: MediaItem): UndoLocation {
        val grant = item.folderGrantId?.let { repository.folderGrant(it) }
        return when (grant?.mode) {
            FolderMode.OFFLOAD -> UndoLocation.OFFLOAD
            FolderMode.FREE -> UndoLocation.SYSTEM_TRASH
            FolderMode.KEEP, null -> UndoLocation.BIN
        }
    }

    /**
     * What AV1 has measured on this device — null, for now, and deliberately.
     *
     * Null means "never measured", which `CodecChoice` reads as "try it": an encoder that
     * has never run cannot be demoted for being slow. The measurement comes from
     * `Job.realtimeMultiple` over finished AV1 jobs, and no AV1 job has ever finished
     * because nothing had ever optimised a file until now. Recorded in PROJECT.md; it wants
     * a query over the job table once there are rows in it to average.
     */
    override suspend fun av1Speed(): CodecChoice.MeasuredSpeed? = null

    override val platform: String = "android"

    /**
     * The device, as the predictor's key spells it.
     *
     * Manufacturer and model, because the same model name is reused across manufacturers
     * and the predictor is keyed on what the *encoder* does — which is a property of the
     * chip in this phone, not of a marketing name.
     */
    override val device: String = "${Build.MANUFACTURER} ${Build.MODEL}"
}
