package app.trimgallery.gradle

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IosNetworkScannerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun plist(name: String, body: String): File =
        File(tmp.root, name).apply {
            writeText(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<plist version=\"1.0\">\n<dict>\n$body\n</dict>\n</plist>\n",
            )
        }

    @Test
    fun `a clean Info plist passes`() {
        val file = plist("Info.plist", "  <key>CFBundleName</key>\n  <string>Trim Gallery</string>")
        assertEquals(emptyList<IosNetworkScanner.Violation>(), IosNetworkScanner.scan(file))
    }

    @Test
    fun `NSAppTransportSecurity is caught`() {
        val file = plist("Info.plist", "  <key>NSAppTransportSecurity</key>\n  <dict/>")
        assertEquals("NSAppTransportSecurity", IosNetworkScanner.scan(file).single().key)
    }

    @Test
    fun `network entitlements are caught`() {
        val file = plist(
            "TrimGallery.entitlements",
            "  <key>com.apple.security.network.client</key>\n  <true/>",
        )
        assertEquals("com.apple.security.network.client", IosNetworkScanner.scan(file).single().key)
    }

    @Test
    fun `whitespace around the key name does not hide it`() {
        val file = plist("Info.plist", "  <key> NSBonjourServices </key>\n  <array/>")
        assertEquals("NSBonjourServices", IosNetworkScanner.scan(file).single().key)
    }

    @Test
    fun `missing plist yields no violations`() {
        assertEquals(emptyList<IosNetworkScanner.Violation>(), IosNetworkScanner.scan(File(tmp.root, "none.plist")))
    }

    @Test
    fun `failure message cites the rule`() {
        val file = plist("Info.plist", "  <key>NSAppTransportSecurity</key>\n  <dict/>")
        val message = IosNetworkScanner.failureMessage(IosNetworkScanner.scan(file))
        assertTrue(message.contains("NSAppTransportSecurity"))
        assertTrue(message.contains("BUILD.md rule 8"))
    }
}
