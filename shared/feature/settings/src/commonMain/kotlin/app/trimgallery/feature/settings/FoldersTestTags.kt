package app.trimgallery.feature.settings

/**
 * The tags the Folders journey drives this screen by.
 *
 * In production code rather than in the test, for the reason `OptimiseTestTags` gives: a
 * test that spells its own selectors is a test that keeps passing after the screen it
 * describes has been renamed out from under it.
 */
object FoldersTestTags {
    const val SCREEN = "folders-screen"
    const val EMPTY = "folders-empty"
    const val ADD = "folders-add"
    const val WHOLE_PHONE = "folders-whole-phone"
    const val WHOLE_PHONE_EXPLAINER = "folders-whole-phone-explainer"
    const val WHOLE_PHONE_CONTINUE = "folders-whole-phone-continue"

    /** One granted folder, keyed by its tree URI so the journey can name a specific row. */
    fun row(ref: String): String = "folder-row-$ref"

    /** The remove control on that row. */
    fun remove(ref: String): String = "folder-remove-$ref"

    /** One of Keep / Offload / Free on that row. */
    fun mode(ref: String, mode: String): String = "folder-mode-$ref-$mode"
}
