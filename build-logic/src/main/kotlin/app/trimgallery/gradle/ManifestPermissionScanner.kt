package app.trimgallery.gradle

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Finds forbidden `<uses-permission>` entries in an Android manifest.
 *
 * BUILD.md rule 8: "No network permission in the manifest. State this in the UI as
 * a feature." That promise is only worth making if it cannot be broken by accident,
 * so it is enforced by the build rather than by review.
 *
 * Kept free of any Android Gradle Plugin types so it can be unit tested on a plain
 * JVM — see [ManifestPermissionScannerTest].
 */
object ManifestPermissionScanner {

    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    /**
     * Permissions that must never appear in a shipped manifest.
     *
     * All four grant network reach. INTERNET is the one BUILD.md names; the others
     * are included because a manifest carrying them is either a mistake or the first
     * step toward one, and the "no network" claim in the UI has to be literally true.
     */
    val FORBIDDEN_PERMISSIONS: Set<String> = setOf(
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.ACCESS_WIFI_STATE",
        "android.permission.CHANGE_NETWORK_STATE",
    )

    /** One forbidden permission, and the manifest it was found in. */
    data class Violation(val permission: String, val manifest: File) {
        override fun toString(): String = "$permission  (in ${manifest.path})"
    }

    /**
     * Returns every forbidden permission declared in [manifest].
     *
     * A manifest that does not exist yields no violations: not every variant produces
     * every manifest, and a missing file is the build's problem to report, not this
     * check's. A manifest that exists but cannot be parsed is a violation of a
     * different kind and is surfaced as an exception, because silently passing an
     * unreadable manifest would defeat the point of the check.
     */
    fun scan(manifest: File): List<Violation> {
        if (!manifest.isFile) return emptyList()

        val document = try {
            DocumentBuilderFactory.newInstance()
                .apply {
                    isNamespaceAware = true
                    // The manifest is a build input, not user content, but there is no
                    // reason for it to reach out to a DTD.
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                }
                .newDocumentBuilder()
                .parse(manifest)
        } catch (e: Exception) {
            throw IllegalStateException("Could not parse manifest ${manifest.path}", e)
        }

        val nodes = document.getElementsByTagName("uses-permission")
        val violations = mutableListOf<Violation>()
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as? Element ?: continue
            val name = element.getAttributeNS(ANDROID_NS, "name")
                .takeIf { it.isNotEmpty() }
                ?: element.getAttribute("android:name")
            if (name in FORBIDDEN_PERMISSIONS) {
                violations += Violation(name, manifest)
            }
        }
        return violations
    }

    /** Scans several manifests, skipping any that do not exist. */
    fun scanAll(manifests: Iterable<File>): List<Violation> = manifests.flatMap(::scan)

    /** The message shown when the build fails, written for whoever has to fix it. */
    fun failureMessage(violations: List<Violation>): String = buildString {
        appendLine("Forbidden network permission found in the manifest.")
        appendLine()
        violations.forEach { appendLine("  - $it") }
        appendLine()
        appendLine("BUILD.md rule 8: no network permission in the manifest — the app states")
        appendLine("this in the UI as a feature, so it has to be literally true.")
        appendLine()
        appendLine("If this came from a library's manifest rather than one of ours, the")
        appendLine("dependency is the problem: either drop it, or (only after checking it")
        appendLine("genuinely never opens a socket) strip the permission with")
        appendLine("tools:node=\"remove\" and record the reasoning in PROJECT.md.")
    }
}
