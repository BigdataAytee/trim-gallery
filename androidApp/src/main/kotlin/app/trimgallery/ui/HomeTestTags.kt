package app.trimgallery.ui

/**
 * The tags the Home journey drives this screen by.
 *
 * In production code rather than in the test, for the reason `OptimiseTestTags` gives: a
 * test that spells its own selectors keeps passing after the screen it describes has been
 * renamed out from under it.
 */
object HomeTestTags {
    const val SCREEN = "home-screen"
    const val FIND = "home-find-big-files"
    const val FREED = "home-freed"
    const val NEXT_RUN = "home-next-run"
    const val TOGGLE = "home-toggle"
    const val FOLDERS = "home-folders"
    const val NO_FOLDER = "home-no-folder"
}
