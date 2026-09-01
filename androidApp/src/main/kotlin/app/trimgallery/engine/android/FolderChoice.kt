package app.trimgallery.engine.android

import android.net.Uri
import android.provider.DocumentsContract

/**
 * What Android will and will not let this app be granted, and how to say so.
 *
 * Since Android 11, `ACTION_OPEN_DOCUMENT_TREE` refuses three locations for **every** app:
 * the root of internal storage, `Download`, and the root of a removable volume. The system
 * picker greys out "Use this folder" there, so the user cannot confirm — they back out, and
 * the app receives exactly what it receives when somebody simply changes their mind:
 * `null`.
 *
 * That is the fact this whole screen has to be designed around. **The app cannot tell a
 * refusal from a cancellation**, so it must never accuse the user of picking the wrong
 * thing. It can do two useful things instead: start the picker somewhere that works, and,
 * when it comes back empty-handed, say which folders Android blocks so the second attempt
 * is informed.
 */
object FolderChoice {

    /** Why a tree cannot be used, or null when it can. */
    enum class Refusal { STORAGE_ROOT, DOWNLOADS, REMOVABLE_ROOT }

    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /** Android's own name for the primary volume in a document id. */
    private const val PRIMARY = "primary"

    /**
     * Classifies a tree the picker returned.
     *
     * Belt and braces: the picker is supposed to make these unpickable, but the check is
     * cheap and the failure it prevents is silent — a grant that is taken, stored, and then
     * scans nothing, which looks like an empty library rather than a refused folder. OEM
     * pickers are not all the same picker.
     *
     * Works on the document id rather than the path, because a tree URI has no path in the
     * filesystem sense: `primary:DCIM/Camera` is the id, `primary:` is the volume root.
     */
    fun refusalFor(tree: Uri): Refusal? {
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
            ?: return null
        return refusalForDocumentId(documentId)
    }

    /**
     * The same rule over the document id alone.
     *
     * Split out so it can be tested: `Uri` is a framework class that returns null for
     * everything in a plain JVM unit test, so a classifier taking a `Uri` can only be
     * checked on a device. The interesting cases here are string cases, and this is where
     * they live.
     */
    fun refusalForDocumentId(documentId: String): Refusal? {
        val volume = documentId.substringBefore(':', missingDelimiterValue = "")
        val relative = documentId.substringAfter(':', missingDelimiterValue = "").trim('/')

        if (relative.isEmpty()) {
            // The root of a volume. Which volume decides the wording, not the outcome.
            return if (volume.equals(PRIMARY, ignoreCase = true)) {
                Refusal.STORAGE_ROOT
            } else {
                Refusal.REMOVABLE_ROOT
            }
        }
        // Only the top-level Download folder is refused; `Download/Holiday` is fine, which
        // is worth getting right because it is the workaround the sheet offers.
        return if (relative.equals(DOWNLOAD_DIR, ignoreCase = true)) Refusal.DOWNLOADS else null
    }

    /**
     * Which storage volume a granted tree is on, or null when the id cannot be read.
     *
     * The volume is the half of a document id before the colon: `primary` for internal
     * storage, a filesystem UUID like `1A2B-3C4D` for an SD card or a USB drive. Two
     * grants with different volumes are on different physical drives, which is the only
     * question offload has to answer — copying an original to another folder on the *same*
     * drive frees nothing at all.
     */
    fun volumeOf(tree: Uri): String? = runCatching { DocumentsContract.getTreeDocumentId(tree) }
        .getOrNull()
        ?.let(::volumeOfDocumentId)

    /**
     * The same rule over the document id alone, testable off a device for the reason
     * [refusalForDocumentId] gives.
     *
     * An id with no colon at all has no volume to report, rather than being reported as a
     * volume of its own: guessing here would make two unreadable ids look like two drives.
     */
    fun volumeOfDocumentId(documentId: String): String? =
        documentId.substringBefore(':', missingDelimiterValue = "").ifEmpty { null }

    /**
     * Where to open the picker, so the common case never meets a blocked folder.
     *
     * `EXTRA_INITIAL_URI` is a hint: a device that has no `DCIM/Camera` opens wherever it
     * likes rather than failing, which is why this is an improvement to the odds and not a
     * guarantee. Camera rather than DCIM itself because that is where the photographs
     * actually are on every phone this app is for.
     */
    fun cameraFolderHint(): Uri = DocumentsContract.buildDocumentUri(
        EXTERNAL_STORAGE_AUTHORITY,
        "$PRIMARY:$CAMERA_DIR",
    )

    const val DOWNLOAD_DIR = "Download"
    const val CAMERA_DIR = "DCIM/Camera"
}
