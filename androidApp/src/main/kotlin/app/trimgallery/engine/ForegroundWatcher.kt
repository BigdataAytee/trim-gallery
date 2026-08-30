package app.trimgallery.engine.android

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Whether the gallery is on screen.
 *
 * BUILD.md rule 7: *"The gallery UI must stay at display refresh rate while background
 * work runs. Background work pauses whenever the app is in the foreground."* The first
 * half is a performance target; the second half needs someone to actually know, and this
 * is the cheapest thing that does — a counter of started activities, which is exactly the
 * definition Android itself uses for "visible".
 *
 * Counting started rather than resumed activities so that a rotation, which stops one
 * activity and starts another, never reads as the app having gone away for an instant. A
 * flicker there would let the pass grab a codec while the grid is mid-recreate, which is
 * the one moment it is least able to spare it.
 */
object ForegroundWatcher : Application.ActivityLifecycleCallbacks {

    private var started = 0

    val isForeground: Boolean get() = started > 0

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) { started += 1 }

    override fun onActivityStopped(activity: Activity) { started = (started - 1).coerceAtLeast(0) }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
