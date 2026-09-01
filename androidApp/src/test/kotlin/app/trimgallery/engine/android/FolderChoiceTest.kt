package app.trimgallery.engine.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rules Android applies to `ACTION_OPEN_DOCUMENT_TREE`, asserted rather than assumed.
 *
 * Worth testing because the interesting cases are the ones that must **not** be refused:
 * `Download/Holiday` is the workaround the help sheet offers, and a classifier that
 * matched on "starts with Download" would refuse the very folder it is telling the user
 * to pick.
 */
class FolderChoiceTest {

    // Document ids, not URIs: `Uri` is a framework class and returns null for everything
    // in a plain JVM test, which is why the rule is split out to take the id.

    @Test
    fun refusesTheRootOfInternalStorage() {
        assertEquals(FolderChoice.Refusal.STORAGE_ROOT, FolderChoice.refusalForDocumentId("primary:"))
    }

    @Test
    fun refusesTheRootOfARemovableVolume() {
        assertEquals(FolderChoice.Refusal.REMOVABLE_ROOT, FolderChoice.refusalForDocumentId("1A2B-3C4D:"))
    }

    @Test
    fun refusesDownloadsItself() {
        assertEquals(FolderChoice.Refusal.DOWNLOADS, FolderChoice.refusalForDocumentId("primary:Download"))
    }

    @Test
    fun allowsAFolderInsideDownloads() {
        // The workaround the sheet suggests. If this ever fails, the advice is a lie.
        assertNull(FolderChoice.refusalForDocumentId("primary:Download/Holiday"))
    }

    @Test
    fun allowsTheCameraFolder() {
        assertNull(FolderChoice.refusalForDocumentId("primary:DCIM/Camera"))
    }

    @Test
    fun allowsAFolderMerelyStartingWithTheWordDownload() {
        assertNull(FolderChoice.refusalForDocumentId("primary:Downloads from Anna"))
    }

    @Test
    fun readsTheVolumeOutOfADocumentId() {
        assertEquals("primary", FolderChoice.volumeOfDocumentId("primary:DCIM/Camera"))
        assertEquals("1A2B-3C4D", FolderChoice.volumeOfDocumentId("1A2B-3C4D:Backup"))
    }

    @Test
    fun twoFoldersOnOneDriveShareAVolume() {
        // What decides whether offload has anywhere to go. If these ever came back
        // different, the app would offer to move originals to the same disk they are on
        // and free nothing at all.
        assertEquals(
            FolderChoice.volumeOfDocumentId("primary:DCIM/Camera"),
            FolderChoice.volumeOfDocumentId("primary:Pictures/Screenshots"),
        )
    }

    @Test
    fun reportsNoVolumeForAnIdWithoutOne() {
        // Rather than "": two unreadable ids must not look like two drives.
        assertNull(FolderChoice.volumeOfDocumentId("DCIM"))
        assertNull(FolderChoice.volumeOfDocumentId(""))
    }
}
