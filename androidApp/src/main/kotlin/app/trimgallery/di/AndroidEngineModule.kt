package app.trimgallery.di

import androidx.media3.common.util.UnstableApi
import app.trimgallery.core.model.Uuid7
import app.trimgallery.core.pipeline.replace.UndoJournal
import app.trimgallery.engine.CodecFactory
import app.trimgallery.engine.LibraryStorage
import app.trimgallery.engine.MetadataCopier
import app.trimgallery.engine.OutputProbe
import app.trimgallery.engine.QualityScorer
import app.trimgallery.engine.Replacer
import app.trimgallery.engine.UndoStore
import app.trimgallery.engine.android.MediaCodecFactory
import app.trimgallery.engine.android.MetadataCopierAndroid
import app.trimgallery.engine.android.NativeQualityScorer
import app.trimgallery.engine.android.OutputProbeAndroid
import app.trimgallery.engine.android.SafStorage
import app.trimgallery.engine.android.SafeReplacerAndroid
import app.trimgallery.engine.android.UndoBinAndroid
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * Binds the Android implementations of the `shared/engine-api` interfaces
 * (ARCHITECTURE.md § 3, § 5).
 *
 * Shared code depends on the interfaces only; this module is the single place the Android
 * engines are named. The iOS equivalent will bind `VideoToolboxFactory`, `SafeReplacerIos`
 * and the rest against the same interfaces.
 */
@UnstableApi
val androidEngineModule = module {
    single<CodecFactory> { MediaCodecFactory(androidContext()) }

    // Milestone 2. XPSNR and VMAF over the native C ABI; the pipeline never sees JNI.
    single<QualityScorer> { NativeQualityScorer() }

    // --- Milestone 4: verify, safe replace, undo -----------------------------
    //
    // Ids are minted from one generator per process so that the UUIDv7 counter stays
    // monotonic within a millisecond (SCHEMA.md; see core.model.Uuid7, which documents why
    // it must be confined to a single thread — ARCHITECTURE.md § 8 puts every database
    // write on the IO dispatcher).
    single { Uuid7() }

    single<LibraryStorage> { SafStorage(androidContext(), newId = { get<Uuid7>().next(System.currentTimeMillis()) }) }
    single<OutputProbe> { OutputProbeAndroid() }
    single<MetadataCopier> { MetadataCopierAndroid(androidContext()) }

    single<UndoStore> {
        UndoBinAndroid(
            context = androidContext(),
            journal = get(),
            originals = get(),
            newId = { get<Uuid7>().next(System.currentTimeMillis()) },
        )
    }

    single<Replacer> {
        SafeReplacerAndroid(
            context = androidContext(),
            storage = get(),
            metadata = get(),
            undo = get(),
            journal = get<UndoJournal>(),
        )
    }

    // The UndoJournal and OriginalLocator bindings are SQLDelight-backed and land with the
    // repository wiring in milestone 5; the schema they read is already in
    // shared/core/data (SCHEMA.md `undo_entry`).
    //
    // Scheduler and indexer bindings land with milestones 5 and 9.
}
