package app.trimgallery.engine.android

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Stack traces from crashes, kept on disk so they can be exported from the phone.
 *
 * Written because a field report can only ever say *what happened on screen*. The first
 * real use of this app produced "tapping a photo closes it", which is true and which
 * narrows the cause to about forty candidate lines; the exception behind it names one.
 * Without a cable there is no logcat, so the app has to keep its own.
 *
 * Deliberately not a crash *reporter*: nothing is uploaded, because BUILD.md rule 8 says
 * this app has no network access and two build guards keep that literally true. The file
 * sits in app-private storage until the user chooses to share it, which is the same
 * bargain the diagnostics export already makes.
 */
class CrashReports(private val context: Context) {

    private val directory: File get() = File(context.filesDir, DIRECTORY).apply { mkdirs() }

    /**
     * Installs the handler, chaining whatever was there before.
     *
     * The previous handler is the one that shows "app keeps stopping" and ends the
     * process. Replacing it rather than chaining would leave a crashed app sitting live
     * with a dead UI, which is worse than the crash.
     */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Never let the handler itself crash the crash: whatever happens here, the
            // process still has to reach the platform handler below.
            runCatching { write(thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * Records a failure the app caught rather than died from.
     *
     * The startup guard turns a crash in the app's own work into a recovery screen, and
     * that screen's whole purpose is to show what happened — but a caught exception never
     * reaches the uncaught handler, so without this the screen opens on "No crashes
     * recorded" for exactly the failure it was opened for. Same file, same format: the
     * export makes no distinction, because to the person reading it there is none.
     */
    fun record(error: Throwable) {
        runCatching { write(Thread.currentThread(), error) }
    }

    /** Every stored report, newest first. */
    fun reports(): List<File> = directory.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()

    /** The reports as one text block, for the diagnostics export. Empty when there are none. */
    fun asReport(): String {
        val files = reports()
        if (files.isEmpty()) return "No crashes recorded.\n"
        return buildString {
            appendLine("${files.size} crash report(s), newest first.")
            files.forEach { file ->
                appendLine()
                appendLine("--- ${file.name} ---")
                appendLine(file.readText())
            }
        }
    }

    fun clear() {
        directory.listFiles()?.forEach { it.delete() }
    }

    private fun write(thread: Thread, error: Throwable) {
        // Oldest first out, so a crash loop cannot fill the disk and cannot push out the
        // report the user is about to send by crashing again on the way to Settings.
        val existing = reports()
        if (existing.size >= MAX_REPORTS) {
            existing.drop(MAX_REPORTS - 1).forEach { it.delete() }
        }

        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val report = buildString {
            appendLine("time: ${System.currentTimeMillis()}")
            appendLine("thread: ${thread.name}")
            // The device and build, because "works on mine" is the first thing a crash
            // report has to rule out. No identifiers: model and API level describe the
            // hardware, not the person holding it.
            appendLine("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            appendLine()
            append(trace)
        }
        File(directory, "crash-${System.currentTimeMillis()}.txt").writeText(report)
    }

    private companion object {
        const val DIRECTORY = "crash-reports"

        /** Enough to show a pattern, few enough that a crash loop cannot fill the disk. */
        const val MAX_REPORTS = 10
    }
}
