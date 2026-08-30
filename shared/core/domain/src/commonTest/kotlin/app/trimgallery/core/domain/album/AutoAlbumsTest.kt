package app.trimgallery.core.domain.album

import app.trimgallery.core.model.AutoAlbum
import app.trimgallery.core.model.Label
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.model.MediaRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutoAlbumsTest {

    private fun item(
        name: String = "IMG_0001.jpg",
        kind: MediaKind = MediaKind.PHOTO,
        locked: Boolean = false,
    ) = MediaItem(
        id = 1,
        platformRef = MediaRef("ref"),
        name = name,
        kind = kind,
        codec = "jpeg",
        width = 4032,
        height = 3024,
        fps = null,
        bitrate = null,
        size = 3_000_000,
        duration = null,
        takenAt = null,
        location = null,
        cameraModel = null,
        phash = null,
        sha256 = null,
        mtime = 0,
        locked = locked,
    )

    private fun label(text: String, confidence: Float = 0.9f) = Label(1, text, confidence)

    @Test
    fun `videos are filed by kind, not by label`() {
        assertEquals(setOf(AutoAlbum.VIDEOS), AutoAlbums.albumsFor(item(kind = MediaKind.VIDEO)))
    }

    @Test
    fun `screenshots are recognised by filename across OEM conventions`() {
        listOf("Screenshot_20260830.png", "screen_shot 3.png", "Screencap-1.jpg").forEach { name ->
            assertTrue(
                AutoAlbum.SCREENSHOTS in AutoAlbums.albumsFor(item(name = name)),
                "not recognised: $name",
            )
        }
    }

    @Test
    fun `a screenshot label also works when the filename is unhelpful`() {
        val albums = AutoAlbums.albumsFor(item(name = "1234.png"), labels = listOf(label("screenshot")))
        assertTrue(AutoAlbum.SCREENSHOTS in albums)
    }

    @Test
    fun `a screenshot of a receipt is a screenshot, not a document`() {
        // Filing it in both would make Documents useless: the user is looking for the
        // thing they photographed.
        val albums = AutoAlbums.albumsFor(
            item(name = "Screenshot_1.png"),
            labels = listOf(label("receipt")),
        )
        assertEquals(setOf(AutoAlbum.SCREENSHOTS), albums)
    }

    @Test
    fun `a photographed receipt is a document`() {
        val albums = AutoAlbums.albumsFor(item(name = "IMG_9.jpg"), labels = listOf(label("receipt")))
        assertTrue(AutoAlbum.DOCUMENTS in albums)
    }

    @Test
    fun `a low confidence label does not file anything`() {
        val albums = AutoAlbums.albumsFor(
            item(),
            labels = listOf(label("document", confidence = 0.4f)),
        )
        assertTrue(AutoAlbum.DOCUMENTS !in albums, "got $albums")
    }

    @Test
    fun `the front camera is what makes a selfie`() {
        assertTrue(AutoAlbum.SELFIES in AutoAlbums.albumsFor(item(), facingCamera = true))
    }

    @Test
    fun `a portrait of someone else is not a selfie without the front camera`() {
        val albums = AutoAlbums.albumsFor(item(), labels = listOf(label("person")))
        assertTrue(AutoAlbum.SELFIES !in albums, "got $albums")
    }

    @Test
    fun `a video from the front camera is not filed as a selfie`() {
        val albums = AutoAlbums.albumsFor(item(kind = MediaKind.VIDEO), facingCamera = true)
        assertEquals(setOf(AutoAlbum.VIDEOS), albums)
    }

    @Test
    fun `chat media is recognised by folder for every major app`() {
        listOf(
            "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/img.jpg",
            "/sdcard/Telegram/Telegram Images/x.jpg",
            "/sdcard/Signal/Media/y.jpg",
        ).forEach { path ->
            assertTrue(
                AutoAlbum.CHAT_MEDIA in AutoAlbums.albumsFor(item(), path = path),
                "not recognised: $path",
            )
        }
    }

    @Test
    fun `an item can belong to several albums`() {
        val albums = AutoAlbums.albumsFor(
            item(kind = MediaKind.VIDEO),
            path = "/sdcard/WhatsApp/Media/v.mp4",
        )
        assertEquals(setOf(AutoAlbum.VIDEOS, AutoAlbum.CHAT_MEDIA), albums)
    }

    @Test
    fun `a locked item appears in no auto-album at all`() {
        // The locked folder is excluded from every other view, auto-albums included.
        val albums = AutoAlbums.albumsFor(
            item(name = "Screenshot_1.png", kind = MediaKind.VIDEO, locked = true),
            path = "/sdcard/WhatsApp/Media/v.mp4",
        )
        assertEquals(emptySet(), albums)
    }

    @Test
    fun `build drops empty albums and keeps a stable order`() {
        val items = listOf(
            item(kind = MediaKind.VIDEO),
            item(name = "Screenshot_2.png"),
        )
        val built = AutoAlbums.build(items)
        assertEquals(listOf(AutoAlbum.SCREENSHOTS, AutoAlbum.VIDEOS), built.keys.toList())
        assertTrue(built.values.none { it.isEmpty() })
    }
}
