package app.trimgallery.feature.compress

/**
 * The tags the Big files journey drives this screen by.
 *
 * In production code rather than in the test, for the reason `OptimiseTestTags` gives: a
 * test that spells its own selectors keeps passing after the screen it describes has been
 * renamed out from under it.
 */
object BigFilesTestTags {
    const val SCREEN = "big-files-screen"
    const val SCANNING = "big-files-scanning"
    const val EMPTY = "big-files-empty"

    /** The heading over the files that can be trimmed, carrying the total saving. */
    const val TOTAL = "big-files-total"

    /** The second section: big files that will not shrink, and why. */
    const val CANNOT = "big-files-cannot"

    fun row(id: String): String = "big-file-$id"

    fun trim(id: String): String = "big-file-trim-$id"

    /** One reason group in the can't-be-trimmed section. */
    fun reason(heading: String): String = "big-file-reason-$heading"
}
