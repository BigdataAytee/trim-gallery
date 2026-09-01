package app.trimgallery.engine.android

import android.content.Context

/**
 * Whether the work the app does by itself at startup finished last time.
 *
 * A crash loop is not simply a crash: it is a crash in work the app starts **on its own**,
 * so restarting reproduces it and the user has no way in. That is what a persisted folder
 * grant plus a failing scan produced — every launch read the grant, rescanned, and died
 * before anything could be tapped, including Export diagnostics.
 *
 * So the app records when it begins that work and when it finishes. If a launch finds the
 * mark still set, the previous run started the work and never came back, and this one must
 * not start it again. It shows what happened instead and offers a way out.
 *
 * Deliberately narrow. It does **not** trip on a crash the user caused by tapping something:
 * that crash is escapable — they simply do not tap it again — and a recovery screen after
 * every such crash would be its own kind of broken. The mark covers exactly the automatic
 * span, from "a folder is granted, start scanning" to "the scan is done".
 *
 * `commit()` rather than `apply()`, and that is the point: the process is about to run the
 * work that may kill it, and a write still sitting in a background queue when it dies is a
 * mark that was never made.
 */
class StartupGuard(context: Context) {

    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * Whether the previous run died inside its own startup work.
     *
     * Read once, at construction, before [begin] can overwrite it — otherwise the answer
     * depends on when it is asked.
     */
    val previousRunFailed: Boolean = preferences.getBoolean(KEY, false)

    /** About to do the work that might not come back. */
    fun begin() {
        preferences.edit().putBoolean(KEY, true).commit()
    }

    /** It came back. */
    fun complete() {
        preferences.edit().putBoolean(KEY, false).commit()
    }

    /**
     * Forget the failure, for the user who has chosen to try again anyway.
     *
     * Separate from [complete] because they mean different things: one says the work
     * succeeded, this one says the user accepted the risk of finding out.
     */
    fun clear() {
        complete()
    }

    private companion object {
        const val FILE = "startup-guard"
        const val KEY = "startup-work-in-progress"
    }
}
