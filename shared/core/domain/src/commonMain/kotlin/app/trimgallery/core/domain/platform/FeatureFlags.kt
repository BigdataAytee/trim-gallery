package app.trimgallery.core.domain.platform

/**
 * Switches for behaviour that is written but not yet safe to run.
 *
 * A flag belongs here only when the code is finished and the *evidence* is not — not as a
 * place to park half-built features. Each one names what would close it, so the flag is a
 * record of an outstanding test rather than an indefinite maybe.
 */
object FeatureFlags {

    /**
     * Whether the iOS pipeline may replace an original. **Off, and it must stay off until a
     * device says otherwise.**
     *
     * The whole § 7 contract rests on `PHPhotoLibrary.performChanges` being atomic: the
     * replacement is added and the original deleted inside one change block, so a failure
     * anywhere in the block leaves the library exactly as it was. `ReplaceSequence` is
     * tested on the JVM against a fake that throws at each step, which proves the *sequence*
     * rolls back correctly — it cannot prove that PhotoKit itself rolls back rather than
     * half-applying, because no test that does not touch a real photo library can.
     *
     * If that assumption is wrong the failure mode is the worst this app has: the original
     * deleted, the replacement absent, and nothing to restore from. That is not a risk worth
     * carrying on someone's only copy of a photograph to save a release cycle.
     *
     * With this off, `SafeReplacerIos.commit` refuses before opening a change block. The
     * read paths, the preflight, the encode and the shared sequence all still run, so the
     * iOS build stays honest about what it does: it can measure and it can save a copy, it
     * cannot replace.
     *
     * **To turn it on:** run the PhotoKit change-block atomicity procedure in PROJECT.md's
     * device-required list, on hardware, and record the result there. The procedure, not
     * this constant, is the thing that closes it.
     */
    const val IOS_REPLACE_ENABLED = false
}
