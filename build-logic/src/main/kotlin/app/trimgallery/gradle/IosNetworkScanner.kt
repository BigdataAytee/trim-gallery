package app.trimgallery.gradle

import java.io.File

/**
 * The iOS half of the no-network guard (ARCHITECTURE.md § 6, "Network guard": *build
 * script: fail if `NSAppTransportSecurity`/network entitlements present*).
 *
 * Android declares network reach in the manifest; iOS declares it in `Info.plist` and
 * the entitlements file. Both are plain XML plists, so one scan covers them.
 */
object IosNetworkScanner {

    /**
     * Keys whose presence means the app is asking for, or configuring, network access.
     *
     * `NSAppTransportSecurity` only ever exists to relax the rules for outbound HTTP,
     * so an app with no networking has no reason to carry it.
     */
    val FORBIDDEN_KEYS: Set<String> = setOf(
        "NSAppTransportSecurity",
        "NSLocalNetworkUsageDescription",
        "NSBonjourServices",
        "com.apple.security.network.client",
        "com.apple.security.network.server",
        "com.apple.developer.networking.wifi-info",
        "com.apple.developer.networking.multipath",
        "com.apple.developer.associated-domains",
    )

    data class Violation(val key: String, val file: File) {
        override fun toString(): String = "$key  (in ${file.path})"
    }

    private val KEY = Regex("""<key>\s*([^<]+?)\s*</key>""")

    /** Returns every forbidden key declared in [plist]; a missing file yields nothing. */
    fun scan(plist: File): List<Violation> {
        if (!plist.isFile) return emptyList()
        return KEY.findAll(plist.readText())
            .map { it.groupValues[1] }
            .filter { it in FORBIDDEN_KEYS }
            .map { Violation(it, plist) }
            .toList()
    }

    fun scanAll(plists: Iterable<File>): List<Violation> = plists.flatMap(::scan)

    fun failureMessage(violations: List<Violation>): String = buildString {
        appendLine("Network entitlement or key found in an iOS plist.")
        appendLine()
        violations.forEach { appendLine("  - $it") }
        appendLine()
        appendLine("BUILD.md rule 8: no network permission on any platform — the app states this")
        appendLine("in the UI as a feature, so it has to be literally true.")
    }
}
