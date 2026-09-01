package app.trimgallery

import android.app.Application
import app.trimgallery.core.ui.motion.MotionSpec
import app.trimgallery.di.androidEngineModule
import app.trimgallery.engine.android.CrashReports
import app.trimgallery.engine.android.ForegroundWatcher
import app.trimgallery.engine.android.NightPass
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
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
        // First, before anything that can throw. A crash inside Koin's graph or Coil's
        // loader is exactly the kind this exists to catch, and a handler installed after
        // them would miss it.
        CrashReports(this).install()

        // BUILD.md rule 7: background work pauses whenever the app is in the foreground.
        // Registered before Koin so the guards can never read a stale "not visible" on a
        // cold start into the gallery.
        ForegroundWatcher.install(this)

        val koin = startKoin {
            androidContext(this@TrimGalleryApplication)
            modules(androidEngineModule)
        }.koin

        // Schedule the night pass if a folder is already granted. A periodic work request
        // does not survive a reinstall, and somebody whose app stopped optimising overnight
        // has no way to know they need to re-grant a folder to fix it.
        //
        // After Koin, because it resolves from the graph; KEEP means calling it on every
        // start leaves an existing schedule untouched rather than resetting its period.
        runCatching { koin.get<NightPass>().sync() }

        // Coil's singleton loader, with the video decoder registered so a video tile shows
        // a frame instead of a blank square (STACK.md names both artifacts).
        //
        // No network component is added and none is on the classpath: `coil-network-*` is
        // not a dependency, so the loader can only ever resolve the local content URI it is
        // handed. That is not a nicety — BUILD.md rule 8 says the app has no network
        // access, the manifest removes INTERNET from the *merged* result, and an image
        // loader that could fetch would be the obvious way for that to stop being true.
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .components { add(VideoFrameDecoder.Factory()) }
                // DESIGN_SYSTEM.md's `reveal` token, applied where the reveal actually
                // happens. `Modifier.arrival` already animates the tile in, but the tile is
                // a coloured rectangle until Coil finishes decoding — so on a real library
                // the container slid into place and the photograph appeared afterwards, in
                // one frame. The crossfade is the missing half.
                .crossfade(MotionSpec.Reveal.DURATION_MS)
                .build()
        }
    }
}
