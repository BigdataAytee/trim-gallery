package app.trimgallery.engine.android

import android.content.Context

/**
 * Whether the previous launch got as far as putting a frame on the screen.
 *
 * A crash loop is not simply a crash: it is a crash in work the app starts **on its own**,
 * so restarting reproduces it and the user has no way in. The escape is to notice, on the
 * next launch, that the previous one never came back — and then not do it again.
 *
 * ## Two marks, because the first version only had one and it was the wrong one
 *
 * The first version of this class bracketed the folder scan alone. That is a real span and
 * it is still bracketed ([begin]/[complete]) — but it starts *inside* the composition, and
 * a phone came back with "Trim Gallery keeps stopping" from a crash that happened before it.
 * Everything ahead of that mark — the Koin graph, the Activity, the first composition, the
 * `koinInject` calls, reading the platform's list of granted folders — was unguarded, so a
 * throw there killed the process leaving no mark, and the next launch did the same thing.
 * Which is the loop, exactly as before, wearing a different hat.
 *
 * So there are two now:
 *
 * - [beginLaunch] / [completeLaunch] spans `MainActivity.onCreate` to the **first frame
 *   drawn**. That is the span the field report asked for and it covers everything.
 * - [begin] / [complete] spans the scan, which continues after the first frame and so
 *   cannot be covered by the first.
 *
 * [previousRunFailed] is true if *either* was left set. Neither is a superset of the other.
 *
 * ## Why `commit()`
 *
 * `commit()` rather than `apply()`, and that is the point: the process is about to run the
 * work that may kill it, and a write still sitting in a background queue when it dies is a
 * mark that was never made.
 *
 * ## What it deliberately does not catch
 *
 * A crash the user caused by tapping something *after* the first frame. That crash is
 * escapable — they simply do not tap it again — and a recovery screen after every such
 * crash would be its own kind of broken.
 *
 * The launch mark's window is a few hundred milliseconds wide, so a process killed in it
 * for an innocent reason (the user launching and immediately swiping away, a low-memory
 * kill) reads as a failure on the next launch. That is the safe direction: the cost is one
 * screen the user dismisses with "Try again anyway", and the alternative is missing the
 * failure this class exists for.
 */
class StartupGuard(context: Context) {

    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * Whether the previous run died before it had drawn anything, or inside its own
     * startup work.
     *
     * Read once, at construction, before either mark can be overwritten — otherwise the
     * answer would depend on when it was asked. Construction therefore has to happen before
     * [beginLaunch], which is why `MainActivity` resolves this first thing in `onCreate`.
     */
    val previousRunFailed: Boolean =
        preferences.getBoolean(LAUNCH_KEY, false) || preferences.getBoolean(WORK_KEY, false)

    /** About to build the screen. Cleared by [completeLaunch] when a frame has been drawn. */
    fun beginLaunch() = set(LAUNCH_KEY, true)

    /** A frame reached the glass, so whatever else happens now is not a launch failure. */
    fun completeLaunch() = set(LAUNCH_KEY, false)

    /** About to do the work that might not come back. */
    fun begin() = set(WORK_KEY, true)

    /** It came back. */
    fun complete() = set(WORK_KEY, false)

    /**
     * Forget both failures, for the user who has chosen to try again anyway.
     *
     * Separate from the completions because it means something different: they say the work
     * succeeded, this one says the user accepted the risk of finding out.
     */
    fun clear() {
        completeLaunch()
        complete()
    }

    private fun set(key: String, value: Boolean) {
        preferences.edit().putBoolean(key, value).commit()
    }

    private companion object {
        const val FILE = "startup-guard"

        /** `MainActivity.onCreate` → first frame drawn. */
        const val LAUNCH_KEY = "launch-in-progress"

        /** The folder scan, which outlives the first frame. */
        const val WORK_KEY = "startup-work-in-progress"
    }
}
