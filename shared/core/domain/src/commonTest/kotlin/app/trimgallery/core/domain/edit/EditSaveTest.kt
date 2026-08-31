package app.trimgallery.core.domain.edit

import app.trimgallery.core.domain.billing.Tier
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.model.MediaRef
import app.trimgallery.core.model.MediaStatus
import app.trimgallery.core.model.SkipReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditSaveTest {

    private val keyframes = (0..15).map { it * 2_000L }

    private fun video(
        durationMs: Long? = 30_000,
        optimisedAt: Long? = null,
        status: MediaStatus = MediaStatus.DONE,
        skipReason: SkipReason? = null,
    ) = MediaItem(
        id = "m1",
        platformRef = MediaRef("content://x"),
        name = "clip.mp4",
        kind = MediaKind.VIDEO,
        codec = "hevc",
        width = 3840,
        height = 2160,
        fps = 30.0,
        bitrate = 20_000_000,
        size = 200L * 1024 * 1024,
        duration = durationMs,
        takenAt = null,
        location = null,
        cameraModel = null,
        phash = 1234L,
        sha256 = "abc",
        status = status,
        skipReason = skipReason,
        mtime = 1_000,
        estSaving = 50L * 1024 * 1024,
        optimisedAt = optimisedAt,
    )

    private fun photo() = video().copy(kind = MediaKind.PHOTO, duration = null, name = "shot.jpg")

    // ------------------------------------------------------------ planning

    @Test
    fun `an edit the user undid is not a save`() {
        assertEquals(EditRender.Plan.Nothing, EditRender.plan(EditRecipe.NONE, photo()))
    }

    /**
     * The most common edit in any gallery, and the one it would be most careless to
     * re-encode for: EXIF, HEIF and MP4 all carry an orientation.
     */
    @Test
    fun `rotating a photograph writes a tag, not a file`() {
        val recipe = EditRecipe.NONE.copy(orientation = Orientation.ROTATE_90)
        val plan = assertIs<EditRender.Plan.MetadataOnly>(EditRender.plan(recipe, photo()))
        assertEquals(Orientation.ROTATE_90, plan.orientation)
        assertFalse(EditRender.usesEncoder(plan))
        assertTrue(EditRender.isLossless(plan))
    }

    @Test
    fun `rotating a video also writes a tag`() {
        val recipe = EditRecipe.NONE.copy(orientation = Orientation.ROTATE_270)
        assertIs<EditRender.Plan.MetadataOnly>(EditRender.plan(recipe, video(), keyframes))
    }

    @Test
    fun `a keyframe-aligned trim cuts the container`() {
        val recipe = EditRecipe.NONE.copy(trim = VideoTrim(4_000, 12_000))
        val plan = assertIs<EditRender.Plan.StreamCopy>(EditRender.plan(recipe, video(), keyframes))
        assertEquals(4_000, plan.trim.startMs)
        assertTrue(EditRender.isLossless(plan))
        assertFalse(EditRender.usesEncoder(plan))
    }

    @Test
    fun `a trim off a keyframe offers the lossless alternative`() {
        val recipe = EditRecipe.NONE.copy(trim = VideoTrim(5_000, 12_000))
        val plan = assertIs<EditRender.Plan.Reencode>(EditRender.plan(recipe, video(), keyframes))
        assertEquals(4_000, plan.losslessAlternative?.startMs)
        assertFalse(EditRender.isLossless(plan))
    }

    /** Anything that changes the picture needs the pixels, keyframes or not. */
    @Test
    fun `a trim combined with a crop is a re-encode`() {
        val recipe = EditRecipe(
            crop = CropGeometry.Rect(0.1, 0.1, 0.9, 0.9),
            trim = VideoTrim(4_000, 12_000),
        )
        val plan = assertIs<EditRender.Plan.Reencode>(EditRender.plan(recipe, video(), keyframes))
        assertNull(plan.losslessAlternative)
    }

    @Test
    fun `a rotation combined with a trim is a re-encode`() {
        val recipe = EditRecipe.NONE.copy(
            orientation = Orientation.ROTATE_90,
            trim = VideoTrim(4_000, 12_000),
        )
        assertIs<EditRender.Plan.Reencode>(EditRender.plan(recipe, video(), keyframes))
    }

    @Test
    fun `adjusting a photograph is a re-encode`() {
        val recipe = EditRecipe.NONE.copy(filter = Filter.NOIR)
        val plan = assertIs<EditRender.Plan.Reencode>(EditRender.plan(recipe, photo()))
        assertNull(plan.trim)
        assertTrue(EditRender.usesEncoder(plan))
    }

    @Test
    fun `a trim on a clip of unknown duration cannot be a container cut`() {
        val recipe = EditRecipe.NONE.copy(trim = VideoTrim(4_000, 12_000))
        assertIs<EditRender.Plan.Reencode>(EditRender.plan(recipe, video(durationMs = null), keyframes))
    }

    // --------------------------------------------------------------- gates

    /**
     * The two gates that are right for the optimiser and wrong for an edit. Refusing to save
     * a crop because it grew, or scoring it against an original it is meant to differ from,
     * would fail every successful edit.
     */
    @Test
    fun `an edit is not required to be smaller or to match the original`() {
        for (destination in EditSave.Destination.entries) {
            val gates = EditSave.gatesFor(destination)
            assertFalse(gates.requireSmaller, "$destination")
            assertFalse(gates.requireQualityScore, "$destination")
        }
    }

    /** Dropping these alongside the quality gate is how an edit replaces a file with a stub. */
    @Test
    fun `an edit is still required to open and to run its full length`() {
        for (destination in EditSave.Destination.entries) {
            val gates = EditSave.gatesFor(destination)
            assertTrue(gates.requireOpenable, "$destination")
            assertTrue(gates.requireExpectedDuration, "$destination")
        }
    }

    @Test
    fun `only a replacement re-checks the original's snapshot`() {
        assertTrue(EditSave.gatesFor(EditSave.Destination.OVER_ORIGINAL).requireUnchangedOriginal)
        assertFalse(EditSave.gatesFor(EditSave.Destination.NEW_COPY).requireUnchangedOriginal)
    }

    /** A new copy is not a lesser write for being a new file (ARCHITECTURE.md § 14). */
    @Test
    fun `both destinations write through the Replacer`() {
        for (destination in EditSave.Destination.entries) {
            assertTrue(destination.throughReplacer, "$destination")
        }
        assertTrue(EditSave.Destination.OVER_ORIGINAL.parksOriginal)
        assertFalse(EditSave.Destination.NEW_COPY.parksOriginal)
    }

    /** MONETIZATION.md lists the editor in the row ticked for both tiers. */
    @Test
    fun `editing is free`() {
        for (tier in Tier.entries) assertTrue(EditSave.isAllowed(tier), "$tier")
    }

    // ---------------------------------------------------------- index reset

    /**
     * A rotation keeps its meaning — same faces, same words, same labels, because every
     * detector works in the upright frame — but not its hash, which is built on a grid of
     * pixels that a turn permutes.
     */
    @Test
    fun `a rotation invalidates the hash and nothing else`() {
        val invalidation = EditSave.invalidatedBy(EditRender.Plan.MetadataOnly(Orientation.ROTATE_90))
        assertTrue(invalidation.hashes)
        assertFalse(invalidation.labels)
        assertFalse(invalidation.faces)
        assertFalse(invalidation.text)
    }

    @Test
    fun `a crop or a trim invalidates everything`() {
        for (plan in listOf(EditRender.Plan.Reencode(), EditRender.Plan.StreamCopy(VideoTrim(0, 1_000)))) {
            val invalidation = EditSave.invalidatedBy(plan)
            assertEquals(EditSave.Invalidation.EVERYTHING, invalidation, "$plan")
        }
    }

    @Test
    fun `saving nothing invalidates nothing`() {
        assertFalse(EditSave.invalidatedBy(EditRender.Plan.Nothing).any)
    }

    // ------------------------------------------------------- the saved row

    @Test
    fun `a saved edit goes back in the indexing queue`() {
        val saved = EditSave.afterSaveOver(video(), EditRender.Plan.Reencode(), newSize = 90_000_000, nowMs = 9_000)
        assertEquals(MediaStatus.NEW, saved.status)
        assertNull(saved.phash)
        assertNull(saved.sha256)
        assertEquals(90_000_000, saved.size)
        assertEquals(9_000, saved.mtime)
    }

    /** A 4K clip cropped to a quarter of its pixels is not the file triage skipped. */
    @Test
    fun `a saved edit forgets why it used to be skipped`() {
        val skipped = video(status = MediaStatus.SKIPPED, skipReason = SkipReason.WOULD_NOT_SHRINK)
        val saved = EditSave.afterSaveOver(skipped, EditRender.Plan.Reencode(), 90_000_000, 9_000)
        assertNull(saved.skipReason)
        assertNull(saved.estSaving)
    }

    /**
     * The generational-loss rule from the other side. One encode from the editor is one
     * generation; letting the night pass add a second is what makes it visible.
     */
    @Test
    fun `a re-encoded edit is not offered to the night pass again`() {
        val saved = EditSave.afterSaveOver(video(), EditRender.Plan.Reencode(), 90_000_000, 9_000)
        assertEquals(9_000, saved.optimisedAt)
    }

    /**
     * And the other way: a rotate or a keyframe cut moves the original bytes, so the file is
     * exactly as un-optimised as it was. Marking it would cost the user the saving.
     */
    @Test
    fun `a lossless edit leaves the optimisation state alone`() {
        for (plan in listOf(
            EditRender.Plan.MetadataOnly(Orientation.ROTATE_90),
            EditRender.Plan.StreamCopy(VideoTrim(0, 1_000)),
        )) {
            assertNull(EditSave.afterSaveOver(video(), plan, 90_000_000, 9_000).optimisedAt, "$plan")
            assertEquals(
                5_000L,
                EditSave.afterSaveOver(video(optimisedAt = 5_000), plan, 90_000_000, 9_000).optimisedAt,
                "$plan",
            )
        }
    }

    @Test
    fun `every plan has something honest to say in the sheet`() {
        val plans = listOf(
            EditRender.Plan.Nothing,
            EditRender.Plan.MetadataOnly(Orientation.ROTATE_90),
            EditRender.Plan.StreamCopy(VideoTrim(0, 1_000)),
            EditRender.Plan.Reencode(),
        )
        for (plan in plans) {
            for (destination in EditSave.Destination.entries) {
                assertTrue(EditSave.describe(plan, destination).isNotBlank(), "$plan $destination")
            }
        }
        assertTrue(
            EditSave.describe(EditRender.Plan.StreamCopy(VideoTrim(0, 1)), EditSave.Destination.NEW_COPY)
                .contains("original quality"),
        )
        assertTrue(
            EditSave.describe(EditRender.Plan.Reencode(), EditSave.Destination.OVER_ORIGINAL)
                .contains("Recently deleted"),
        )
    }
}
