package app.trimgallery.core.pipeline.index

import app.trimgallery.core.model.MediaFlags
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.model.MediaRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The best ratio of space freed to risk taken in the whole app — and the place a wrong
 * suggestion stings most, because a photograph someone sent you may be the only copy.
 */
class ChatMediaReviewTest {

    private val now = Instant.parse("2026-08-30T12:00:00Z")

    private fun item(
        id: String,
        takenAt: String,
        size: Long = 20_000_000,
        favourite: Boolean = false,
        hidden: Boolean = false,
    ) = MediaItem(
        id = id,
        platformRef = MediaRef("ref-$id"),
        name = "$id.mp4",
        kind = MediaKind.VIDEO,
        codec = "avc1",
        width = 1280,
        height = 720,
        fps = 30.0,
        bitrate = 4_000_000,
        size = size,
        duration = 30_000,
        takenAt = Instant.parse(takenAt),
        location = null,
        cameraModel = null,
        flags = MediaFlags(favourite = favourite, hidden = hidden),
        phash = null,
        sha256 = null,
        mtime = 0,
    )

    @Test
    fun `chat folders are recognised by path, because nothing in the file says so`() {
        assertEquals("WhatsApp", ChatMediaReview.appFor("/storage/emulated/0/WhatsApp/Media/Video/x.mp4"))
        assertEquals("Telegram", ChatMediaReview.appFor("/sdcard/Telegram/Telegram Video/y.mp4"))
        assertNull(ChatMediaReview.appFor("/storage/emulated/0/DCIM/Camera/IMG.jpg"))
        assertNull(ChatMediaReview.appFor(null))
    }

    @Test
    fun `old unopened media is offered, newest is not`() {
        val buckets = ChatMediaReview.review(
            items = listOf(
                item("ancient", "2024-01-01T00:00:00Z"),
                item("old", "2026-02-01T00:00:00Z"),
                item("recent", "2026-08-20T00:00:00Z"),
            ),
            paths = mapOf(
                "ancient" to "/sdcard/WhatsApp/Media/a.mp4",
                "old" to "/sdcard/WhatsApp/Media/b.mp4",
                "recent" to "/sdcard/WhatsApp/Media/c.mp4",
            ),
            opened = emptySet(),
            now = now,
        )

        val offered = buckets.flatMap { it.items }.map { it.id }.toSet()
        assertEquals(setOf("ancient", "old"), offered)
    }

    @Test
    fun `media the user has opened is never offered`() {
        // "Not opened" is the strongest reason to suggest deleting something; the moment
        // they have looked at it, it is theirs.
        val buckets = ChatMediaReview.review(
            listOf(item("seen", "2020-01-01T00:00:00Z")),
            mapOf("seen" to "/sdcard/WhatsApp/Media/a.mp4"),
            opened = setOf("seen"),
            now = now,
        )
        assertTrue(buckets.isEmpty())
    }

    @Test
    fun `a favourite is never offered, and neither is anything hidden`() {
        val buckets = ChatMediaReview.review(
            listOf(
                item("loved", "2020-01-01T00:00:00Z", favourite = true),
                item("locked", "2020-01-01T00:00:00Z", hidden = true),
            ),
            mapOf("loved" to "/sdcard/WhatsApp/a.mp4", "locked" to "/sdcard/WhatsApp/b.mp4"),
            opened = emptySet(),
            now = now,
        )
        assertTrue(buckets.isEmpty())
    }

    @Test
    fun `camera photos are not chat media`() {
        val buckets = ChatMediaReview.review(
            listOf(item("photo", "2018-01-01T00:00:00Z")),
            mapOf("photo" to "/storage/emulated/0/DCIM/Camera/IMG_0001.jpg"),
            opened = emptySet(),
            now = now,
        )
        assertTrue(buckets.isEmpty())
    }

    @Test
    fun `buckets separate by app and by age`() {
        val buckets = ChatMediaReview.review(
            listOf(
                item("w-old", "2024-01-01T00:00:00Z"),
                item("w-recent", "2026-02-01T00:00:00Z"),
                item("t-old", "2024-01-01T00:00:00Z"),
            ),
            mapOf(
                "w-old" to "/sdcard/WhatsApp/a.mp4",
                "w-recent" to "/sdcard/WhatsApp/b.mp4",
                "t-old" to "/sdcard/Telegram/c.mp4",
            ),
            opened = emptySet(),
            now = now,
        )
        assertEquals(3, buckets.size)
        assertEquals(setOf("WhatsApp", "Telegram"), buckets.map { it.app }.toSet())
    }

    @Test
    fun `the biggest win leads, and so does the biggest file inside it`() {
        val buckets = ChatMediaReview.review(
            listOf(
                item("small", "2020-01-01T00:00:00Z", size = 1_000_000),
                item("huge", "2020-01-01T00:00:00Z", size = 900_000_000),
            ),
            mapOf("small" to "/sdcard/WhatsApp/a.mp4", "huge" to "/sdcard/WhatsApp/b.mp4"),
            opened = emptySet(),
            now = now,
        )
        assertEquals("huge", buckets.first().items.first().id)
        assertEquals(901_000_000, ChatMediaReview.totalReclaimable(buckets))
    }

    @Test
    fun `an item with no known path is left alone`() {
        // A SAF grant may have no filesystem path at all. Unknown is not "chat media".
        val buckets = ChatMediaReview.review(
            listOf(item("a", "2020-01-01T00:00:00Z")),
            paths = emptyMap(),
            opened = emptySet(),
            now = now,
        )
        assertTrue(buckets.isEmpty())
    }
}
