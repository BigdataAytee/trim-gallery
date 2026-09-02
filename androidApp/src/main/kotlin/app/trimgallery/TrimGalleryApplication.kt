package app.trimgallery

import android.app.Application
import app.trimgallery.di.androidEngineModule
import app.trimgallery.engine.SettingsStore
import app.trimgallery.engine.android.CrashReports
import app.trimgallery.engine.android.ForegroundWatcher
import app.trimgallery.engine.android.NightPass
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
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
        // First, before anything that can throw. A crash inside Koin's graph or the
        // loader is exactly the kind this exists to catch, and a handler installed after
        // them would miss it.
        val crashes = CrashReports(this)
        crashes.install()

        // BUILD.md rule 7: background work pauses whenever the app is in the foreground.
        // Registered before Koin so the guards can never read a stale "not visible" on a
        // cold start into the gallery.
        ForegroundWatcher.install(this)

        // Wrapped, and this is not defensiveness for its own sake.
        //
        // A throw here happens before any Activity exists, so it kills the process with no
        // screen ever shown — no recovery screen, no Export diagnostics, nothing but "Trim
        // Gallery keeps stopping". And because the graph is built identically on every
        // launch, it kills every launch. That is the worst shape of the crash loop: the one
        // where the app cannot even tell the user what happened.
        //
        // So the failure is recorded and kept, and `MainActivity` shows it instead of a
        // gallery it has no graph to build. Recorded here rather than left to the uncaught
        // handler because the process has to survive to draw the screen that reports it.
        val koin = runCatching {
            startKoin {
                androidContext(this@TrimGalleryApplication)
                modules(androidEngineModule)
            }.koin
        }.onFailure { failure ->
            startupFailure = failure
            crashes.record(failure)
        }.getOrNull()

        // Schedule the night pass if a folder is already granted. A periodic work request
        // does not survive a reinstall, and somebody whose app stopped optimising overnight
        // has no way to know they need to re-grant a folder to fix it.
        //
        // After Koin, because it resolves from the graph; KEEP means calling it on every
        // start leaves an existing schedule untouched rather than resetting its period.
        // In a coroutine because the switch lives in a suspending store: reading it on the
        // main thread would block start-up on disk, and scheduling a beat later costs
        // nothing — the work is periodic and nightly.
        MainScope().launch {
            runCatching {
                val enabled = koin?.get<SettingsStore>()?.read()?.nightPassEnabled ?: true
                koin?.get<NightPass>()?.sync(enabled = enabled)
            }
        }
    }

    companion object {
        /**
         * What went wrong building the app, or null if nothing did.
         *
         * A field on the Application rather than something in the graph, because the graph
         * is the thing that may not exist. `MainActivity` reads it before it resolves
         * anything, and shows it rather than a screen it cannot build.
         *
         * `@Volatile` because it is written on the main thread during `onCreate` and read
         * from an Activity that the system may start on a different one.
         */
        @Volatile
        var startupFailure: Throwable? = null
            private set
    }
}
