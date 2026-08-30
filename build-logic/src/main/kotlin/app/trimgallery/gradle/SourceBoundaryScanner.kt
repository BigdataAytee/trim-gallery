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
     * @param allowedFileNames the components permitted to make those calls, by file name.
     *   Compared without the extension, because the boundary is about *which component*
     *   does a thing and the same component is `.kt` on Android and `.swift` on iOS —
     *   `SafeReplacerIos` is the only writer whichever language it ends up in.
     * @param rationale why the boundary exists, printed on failure
     */
    data class Rule(
        val id: String,
        val patterns: List<Regex>,
        val allowedFileNames: Set<String>,
        val rationale: String,
        /**
         * Match against the raw source instead of the comment- and string-stripped form.
         *
         * Needed for exactly one thing: an open mode is a string literal, so a rule about
         * *how* a file is opened cannot see it once literals are blanked. Patterns that
         * set this must be specific enough that prose cannot trip them.
         */
        val rawSource: Boolean = false,
        /**
         * Restrict the rule to files whose path contains this segment.
         *
         * The other rules are about *which file* may do a thing, so a file-name allow-list
         * expresses them. This one is about *which source set*: a JVM import is perfectly
         * correct in `jvmMain` and fatal in `commonMain`, and the difference is the
         * directory, not the name.
         */
        val pathContains: String? = null,
        /**
         * The file extensions this rule claims to police.
         *
         * Declared rather than inferred so that `GuardSelfTest` can insist on a planted
         * violation *per language*. A rule that says it covers Swift and has never been run
         * against any is not a guard, it is a comment — which is exactly what the iOS
         * patterns were until milestone 15, when the harness first globbed `.swift`.
         */
        val languages: Set<String> = setOf("kt", "swift"),
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
            Regex("""DocumentsContract\s*\.\s*(rename|move|delete|create|copy)Document"""),
            // Any receiver, not just a property literally called `contentResolver`:
            // writing `private val resolver: ContentResolver` and calling through that
            // would otherwise walk straight past this guard.
            Regex("""\.\s*openOutputStream\s*\("""),
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

    /**
     * Opening a user's file with any write mode.
     *
     * The safe-replace skill lists this among the things that must never appear:
     * originals are read-only until the single atomic replace, so a `"w"`, `"rw"` or
     * `"wa"` on a content URI is the invariant being broken directly rather than by
     * accident. Its own rule because the mode is a string literal, which the stripped
     * source deliberately cannot see.
     */
    val NO_WRITE_MODE_OPEN = Rule(
        id = "readOnlyOriginals",
        patterns = listOf(
            Regex("""open(FileDescriptor|AssetFileDescriptor|InputStream)\s*\([^)]*"(?:rw|wa|wt|w)"""),
        ),
        allowedFileNames = setOf(
            "SafeReplacerAndroid.kt",
            "SafeReplacerIos.kt",
            "UndoBinAndroid.kt",
            "UndoBinIos.kt",
        ),
        rationale = "Originals are opened read-only (ARCHITECTURE.md § 2.2). A write mode on a " +
            "user's file is the safe-replace invariant broken outright — write to " +
            "LibraryStorage.tempFile() and let Replacer commit it.",
        rawSource = true,
        // Kotlin only. The open modes this matches are Android's SAF strings; on iOS an
        // "original" is a PhotoKit asset rather than a path, and writing to one goes
        // through a change request — which REPLACER_ONLY already covers, in Swift.
        languages = setOf("kt"),
    )

    /**
     * Platform types in shared common code (milestone 15, the iOS port).
     *
     * ARCHITECTURE.md § 3 says the shared modules depend on no platform, and until this
     * milestone that was true only because nobody had broken it — the shared layer has only
     * ever been compiled for the JVM, so a stray `java.util` import would have gone on
     * passing every test and CI run right up until the day someone tried to build for
     * Kotlin/Native and found the port blocked by a hundred small things.
     *
     * This turns "we checked by hand" into something the build enforces on every commit,
     * which is the only form in which the claim survives contact with a year of changes.
     *
     * Deliberately *not* on the list:
     *
     * - `kotlin.jvm.JvmInline` and friends. The `kotlin.jvm` annotations are part of common
     *   Kotlin and `MediaRef` is a `@JvmInline value class` on every target.
     * - `Dispatchers.IO`. It has been available on Native since coroutines 1.9, so banning
     *   it would be enforcing a fact that stopped being true. This codebase injects the
     *   dispatcher anyway, for testability rather than portability.
     */
    val PORTABLE_COMMON = Rule(
        id = "portableCommon",
        patterns = listOf(
            Regex("""^\s*import\s+java\."""),
            Regex("""^\s*import\s+javax\."""),
            Regex("""^\s*import\s+android\."""),
            // androidx is not one thing. `androidx.compose.*` is Compose Multiplatform and
            // compiles for Kotlin/Native — `shared/core/ui` is full of it, correctly.
            // `androidx.work`, `androidx.datastore` and `androidx.media3` are Android only.
            // The first version of this rule banned the lot and flagged 196 correct lines,
            // which is the useful kind of false positive: a guard nobody can satisfy gets
            // switched off, and then it is guarding nothing.
            Regex("""^\s*import\s+androidx\.(?!compose\.)"""),
            // Kotlin/Native's Apple interop packages: correct in iosMain, wrong in common.
            Regex("""^\s*import\s+platform\."""),
            Regex("""\bSystem\s*\.\s*(currentTimeMillis|nanoTime|getProperty|getenv)\b"""),
        ),
        allowedFileNames = emptySet(),
        pathContains = "commonMain",
        // Kotlin only, by construction: commonMain is a Kotlin source set.
        languages = setOf("kt"),
        rationale = "Shared code must compile for Kotlin/Native as well as the JVM " +
            "(ARCHITECTURE.md § 3). A platform type in commonMain blocks the iOS port and " +
            "will not be noticed by any JVM test — put it behind an engine-api interface, " +
            "or in the platform source set that actually needs it.",
    )

    val DEFAULT_RULES =
        listOf(CODEC_FACTORY_ONLY, REPLACER_ONLY, NO_NETWORK_API, NO_WRITE_MODE_OPEN, PORTABLE_COMMON)


    /**
     * Blanks out comments and string literals so a rule name mentioned in a doc comment
     * or an error message is not itself a violation. Replaces with spaces rather than
     * deleting, to keep line numbers and columns intact.
     *
     * Written as a character scan rather than a regex. The regex version — a lazy
     * block-comment pattern with DOT_MATCHES_ALL, applied to the whole file at once —
     * worked for two milestones and then threw `StackOverflowError` on a 2,000-line
     * generated Kotlin file. Java's regex engine recurses while backtracking, so that
     * failure scales with file size rather than with anything about the content, and a
     * build guard that crashes on a large file is worse than one that is slow: it fails
     * the build for a reason that has nothing to do with the boundary it is checking.
     */
    internal fun strip(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        var inBlockComment = false
        var inLineComment = false
        var inString = false
        var inRawString = false

        while (i < source.length) {
            val c = source[i]
            val two = if (i + 1 < source.length) source.substring(i, i + 2) else ""
            val three = if (i + 2 < source.length) source.substring(i, i + 3) else ""

            when {
                c == '\n' -> {
                    inLineComment = false
                    // A string literal cannot span a line; a raw one can.
                    inString = false
                    out.append(c)
                    i++
                }

                inBlockComment -> {
                    if (two == "*/") { inBlockComment = false; out.append("  "); i += 2 } else { out.append(' '); i++ }
                }

                inLineComment -> { out.append(' '); i++ }

                inRawString -> {
                    if (three == "\"\"\"") { inRawString = false; out.append("   "); i += 3 } else { out.append(' '); i++ }
                }

                inString -> {
                    // Skip an escaped character whole, so a \" does not end the literal.
                    if (c == '\\' && i + 1 < source.length) { out.append("  "); i += 2 } else if (c == '"') {
                        inString = false; out.append(' '); i++
                    } else { out.append(' '); i++ }
                }

                three == "\"\"\"" -> { inRawString = true; out.append("   "); i += 3 }
                two == "/*" -> { inBlockComment = true; out.append("  "); i += 2 }
                two == "//" -> { inLineComment = true; out.append("  "); i += 2 }
                c == '"' -> { inString = true; out.append(' '); i++ }

                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /** Scans one file against every rule that applies to it. */
    fun scan(file: File, rules: List<Rule> = DEFAULT_RULES): List<Violation> {
        if (!file.isFile) return emptyList()
        val path = file.invariantSeparatorsPath
        val component = componentName(file.name)
        val applicable = rules
            .filterNot { rule -> rule.allowedFileNames.any { componentName(it) == component } }
            .filter { it.pathContains == null || path.contains(it.pathContains) }
        if (applicable.isEmpty()) return emptyList()

        val raw = file.readText()
        val strippedLines = strip(raw).lines()
        val rawLines = raw.lines()

        val violations = mutableListOf<Violation>()
        applicable.forEach { rule ->
            val lines = if (rule.rawSource) rawLines else strippedLines
            lines.forEachIndexed { index, line ->
                if (rule.patterns.any { it.containsMatchIn(line) }) {
                    violations += Violation(rule, file, index + 1, line)
                }
            }
        }
        return violations.sortedWith(compareBy({ it.line }, { it.rule.id }))
    }

    /**
     * A file name with its language stripped.
     *
     * The allow-lists were written when every implementation was Kotlin, so they say
     * `SafeReplacerIos.kt` — and milestone 15 wrote that component in Swift, where PhotoKit
     * lives. Comparing without the extension is what stops the guard flagging the one file
     * it was written to permit, and it is the right comparison anyway: the boundary is about
     * the component, not the compiler.
     */
    internal fun componentName(fileName: String): String =
        fileName.substringBeforeLast('.', fileName)

    fun scanAll(files: Iterable<File>, rules: List<Rule> = DEFAULT_RULES): List<Violation> =
        files.flatMap { scan(it, rules) }

    /** The failure message, written for whoever has to fix it. */
    fun failureMessage(violations: List<Violation>): String = buildString {
        appendLine("Architectural boundary violated.")
        appendLine()
        violations.groupBy { it.rule }.forEach { (rule, found) ->
            appendLine("  ${rule.id}: ${rule.rationale}")
            if (rule.allowedFileNames.isEmpty()) {
                appendLine("  Allowed nowhere" + (rule.pathContains?.let { " under $it" } ?: "") + ".")
            } else {
                appendLine("  Allowed only in: ${rule.allowedFileNames.sorted().joinToString(", ")}")
            }
            found.forEach { appendLine("    - $it") }
            appendLine()
        }
        appendLine("If a new file genuinely belongs inside one of these boundaries, add it to the")
        appendLine("rule's allow-list in SourceBoundaryScanner and record why in PROJECT.md.")
    }
}
