package app.trimgallery.feature.settings

import app.trimgallery.core.model.FolderMode

/**
 * One granted folder, as this screen needs it.
 *
 * The platform owns which folders are granted; this carries the identity and what the
 * user chose to do with the originals inside it.
 */
data class FolderRow(
    /** The tree URI. The stable identity of a grant, and the key its settings hang off. */
    val ref: String,
    val displayName: String,
    val mode: FolderMode,
    /**
     * The name of the drive originals would move to under OFFLOAD, or null when there is
     * nowhere to move them.
     *
     * A second granted tree, not a path: the destination volume is written to, so it needs
     * its own persisted permission (safe-replace skill). Without one, OFFLOAD is refused
     * rather than guessed — and with one, the row says *where*, because "move originals to
     * another drive" is not a choice anybody can make without knowing which drive.
     */
    val offloadTarget: String? = null,
)
