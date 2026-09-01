package app.trimgallery.engine.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The line that answers "which build am I on?".
 *
 * Worth testing for one reason: it is read by somebody typing it into a message, so its
 * shape has to survive a build that could not name its own commit.
 */
class BuildIdentityTest {

    @Test
    fun namesTheVersionAndTheCommit() {
        assertEquals(
            "Trim Gallery 0.1.0 (1) · a03262a",
            BuildIdentity.format(versionName = "0.1.0", versionCode = 1, sha = "a03262a"),
        )
    }

    @Test
    fun saysSoWhenItCannotNameItsCommit() {
        // Rather than leaving the field out: a line that silently omits it looks complete,
        // and the reader cannot tell "the build did not know" from "this version never
        // said".
        val line = BuildIdentity.format(versionName = "0.1.0", versionCode = 1, sha = "")

        assertTrue(line, BuildIdentity.UNKNOWN in line)
    }

    @Test
    fun carriesNoTimestamp() {
        // The same rule `Diagnostics` states for the metrics export, asserted here because
        // this line is now the first thing in that file. When the app ran says when its
        // owner sleeps, and a build date invites exactly that question.
        val line = BuildIdentity.format("0.1.0", 1, "a03262a")

        assertTrue(line, !Regex("""\d{10,}""").containsMatchIn(line))
    }
}
