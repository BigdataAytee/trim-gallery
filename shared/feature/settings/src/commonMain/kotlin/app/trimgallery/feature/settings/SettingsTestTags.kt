package app.trimgallery.feature.settings

/**
 * The tags the Settings journey drives this screen by.
 *
 * In production code rather than in the test, for the reason `OptimiseTestTags` gives: a
 * test that spells its own selectors keeps passing after the screen it describes has been
 * renamed out from under it.
 */
object SettingsTestTags {
    const val SCREEN = "settings-screen"
    const val RETENTION = "settings-retention"
    const val RETENTION_LESS = "settings-retention-less"
    const val RETENTION_MORE = "settings-retention-more"

    /** The line that appears instead of a `+` once the plan's ceiling is reached. */
    const val RETENTION_CAP = "settings-retention-cap"
    const val ABOUT = "settings-about"
}
