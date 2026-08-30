package app.trimgallery.gradle

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ManifestPermissionScannerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * Builds a manifest around [body]. Assembled by concatenation rather than by
     * interpolating into a `trimIndent()` block: the interpolation happens first, so
     * an indented body would change the common indent and leave whitespace in front
     * of the XML declaration.
     */
    private fun manifest(body: String): File =
        tmp.newFile("AndroidManifest-${System.nanoTime()}.xml").apply {
            writeText(
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                    "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"\n" +
                    "    package=\"app.trimgallery\">\n" +
                    body.trimIndent() + "\n" +
                    "</manifest>\n",
            )
        }

    @Test
    fun `clean manifest passes`() {
        val file = manifest(
            """
            <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
            <uses-feature android:name="android.hardware.camera" android:required="false" />
            <application android:label="Trim Gallery" />
            """,
        )
        assertEquals(emptyList<ManifestPermissionScanner.Violation>(), ManifestPermissionScanner.scan(file))
    }

    @Test
    fun `INTERNET permission is caught`() {
        val file = manifest("""<uses-permission android:name="android.permission.INTERNET" />""")
        val violations = ManifestPermissionScanner.scan(file)
        assertEquals(1, violations.size)
        assertEquals("android.permission.INTERNET", violations.single().permission)
    }

    @Test
    fun `INTERNET is caught when the android namespace uses a different prefix`() {
        // A merged manifest is machine-written and does not have to use the prefix
        // "android" — a naive string match on `android:name` would miss this.
        val file = tmp.newFile("prefixed.xml").apply {
            writeText(
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                    "<manifest xmlns:a=\"http://schemas.android.com/apk/res/android\" package=\"app.trimgallery\">\n" +
                    "    <uses-permission a:name=\"android.permission.INTERNET\" />\n" +
                    "</manifest>\n",
            )
        }
        assertEquals(1, ManifestPermissionScanner.scan(file).size)
    }

    @Test
    fun `other network permissions are caught`() {
        val file = manifest(
            """
            <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
            <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
            """,
        )
        val found = ManifestPermissionScanner.scan(file).map { it.permission }.toSet()
        assertEquals(
            setOf(
                "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.ACCESS_WIFI_STATE",
            ),
            found,
        )
    }

    @Test
    fun `permission nested anywhere in the document is caught`() {
        // Guards against a check that only looks at direct children of <manifest>.
        val file = manifest(
            """
            <queries>
                <uses-permission android:name="android.permission.INTERNET" />
            </queries>
            """,
        )
        assertEquals(1, ManifestPermissionScanner.scan(file).size)
    }

    @Test
    fun `a permission whose name merely contains INTERNET is not a violation`() {
        val file = manifest("""<uses-permission android:name="com.example.INTERNETISH" />""")
        assertEquals(emptyList<ManifestPermissionScanner.Violation>(), ManifestPermissionScanner.scan(file))
    }

    @Test
    fun `missing manifest yields no violations`() {
        assertEquals(
            emptyList<ManifestPermissionScanner.Violation>(),
            ManifestPermissionScanner.scan(File(tmp.root, "nope.xml")),
        )
    }

    @Test
    fun `unparseable manifest fails loudly rather than passing`() {
        val file = tmp.newFile("broken.xml").apply { writeText("<manifest><oops>") }
        val error = runCatching { ManifestPermissionScanner.scan(file) }.exceptionOrNull()
        assertTrue("expected a parse failure, got $error", error is IllegalStateException)
    }

    @Test
    fun `scanAll reports which manifest each violation came from`() {
        val clean = manifest("""<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />""")
        val dirty = manifest("""<uses-permission android:name="android.permission.INTERNET" />""")
        val violations = ManifestPermissionScanner.scanAll(listOf(clean, dirty))
        assertEquals(1, violations.size)
        assertEquals(dirty, violations.single().manifest)
    }

    @Test
    fun `failure message names the permission and the file`() {
        val dirty = manifest("""<uses-permission android:name="android.permission.INTERNET" />""")
        val message = ManifestPermissionScanner.failureMessage(ManifestPermissionScanner.scan(dirty))
        assertTrue(message.contains("android.permission.INTERNET"))
        assertTrue(message.contains(dirty.path))
        assertTrue(message.contains("BUILD.md rule 8"))
    }
}
