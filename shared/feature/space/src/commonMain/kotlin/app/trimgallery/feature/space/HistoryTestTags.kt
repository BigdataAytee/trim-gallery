package app.trimgallery.feature.space

/**
 * The tags the History journey drives this screen by.
 *
 * In production code rather than in the test, for the reason `OptimiseTestTags` gives: a
 * test that spells its own selectors keeps passing after the screen it describes has been
 * renamed out from under it — which this screen has just been.
 */
object HistoryTestTags {
    const val SCREEN = "history-screen"
    const val TOTAL = "history-total"
    const val CHANGED = "history-changed"
    const val LEFT_ALONE = "history-left-alone"
    const val EMPTY = "history-empty"

    /** One changed file, keyed by the job that changed it. */
    fun row(jobId: String): String = "history-row-$jobId"

    /** Its restore control, present only when there is an original to put back. */
    fun restore(jobId: String): String = "history-restore-$jobId"
}
