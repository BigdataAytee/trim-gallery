package app.trimgallery.di

import androidx.media3.common.util.UnstableApi
import app.trimgallery.engine.CodecFactory
import app.trimgallery.engine.QualityScorer
import app.trimgallery.engine.android.MediaCodecFactory
import app.trimgallery.engine.android.NativeQualityScorer
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * Binds the Android implementations of the `shared/engine-api` interfaces
 * (ARCHITECTURE.md § 3, § 5).
 *
 * Shared code depends on the interfaces only; this module is the single place the
 * Android engines are named. The iOS equivalent will bind `VideoToolboxFactory` and the
 * rest against the same interfaces.
 */
@UnstableApi
val androidEngineModule = module {
    single<CodecFactory> { MediaCodecFactory(androidContext()) }
    // Milestone 2. XPSNR and VMAF over the native C ABI; the pipeline never sees JNI.
    single<QualityScorer> { NativeQualityScorer() }
    // Storage, scheduler and indexer bindings land with milestones 4, 5 and 9.
}
