package app.trimgallery.core.model

/**
 * What an album is, and therefore who maintains it.
 *
 * BUILD.md § 9 lists Albums, Favourites, auto-albums and the locked folder as separate
 * screens; they are one concept with different sources of truth, so they share a type
 * and differ by [AlbumKind].
 */
enum class AlbumKind {
    /** A folder the user granted, or a collection they made. They decide what is in it. */
    USER,

    /** Derived from the index every night (BUILD.md § 7). The user cannot edit it. */
    AUTO,

    /** Items the user marked. Backed by `MediaItem.favourite`. */
    FAVOURITES,

    /** The undo bin (BUILD.md § 6). Entries expire; nothing else here does. */
    RECENTLY_DELETED,

    /** Behind a biometric prompt, and excluded from every other view. */
    LOCKED,
}

/**
 * The auto-albums BUILD.md § 7 asks for.
 *
 * Each is a rule over the index rather than a stored membership list, so an album is
 * always consistent with what the indexer last saw and costs nothing to keep in step.
 */
enum class AutoAlbum(val displayName: String) {
    SCREENSHOTS("Screenshots"),
    SELFIES("Selfies"),
    DOCUMENTS("Documents"),
    VIDEOS("Videos"),
    CHAT_MEDIA("Chat media"),
    ;
}

data class Album(
    val id: Long,
    val name: String,
    val kind: AlbumKind,
    /** Set for [AlbumKind.AUTO]; identifies which rule produced it. */
    val auto: AutoAlbum? = null,
    val coverMediaId: Long? = null,
    val count: Int = 0,
)
