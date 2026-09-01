package app.trimgallery.feature.compress

/**
 * The handles an emulator test uses to drive the Optimise sheet.
 *
 * In production code rather than in the test, for the reason `GalleryTestTags` gives: a test
 * that finds a button by its wording breaks when the wording is improved, and a UI test that
 * breaks for reasons unrelated to the bug it guards is a UI test that gets deleted.
 *
 * The exception is [SUMMARY], which a test reads *by its text* on purpose — "Now 165 MB (was
 * 380 MB)" is the sentence the whole screen exists to say, so a test that did not check the
 * words would not be checking the thing that matters.
 */
object OptimiseTestTags {
    const val SHEET = "optimise-sheet"
    const val ESTIMATE = "optimise-estimate"
    const val START = "optimise-start"
    const val PROGRESS = "optimise-progress"
    const val SUMMARY = "optimise-summary"
    const val KEEP = "optimise-keep"
    const val UNDO = "optimise-undo"
    const val REFUSAL = "optimise-refusal"
}
