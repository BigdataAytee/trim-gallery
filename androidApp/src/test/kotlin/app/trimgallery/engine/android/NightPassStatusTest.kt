package app.trimgallery.engine.android

import app.trimgallery.engine.NightConstraints
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the diagnostics export says about the night pass.
 *
 * The warning line is the reason this test exists. The whole failure it describes —
 * folders granted, nothing enqueued — was invisible for the life of the project: the
 * scheduler was written, bound and never called, and no screen said so. A field report
 * that reads "scheduled: no" beside "granted folders: 1" turns that from something only a
 * WorkManager inspector could see into something a sentence says.
 */
class NightPassStatusTest {

    private val allConstraints = NightConstraints()

    @Test
    fun warnsWhenFoldersAreGrantedButNothingIsEnqueued() {
        val lines = NightPassStatus(scheduled = false, state = null, runAttempts = 0)
            .lines(allConstraints, grantedFolders = 1)

        assertTrue(lines, lines.contains("WARNING: folders are granted but no work is enqueued."))
    }

    @Test
    fun doesNotWarnWhenNothingIsGranted() {
        // Nothing enqueued and nothing granted is the correct state on a fresh install,
        // not a fault. Warning there would train the reader to ignore the warning.
        val lines = NightPassStatus(scheduled = false, state = null, runAttempts = 0)
            .lines(allConstraints, grantedFolders = 0)

        assertTrue(lines, !lines.contains("WARNING"))
    }

    @Test
    fun doesNotWarnWhenWorkIsEnqueued() {
        val lines = NightPassStatus(scheduled = true, state = "ENQUEUED", runAttempts = 0)
            .lines(allConstraints, grantedFolders = 2)

        assertTrue(lines, !lines.contains("WARNING"))
        assertTrue(lines, lines.contains("state: ENQUEUED"))
    }

    @Test
    fun namesEveryConstraintThatIsOn() {
        val lines = NightPassStatus(scheduled = true, state = "ENQUEUED", runAttempts = 0)
            .lines(allConstraints, grantedFolders = 1)

        listOf("charging", "idle", "storage-not-low", "battery-full").forEach { constraint ->
            assertTrue("$constraint missing from: $lines", lines.contains(constraint))
        }
    }

    @Test
    fun saysNoneRatherThanAnEmptyListWhenEveryConstraintIsOff() {
        val lines = NightPassStatus(scheduled = true, state = "ENQUEUED", runAttempts = 0).lines(
            NightConstraints(
                requiresCharging = false,
                requiresIdle = false,
                requiresStorageNotLow = false,
                requiresBatteryFull = false,
            ),
            grantedFolders = 1,
        )

        assertTrue(lines, lines.contains("constraints: none"))
    }

    /**
     * The redaction rule, asserted rather than remembered.
     *
     * `Diagnostics` in core/domain bans absolute timestamps from the export because when
     * the night pass ran says when its owner sleeps. Nothing here may drift into carrying
     * one.
     */
    @Test
    fun carriesNoTimestamps() {
        val lines = NightPassStatus(scheduled = true, state = "ENQUEUED", runAttempts = 3)
            .lines(allConstraints, grantedFolders = 1)

        // Ten digits or more is an epoch second or millisecond; nothing legitimate here is
        // that long, since the counts are small integers.
        assertTrue(lines, !Regex("""\d{10,}""").containsMatchIn(lines))
    }
}
