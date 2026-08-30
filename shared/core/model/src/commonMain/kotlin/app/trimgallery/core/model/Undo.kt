package app.trimgallery.core.model

import kotlin.time.Instant

/** Where a parked original went (ARCHITECTURE.md § 4, § 6). */
enum class UndoLocation { BIN, OFFLOAD, SYSTEM_TRASH }

enum class UndoState { ACTIVE, RESTORED, EXPIRED, OFFLOADED }

/**
 * The record that makes an optimisation reversible.
 *
 * Compression here is visually lossless, not lossless (PROJECT.md § Quality and
 * reversibility): the original *is* the undo. This row must exist before the user is
 * told any space was freed.
 */
data class UndoEntry(
    val id: Long,
    val mediaId: Long,
    val location: UndoLocation,
    val ref: MediaRef,
    val expiresAt: Instant?,
    val state: UndoState = UndoState.ACTIVE,
)

/** BUILD.md § 6, per granted folder. */
enum class FolderMode { KEEP, OFFLOAD, FREE }

data class FolderGrant(val platformRef: MediaRef, val mode: FolderMode)
