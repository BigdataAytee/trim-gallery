package app.trimgallery.core.model

import kotlin.time.Instant

/** Where a parked original went (ARCHITECTURE.md § 4, § 6; SCHEMA.md `undo_entry`). */
enum class UndoLocation { BIN, OFFLOAD, SYSTEM_TRASH }

enum class UndoState { ACTIVE, RESTORED, EXPIRED, OFFLOADED }

/**
 * The record that makes an optimisation reversible.
 *
 * Compression here is visually lossless, not lossless (PROJECT.md § Quality and
 * reversibility): the original *is* the undo. This row must exist before the user is
 * told any space was freed — `ReplaceSequence` writes it as the last step of the
 * ARCHITECTURE.md § 7 contract for exactly that reason.
 *
 * SCHEMA.md forbids ever dropping a column from this table: restore depends on them.
 */
data class UndoEntry(
    val id: String,
    val mediaId: String,
    /** The job that parked it, so History can show what the swap actually bought. */
    val jobId: String? = null,
    val location: UndoLocation,
    val ref: MediaRef,
    /**
     * The original's size.
     *
     * Held here rather than looked up because the whole point of the row is to survive
     * the original being moved off the volume it was measured on — and because "you get
     * back 380 MB" is what the restore sheet has to say (USER_JOURNEY.md § 5).
     */
    val originalSize: Long? = null,
    val expiresAt: Instant?,
    val state: UndoState = UndoState.ACTIVE,
    val createdAt: Instant? = null,
)

/** BUILD.md § 6, per granted folder; SCHEMA.md `folder_grant`. */
enum class FolderMode { KEEP, OFFLOAD, FREE }

/**
 * One folder the user granted, and what should happen to originals inside it.
 *
 * [offloadRef] is the SD or USB tree originals move to under [FolderMode.OFFLOAD]. It is
 * a second grant, not a path: the destination volume is written to, so it needs its own
 * persisted permission, and offload is refused rather than guessed if it is missing.
 */
data class FolderGrant(
    val id: String = "",
    val platformRef: MediaRef,
    val mode: FolderMode,
    val displayName: String? = null,
    val offloadRef: MediaRef? = null,
    val enabled: Boolean = true,
    val lastScannedAt: Long? = null,
)
