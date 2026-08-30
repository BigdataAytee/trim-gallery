package app.trimgallery.gradle

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourceBoundaryScannerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun kt(name: String, body: String): File =
        File(tmp.root, name).apply { parentFile.mkdirs(); writeText(body) }

    // ----------------------------------------------------------- codec guard

    @Test
    fun `creating a codec outside CodecFactory is a violation`() {
        val file = kt(
            "ThumbnailPipelineAndroid.kt",
            """
            package app.trimgallery
            class ThumbnailPipelineAndroid {
                fun go() { val c = MediaCodec.createEncoderByType("video/hevc") }
            }
            """.trimIndent(),
        )
        val found = SourceBoundaryScanner.scan(file)
        assertEquals(1, found.size)
        assertEquals("codecFactory", found.single().rule.id)
        assertEquals(3, found.single().line)
    }

    @Test
    fun `MediaCodecFactory may create codecs`() {
        val file = kt(
            "MediaCodecFactory.kt",
            """
            class MediaCodecFactory {
                fun make() = MediaCodec.createEncoderByType("video/hevc")
                fun list() = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            }
            """.trimIndent(),
        )
        assertEquals(emptyList<SourceBoundaryScanner.Violation>(), SourceBoundaryScanner.scan(file))
    }

    @Test
    fun `VideoToolbox session creation is caught on the iOS side too`() {
        val file = kt("SomeHelper.swift", "let s = VTCompressionSessionCreate(allocator: nil)")
        assertEquals("codecFactory", SourceBoundaryScanner.scan(file).single().rule.id)
    }

    // -------------------------------------------------------- replacer guard

    @Test
    fun `writing to a granted folder outside Replacer is a violation`() {
        val file = kt(
            "MlKitIndexer.kt",
            """
            class MlKitIndexer {
                fun oops(uri: Uri) { DocumentsContract.renameDocument(resolver, uri, "x.mp4") }
            }
            """.trimIndent(),
        )
        assertEquals("replacer", SourceBoundaryScanner.scan(file).single().rule.id)
    }

    @Test
    fun `SafeReplacerAndroid and the undo bin may write`() {
        listOf("SafeReplacerAndroid.kt", "UndoBinAndroid.kt").forEach { name ->
            val file = kt(name, """fun f(u: Uri) { DocumentsContract.renameDocument(r, u, "x") }""")
            assertEquals("$name should be allowed", emptyList<SourceBoundaryScanner.Violation>(), SourceBoundaryScanner.scan(file))
        }
    }

    @Test
    fun `PhotoKit mutation outside the iOS replacer is a violation`() {
        val file = kt("PhotoKitStorage.kt", "val r = PHAssetChangeRequest()")
        assertEquals("replacer", SourceBoundaryScanner.scan(file).single().rule.id)
    }

    // --------------------------------------------------------- network guard

    @Test
    fun `any networking API is a violation, with no allow-list`() {
        val file = kt("Uploader.kt", "import java.net.HttpURLConnection")
        assertEquals("noNetwork", SourceBoundaryScanner.scan(file).single().rule.id)
    }

    @Test
    fun `naming the INTERNET permission in code is a violation`() {
        val file = kt("Perms.kt", "val p = android.permission.INTERNET")
        assertEquals("noNetwork", SourceBoundaryScanner.scan(file).single().rule.id)
    }

    @Test
    fun `android net Uri is not networking`() {
        val file = kt("Fine.kt", "import android.net.Uri\nfun f(u: Uri) = u.path")
        assertEquals(emptyList<SourceBoundaryScanner.Violation>(), SourceBoundaryScanner.scan(file))
    }

    // ------------------------------------------------------ false positives

    @Test
    fun `a restricted call inside a line comment is not a violation`() {
        val file = kt("Notes.kt", "// never call MediaCodec.createEncoderByType here\nval x = 1")
        assertEquals(emptyList<SourceBoundaryScanner.Violation>(), SourceBoundaryScanner.scan(file))
    }

    @Test
    fun `a restricted call inside a block comment is not a violation`() {
        val file = kt(
            "Notes2.kt",
            "/*\n * MediaCodec.createEncoderByType is banned outside CodecFactory.\n */\nval x = 1",
        )
        assertEquals(emptyList<SourceBoundaryScanner.Violation>(), SourceBoundaryScanner.scan(file))
    }

    @Test
    fun `a restricted call inside a string literal is not a violation`() {
        val file = kt("Msg.kt", """val why = "do not use MediaCodec.createEncoderByType directly"""")
        assertEquals(emptyList<SourceBoundaryScanner.Violation>(), SourceBoundaryScanner.scan(file))
    }

    @Test
    fun `stripping preserves line numbers`() {
        val stripped = SourceBoundaryScanner.strip("/* a\nb */\nval x = MediaCodec.createByCodecName(\"n\")")
        assertEquals(3, stripped.lines().size)
    }

    @Test
    fun `failure message names the rule, the rationale and the file`() {
        val file = kt("Bad.kt", """fun f() = MediaCodec.createEncoderByType("video/hevc")""")
        val message = SourceBoundaryScanner.failureMessage(SourceBoundaryScanner.scan(file))
        assertTrue(message.contains("codecFactory"))
        assertTrue(message.contains("MediaCodecFactory.kt"))
        assertTrue(message.contains("Bad.kt"))
        assertTrue(message.contains("BUILD.md"))
    }

    @Test
    fun `opening a user file with a write mode is a violation`() {
        // The safe-replace invariant broken directly rather than by accident.
        listOf(
            """val fd = resolver.openFileDescriptor(uri, "w")""",
            """val fd = resolver.openFileDescriptor(uri, "rw")""",
            """resolver.openAssetFileDescriptor(uri, "wa")""",
        ).forEach { line ->
            val file = kt("Thumbnailer.kt", line)
            val found = SourceBoundaryScanner.scan(file)
            assertEquals("not caught: $line", 1, found.size)
            assertEquals("readOnlyOriginals", found.single().rule.id)
        }
    }

    @Test
    fun `opening a user file read-only is fine`() {
        val file = kt("Thumbnailer.kt", """val fd = resolver.openFileDescriptor(uri, "r")""")
        assertTrue(SourceBoundaryScanner.scan(file).isEmpty())
    }

    @Test
    fun `the write-mode rule does not fire on prose that mentions the modes`() {
        // It matches raw source, so it has to be specific enough that documentation and
        // the rule's own rationale cannot trip it.
        val file = kt(
            "Notes.kt",
            """
            // Never open an original with "w", "rw" or "wa".
            val modes = listOf("w", "rw", "wa")
            """.trimIndent(),
        )
        assertTrue(SourceBoundaryScanner.scan(file).isEmpty())
    }

    @Test
    fun `a content resolver aliased to another name is still caught`() {
        // SafeReplacerAndroid holds `private val resolver: ContentResolver`; a pattern
        // anchored on the literal name `contentResolver` would have walked past it.
        val file = kt("Indexer.kt", """resolver.openOutputStream(uri, "wt")""")
        val found = SourceBoundaryScanner.scan(file)
        assertTrue(found.toString(), found.any { it.rule.id == "replacer" })
    }

    @Test
    fun `a large file does not blow the stack`() {
        // A regex over the whole file threw StackOverflowError on a 2,000-line generated
        // Kotlin file inside a native submodule. A guard that crashes on a big file fails
        // the build for a reason that has nothing to do with the boundary it checks.
        val body = buildString {
            appendLine("package app.trimgallery")
            repeat(20_000) { i -> appendLine("/* comment $i */ val v$i = \"string $i\"") }
        }
        val file = kt("Generated.kt", body)
        assertTrue(SourceBoundaryScanner.scan(file).isEmpty())
    }

    @Test
    fun `block comments, line comments and strings are all blanked`() {
        val stripped = SourceBoundaryScanner.strip(
            """
            /* MediaCodec.createEncoderByType */
            // MediaCodec.createEncoderByType
            val s = "MediaCodec.createEncoderByType"
            val raw = ${"\"\"\""}MediaCodec.createEncoderByType${"\"\"\""}
            val real = MediaCodec.createEncoderByType(mime)
            """.trimIndent(),
        )
        assertEquals(1, stripped.lines().count { it.contains("createEncoderByType") })
        assertTrue(stripped.lines().last().contains("val real"))
    }

    @Test
    fun `stripping preserves line numbers exactly`() {
        // Violations are reported by line, so a strip that dropped characters would point
        // at the wrong one.
        val source = "/*\n a\n b\n*/\nval x = 1\n"
        assertEquals(source.lines().size, SourceBoundaryScanner.strip(source).lines().size)
        assertEquals(source.length, SourceBoundaryScanner.strip(source).length)
    }

    @Test
    fun `an escaped quote does not end a string early`() {
        // Otherwise the rest of the line reads as code and a rule name inside a message
        // becomes a violation.
        val stripped = SourceBoundaryScanner.strip("""val m = "he said \"MediaCodec.createEncoderByType\"" """)
        assertTrue(stripped, !stripped.contains("createEncoderByType"))
    }
}
