package app.trimgallery

import android.app.Application
import app.trimgallery.di.androidEngineModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

/**
 * Koin rather than Hilt.
 *
 * Hilt is Dagger-based and JVM/Android only — it cannot generate for Kotlin/Native, so
 * it cannot wire the shared modules on iOS. ARCHITECTURE.md § 3 anticipates this swap;
 * the reasoning is recorded in PROJECT.md.
 */
class TrimGalleryApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@TrimGalleryApplication)
            modules(androidEngineModule)
        }
    }
}
