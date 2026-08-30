package app.trimgallery.core.model

/** An on-device image label, used for search and auto-albums (BUILD.md § 7). */
data class Label(val mediaId: String, val text: String, val confidence: Float)

/** A face embedding. Never leaves the device; the user can turn clustering off. */
data class FaceEmbedding(val mediaId: String, val vector: FloatArray, val box: BoundingBox) {
    // FloatArray has reference equality by default, which would silently break dedupe.
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is FaceEmbedding && mediaId == other.mediaId && box == other.box && vector.contentEquals(other.vector))

    override fun hashCode(): Int =
        (mediaId.hashCode() * PRIME + box.hashCode()) * PRIME + vector.contentHashCode()

    private companion object { const val PRIME = 31 }
}

data class BoundingBox(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** A cluster of faces the user may name. */
data class Person(val id: String, val displayName: String?, val coverMediaId: String?, val faceCount: Int)

/** Text found in a photo by OCR — searchable, and copyable in the viewer. */
data class TextBlock(val mediaId: String, val text: String, val box: BoundingBox)

/** Items that are the same or near enough that the user should pick one (BUILD.md § 8). */
data class DuplicateGroup(val id: String, val mediaIds: List<String>, val exact: Boolean)
