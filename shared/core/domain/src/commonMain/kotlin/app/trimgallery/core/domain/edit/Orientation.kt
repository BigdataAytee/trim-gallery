package app.trimgallery.core.domain.edit

/**
 * The eight ways a picture can sit in its own file (BUILD.md § 9: *"crop, rotate,
 * straighten"*).
 *
 * Four quarter-turns, each optionally mirrored — the dihedral group of the square. Writing
 * it as one closed set of eight rather than as a rotation field plus a flip field is what
 * makes it composable: "rotate right, then mirror, then rotate right" has an answer here,
 * and with two independent fields it has whichever answer the call site happened to
 * compute. That matters because a user taps these buttons in any order they like, dozens of
 * times, and expects the picture to end up where the taps say.
 *
 * This is also the one edit that can be **free**: EXIF, HEIF and MP4 all carry an
 * orientation, so a rotation on its own is a metadata write, not a re-encode. See
 * `EditRender`, which is where that decision is taken.
 */
enum class Orientation(
    /** Quarter-turns clockwise, applied after the mirror. */
    val quarterTurns: Int,
    /** Whether the image is mirrored left-to-right before the turn. */
    val mirrored: Boolean,
) {
    NORMAL(0, false),
    ROTATE_90(1, false),
    ROTATE_180(2, false),
    ROTATE_270(3, false),
    MIRROR(0, true),
    MIRROR_ROTATE_90(1, true),
    MIRROR_ROTATE_180(2, true),
    MIRROR_ROTATE_270(3, true),
    ;

    /** True when the width and height swap — every odd quarter-turn. */
    val swapsDimensions: Boolean get() = quarterTurns % 2 == 1

    val isIdentity: Boolean get() = this == NORMAL

    /**
     * The four buttons in the editor, each expressed as "what the user did, on top of what
     * was already there".
     *
     * Written through [then] rather than by adjusting the fields directly, because that is
     * the only version that is right for a mirrored image. Mirroring reverses the sense of
     * a rotation, so a picture the user has flipped turns the *other* way — and a
     * flip-horizontally that toggled the stored mirror flag would flip the wrong axis after
     * a quarter-turn, because by then the screen's horizontal is the file's vertical. Both
     * properties are asserted in tests rather than left to be believed.
     */
    fun rotatedRight(): Orientation = then(ROTATE_90)

    fun rotatedLeft(): Orientation = then(ROTATE_270)

    fun flippedHorizontally(): Orientation = then(MIRROR)

    fun flippedVertically(): Orientation = then(MIRROR_ROTATE_180)

    /**
     * Applies [other] on top of this one.
     *
     * Mirroring reverses the sense of a rotation, which is the whole reason this cannot be
     * "add the turns and xor the flags": a mirrored image turned right goes left.
     */
    fun then(other: Orientation): Orientation {
        val turns = if (other.mirrored) 4 - quarterTurns else quarterTurns
        return of((turns + other.quarterTurns) % 4, mirrored != other.mirrored)
    }

    /** The orientation that undoes this one. */
    fun inverse(): Orientation = Orientation.entries.first { then(it).isIdentity }

    /** The EXIF `Orientation` tag value (1–8) for this arrangement. */
    val exif: Int get() = EXIF.entries.first { it.value == this }.key

    companion object {
        fun of(quarterTurns: Int, mirrored: Boolean): Orientation =
            entries.first { it.quarterTurns == ((quarterTurns % 4) + 4) % 4 && it.mirrored == mirrored }

        /**
         * Reads the EXIF `Orientation` tag.
         *
         * Anything outside 1–8 becomes [NORMAL]. Cameras write 0 and other nonsense often
         * enough that treating it as an error would mean refusing to open real photographs,
         * and "assume it is the right way up" is what every other viewer does.
         */
        fun fromExif(value: Int): Orientation = EXIF[value] ?: NORMAL

        /**
         * The tag's eight values, in the order the specification defines them.
         *
         * Note 5 and 7: EXIF numbers them as transpose and transverse, which are a mirror
         * *plus* a turn, and getting either backwards rotates a minority of photographs the
         * wrong way — the classic symptom being that only pictures from one phone, held one
         * way, come out upside down.
         */
        private val EXIF = mapOf(
            1 to NORMAL,
            2 to MIRROR,
            3 to ROTATE_180,
            4 to MIRROR_ROTATE_180,
            5 to MIRROR_ROTATE_90,
            6 to ROTATE_90,
            7 to MIRROR_ROTATE_270,
            8 to ROTATE_270,
        )
    }
}
