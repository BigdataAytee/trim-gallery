package app.trimgallery.gradle

import java.io.File

/**
 * Enforces the architectural boundaries that BUILD.md's non-negotiable rules depend on
 * (ARCHITECTURE.md § 14, "Build guards"): some APIs may only be called from the one
 * class that is allowed to own them.
 *
 * Two of the three guards in this project are of that shape — codecs may only be
 * created in `CodecFactory`, and a user's library may only be written by `Replacer` —
 * so they share one scanner rather than one implementation each.
 *
 * Deliberately a plain-JVM text scan with no Android Gradle Plugin, Kotlin compiler or
 * PSI dependency: it has to run in CI before anything Android resolves, and it has to
 * be unit testable without an SDK.
 */
object SourceBoundaryScanner {

    /**
     * One boundary.
     *
     * @param id short name, used in task names and messages
     * @param patterns the calls that are restricted
     * @param allowedFileNames base file names permitted to make those calls
     * @param rationale why the boundary exists, printed on failure
     */
    data class Rule(
        val id: String,
        val patterns: List<Regex>,
        val allowedFileNames: Set<String>,
        val rationale: String,
    )

    data class Violation(
        val rule: Rule,
        val file: File,
        val line: Int,
        val text: String,
    ) {
        override fun toString(): String = "${file.path}:$line  ${text.trim()}"
    }

    /**
     * Creating a video codec anywhere but the platform `CodecFactory`.
     *
     * BUILD.md rule 2 bans software video encoding. That rule is only as strong as its
     * weakest call site: a single `createEncoderByType` in some helper is a software
     * encoder on any device without the hardware one, and nothing above it would
     * notice. Funnelling every codec through `CodecFactory` is what makes the
     * hardware-only check unavoidable rather than merely usual.
     */
    val CODEC_FACTORY_ONLY = Rule(
        id = "codecFactory",
        patterns = listOf(
            Regex("""MediaCodec\s*\.\s*create(Encoder|Decoder)ByType"""),
            Regex("""MediaCodec\s*\.\s*createByCodecName"""),
            Regex("""\bMediaCodecList\s*\("""),
            Regex("""\bVTCompressionSessionCreate\b"""),
        ),
        allowedFileNames = setOf(
            "MediaCodecFactory.kt",
            "VideoToolboxFactory.kt",
        ),
        rationale = "Codecs are created only in a CodecFactory (ARCHITECTURE.md § 5), so the " +
            "hardware-only rule in BUILD.md § 2.2 cannot be bypassed. See the codec-priority skill.",
    )

    /**
     * Writing to the user's library anywhere but the platform `Replacer`.
     *
     * ARCHITECTURE.md § 2.2: originals are read-only until the single atomic replace.
     * `UndoStore` implementations are inside the boundary because the Replacer contract
     * (§ 7) delegates parking the original to them; nothing else is.
     */
    val REPLACER_ONLY = Rule(
        id = "replacer",
        patterns = listOf(
            Regex("""DocumentsContract\s*\.\s*(rename|move|delete|create)Document"""),
            Regex("""contentResolver\s*\.\s*openOutputStream"""),
            Regex("""DocumentFile[^\n]*\.\s*(createFile|createDirectory|renameTo|delete)\s*\("""),
            Regex("""\bPHAssetChangeRequest\b"""),
            Regex("""\bdeleteAssets\s*\("""),
        ),
        allowedFileNames = setOf(
            "SafeReplacerAndroid.kt",
            "SafeReplacerIos.kt",
            "UndoBinAndroid.kt",
            "UndoBinIos.kt",
        ),
        rationale = "A user's library is written only by Replacer (ARCHITECTURE.md § 5, § 14). " +
            "Everything else reads through LibraryStorage.openRead and writes to " +
            "LibraryStorage.tempFile(). See the safe-replace skill.",
    )

    /**
     * Any use of a networking API, anywhere.
     *
     * BUILD.md rule 8 promises no network access and says to state it in the UI as a
     * feature. The manifest/entitlement check proves nothing was *declared*; this
     * proves nothing was *written*. ARCHITECTURE.md § 6 asks for exactly this pairing
     * ("no URLSession usage lint"). The allow-list is empty on purpose.
     */
    val NO_NETWORK_API = Rule(
        id = "noNetwork",
        patterns = listOf(
            Regex("""\bjava\.net\.(URL|Socket|HttpURLConnection|DatagramSocket)\b"""),
            Regex("""\bHttpURLConnection\b"""),
            Regex("""\bokhttp3\b|\bretrofit2\b|\bio\.ktor\.client\b"""),
            Regex("""\b(NS)?URLSession\b"""),
            Regex("""\bandroid\.permission\.INTERNET\b"""),
        ),
        allowedFileNames = emptySet(),
        rationale = "Trim Gallery has no network access at all (BUILD.md § 2.8). Nothing may " +
            "open a socket, and no code may name the INTERNET permission.",
    )

    val DEFAULT_RULES = listOf(CODEC_FACTORY_ONLY, REPLACER_ONLY, NO_NETWORK_API)

    private val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)

    /**
     * Blanks out comments and string literals so a rule name mentioned in a doc comment
     * or an error message is not itself a violation. Replaces with spaces rather than
     * deleting, to keep line numbers and columns intact.
     */
    internal fun strip(source: String): String {
        val noBlocks = BLOCK_COMMENT.replace(source) { m -> m.value.map { if (it == '\n') '\n' else ' ' }.joinToString("") }
        return noBlocks.lineSequence().joinToString("\n") { line ->
            val lineComment = line.indexOf("//")
            val code = if (lineComment >= 0) line.substring(0, lineComment) else line
            // Blank string literals: "..." and """...""" fragments on this line.
            code.replace(Regex(""""([^"\\]|\\.)*""""), { m -> " ".repeat(m.value.length) })
        }
    }

    /** Scans one file against every rule that applies to it. */
    fun scan(file: File, rules: List<Rule> = DEFAULT_RULES): List<Violation> {
        if (!file.isFile) return emptyList()
        val applicable = rules.filterNot { file.name in it.allowedFileNames }
        if (applicable.isEmpty()) return emptyList()

        val lines = strip(file.readText()).lines()
        val violations = mutableListOf<Violation>()
        lines.forEachIndexed { index, line ->
            applicable.forEach { rule ->
                if (rule.patterns.any { it.containsMatchIn(line) }) {
                    violations += Violation(rule, file, index + 1, line)
                }
            }
        }
        return violations
    }

    fun scanAll(files: Iterable<File>, rules: List<Rule> = DEFAULT_RULES): List<Violation> =
        files.flatMap { scan(it, rules) }

    /** The failure message, written for whoever has to fix it. */
    fun failureMessage(violations: List<Violation>): String = buildString {
        appendLine("Architectural boundary violated.")
        appendLine()
        violations.groupBy { it.rule }.forEach { (rule, found) ->
            appendLine("  ${rule.id}: ${rule.rationale}")
            appendLine("  Allowed only in: ${rule.allowedFileNames.sorted().joinToString(", ")}")
            found.forEach { appendLine("    - $it") }
            appendLine()
        }
        appendLine("If a new file genuinely belongs inside one of these boundaries, add it to the")
        appendLine("rule's allow-list in SourceBoundaryScanner and record why in PROJECT.md.")
    }
}
