package app.trimgallery.engine.android

import app.trimgallery.BuildConfig

/**
 * Which build this is, in one line the tester and the developer can both read.
 *
 * The first question a field report has to answer is not "what went wrong" but **"which
 * program was it"**. Without this, a crash after a fix has shipped is indistinguishable
 * from a crash on a build that predates it, and the only way to tell them apart is
 * comparing APK file sizes on a release page — which is how it was actually done, twice.
 *
 * So the same string goes in two places: Settings, where somebody can read it out, and the
 * top of the diagnostics export, where it arrives attached to the stack trace it explains.
 *
 * It carries no timestamp. `Diagnostics` in core/domain bans absolute times from the export
 * because when the app ran says when its owner sleeps, and a build date is one more number
 * that invites the same question. The commit is a better answer anyway: it says exactly
 * what the code was, which a date only approximates.
 */
object BuildIdentity {

    /** What the user sees, e.g. `Trim Gallery 0.1.0 (1) · a03262a`. */
    val line: String get() = format(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.GIT_SHA)

    /** The section that opens the diagnostics file. */
    fun lines(): String = buildString {
        appendLine("--- build ---")
        appendLine(line)
        appendLine()
    }

    /**
     * Split out so it can be tested: `BuildConfig` is generated, so a formatter that read
     * it directly could only be checked by building an APK.
     *
     * An unknown commit says so rather than being left out. A line that silently omits the
     * field is a line that looks complete and is not, and the reader has no way to tell
     * whether the build could not name its commit or whether this version of the app never
     * had one.
     */
    fun format(versionName: String, versionCode: Int, sha: String): String {
        val commit = sha.ifBlank { UNKNOWN }
        return "Trim Gallery $versionName ($versionCode) · $commit"
    }

    const val UNKNOWN = "unknown"
}
