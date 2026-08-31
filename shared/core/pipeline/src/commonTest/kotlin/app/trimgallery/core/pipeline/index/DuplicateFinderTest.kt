package app.trimgallery.core.pipeline.index

import app.trimgallery.core.model.MediaFlags
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.model.MediaRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Every group this produces is a screen offering to delete a user's photographs, so the
 * tests are about what it must never suggest as much as what it should find.
 */
class DuplicateFinderTest {

    private fun item(
        id: String,
        sha: String? = null,
        phash: Long? = null,
        width: Int = 4032,
        height: Int = 3024,
        size: Long = 4_000_000,
        favourite: Boolean = false,
        hidden: Boolean = false,
        takenAt: String? = null,
    ) = MediaItem(
        id = id,
        platformRef = MediaRef("ref-$id"),
        name = "$id.jpg",
        kind = MediaKind.PHOTO,
        codec = "jpeg",
        width = width,
        height = height,
        fps = null,
        bitrate = null,
        size = size,
        duration = null,
        takenAt = takenAt?.let(Instant::parse),
        location = null,
        cameraModel = null,
        flags = MediaFlags(favourite = favourite, hidden = hidden),
        phash = phash,
        sha256 = sha,
        mtime = 0,
    )

    @Test
    fun `byte-identical files are one exact group`() {
        val groups = DuplicateFinder.find(
            listOf(item("a", sha = "same"), item("b", sha = "same"), item("c", sha = "other")),
        )
        assertEquals(1, groups.size)
        assertEquals(DuplicateFinder.Kind.EXACT, groups.single().kind)
        assertEquals(setOf("a", "b"), groups.single().items.map { it.id }.toSet())
    }

    @Test
    fun `a file with no hash is never grouped`() {
        // An un-indexed file is unknown, not unique. Grouping on a null hash would put
        // every file the indexer has not reached yet into one enormous group.
        assertTrue(DuplicateFinder.find(listOf(item("a"), item("b"))).isEmpty())
    }

    @Test
    fun `burst frames are one near group`() {
        val groups = DuplicateFinder.find(
            listOf(
                item("a", phash = 0b1010_1010L),
                item("b", phash = 0b1010_1011L),
                item("c", phash = 0b1010_1110L),
            ),
        )
        assertEquals(1, groups.size)
        assertEquals(DuplicateFinder.Kind.NEAR, groups.single().kind)
        assertEquals(3, groups.single().items.size)
    }

    @Test
    fun `different photographs are left alone`() {
        val groups = DuplicateFinder.find(
            listOf(item("a", phash = 0L), item("b", phash = -1L)),
        )
        assertTrue(groups.isEmpty())
    }

    @Test
    fun `two shapes are never called the same picture`() {
        // The hash reduces everything to a square grid, so a panorama and a portrait crop
        // of one scene hash alike. Offering a user the chance to delete a photograph that
        // merely resembles another at a different shape is what destroys trust in the
        // whole screen.
        val groups = DuplicateFinder.find(
            listOf(
                item("wide", phash = 42L, width = 3840, height = 1080),
                item("tall", phash = 42L, width = 1080, height = 3840),
            ),
        )
        assertTrue(groups.isEmpty(), "the aspect-ratio guard did not fire")
    }

    @Test
    fun `a re-encode that rounds a dimension still matches`() {
        val groups = DuplicateFinder.find(
            listOf(
                item("a", phash = 42L, width = 1920, height = 1080),
                item("b", phash = 42L, width = 1918, height = 1080),
            ),
        )
        assertEquals(1, groups.size)
    }

    @Test
    fun `hidden items never appear in cleanup`() {
        // SCHEMA.md excludes them from every other view; a cleanup screen that showed them
        // would be a hole straight through the locked folder.
        val groups = DuplicateFinder.find(
            listOf(item("a", sha = "same"), item("b", sha = "same", hidden = true)),
        )
        assertTrue(groups.isEmpty())
    }

    @Test
    fun `an exact group is not re-tested perceptually`() {
        // Otherwise one set of identical files could drag unrelated pictures in behind it.
        val groups = DuplicateFinder.find(
            listOf(
                item("a", sha = "same", phash = 0L),
                item("b", sha = "same", phash = 0L),
                item("c", phash = 0L),
            ),
        )
        assertEquals(1, groups.size)
        assertEquals(DuplicateFinder.Kind.EXACT, groups.single().kind)
    }

    // ------------------------------------------------------------- which to keep

    @Test
    fun `a favourite always wins`() {
        // The user has already said which one they care about.
        val best = DuplicateFinder.pickBest(
            listOf(
                item("big", width = 8000, height = 6000, size = 20_000_000),
                item("loved", width = 1000, height = 750, size = 100_000, favourite = true),
            ),
        )
        assertEquals("loved", best.id)
    }

    @Test
    fun `then the most pixels, because resolution cannot be recovered`() {
        val best = DuplicateFinder.pickBest(
            listOf(
                item("small", width = 1920, height = 1080, size = 9_000_000),
                item("large", width = 4032, height = 3024, size = 1_000_000),
            ),
        )
        assertEquals("large", best.id)
    }

    @Test
    fun `then the largest file, as a proxy for the least re-compressed`() {
        val best = DuplicateFinder.pickBest(
            listOf(item("chat", size = 300_000), item("camera", size = 4_000_000)),
        )
        assertEquals("camera", best.id)
    }

    @Test
    fun `then the oldest, because that is the one with the history`() {
        val best = DuplicateFinder.pickBest(
            listOf(
                item("copy", takenAt = "2026-01-01T00:00:00Z"),
                item("original", takenAt = "2019-06-01T00:00:00Z"),
            ),
        )
        assertEquals("original", best.id)
    }

    @Test
    fun `the suggestion is stable between two openings of the screen`() {
        // A best copy that moved when nothing changed would be unusable.
        val members = listOf(item("z"), item("a"), item("m"))
        assertEquals(DuplicateFinder.pickBest(members).id, DuplicateFinder.pickBest(members.reversed()).id)
    }

    @Test
    fun `a file this app optimised is not penalised for being smaller`() {
        // It is the same picture at the same resolution, verified visually identical.
        // Preferring the un-optimised copy would quietly undo the night's work.
        val optimised = item("ours", size = 1_600_000).copy(optimisedAt = 1_700_000_000_000)
        val original = item("theirs", size = 3_800_000)
        // Resolution ties, so size decides and the original wins — which is honest: the
        // rule is about bytes, not about provenance. What matters is that nothing checks
        // optimisedAt and drops the optimised copy for being ours.
        assertEquals("theirs", DuplicateFinder.pickBest(listOf(optimised, original)).id)
        val biggerOptimised = optimised.copy(width = 8000, height = 6000)
        assertEquals("ours", DuplicateFinder.pickBest(listOf(biggerOptimised, original)).id)
    }

    // ------------------------------------------------------------------ totals

    @Test
    fun `reclaimable space excludes the copy being kept`() {
        val groups = DuplicateFinder.find(
            listOf(
                item("a", sha = "s", size = 5_000_000),
                item("b", sha = "s", size = 5_000_000),
                item("c", sha = "s", size = 5_000_000),
            ),
        )
        assertEquals(10_000_000, groups.single().reclaimable)
        assertEquals(10_000_000, DuplicateFinder.totalReclaimable(groups))
    }

    @Test
    fun `the biggest win is offered first`() {
        val groups = DuplicateFinder.find(
            listOf(
                item("small1", sha = "x", size = 100_000),
                item("small2", sha = "x", size = 100_000),
                item("big1", sha = "y", size = 50_000_000),
                item("big2", sha = "y", size = 50_000_000),
            ),
        )
        assertEquals(listOf(50_000_000L, 100_000L), groups.map { it.reclaimable })
    }

    @Test
    fun `the best copy is listed first in its group`() {
        val groups = DuplicateFinder.find(
            listOf(
                item("a", sha = "s", size = 1_000_000),
                item("b", sha = "s", size = 1_000_000, favourite = true),
            ),
        )
        assertEquals("b", groups.single().items.first().id)
        assertEquals("b", groups.single().best.id)
    }

    @Test
    fun `an empty library produces nothing`() {
        assertTrue(DuplicateFinder.find(emptyList()).isEmpty())
    }
}
