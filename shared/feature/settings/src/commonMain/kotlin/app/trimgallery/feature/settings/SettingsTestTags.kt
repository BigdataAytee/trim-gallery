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
    const val RETENTION_MORE = "settings-retention-more"
    const val ABOUT = "settings-about"
}
