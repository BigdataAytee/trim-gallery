package app.trimgallery.core.pipeline.index

import app.trimgallery.core.model.FaceEmbedding
import kotlin.math.sqrt

/**
 * Groups face embeddings into people (BUILD.md § 7, USER_JOURNEY.md § 8).
 *
 * > Face detection + local embedding → people/pets clustering. Never leaves device; user
 * > can disable.
 *
 * Shared, like the perceptual hash and for the same reason (ARCHITECTURE.md § 6): the
 * embeddings come from ML Kit on Android and Vision on iOS, but the decision about which
 * faces are the same person has to be one implementation or a library that moves between
 * platforms would come apart into different people.
 *
 * **This is the most sensitive thing the app computes.** Getting it wrong in one direction
 * scatters someone's child across four "people"; in the other it merges two family members
 * into one, which is worse — it puts photographs of one person under another's name. The
 * thresholds below are therefore deliberately conservative: the app would rather show a
 * user two clusters to merge than one cluster to pull apart.
 */
object FaceClustering {

    /**
     * Cosine similarity above which two faces are the same person.
     *
     * Chosen to under-merge. Face embeddings of one person across years, lighting and age
     * typically sit around 0.6–0.8; different people of similar appearance can reach 0.55.
     * At 0.72 the app splits a person into two clusters more often than it merges two
     * people — and merging is the failure the user cannot undo by hand without opening
     * every photograph.
     */
    const val SAME_PERSON = 0.72

    /**
     * Detection quality below which a face is indexed but never used to form a cluster.
     *
     * A blurred profile at the edge of a group shot produces an embedding that sits
     * halfway between everybody, and a cluster seeded from one absorbs strangers. Such a
     * face can still be *assigned* to a cluster formed by good ones — it just cannot start
     * or define one.
     */
    const val MIN_SEED_QUALITY = 0.6f

    /**
     * Faces below which a cluster is not shown as a person.
     *
     * A single sighting is more often a passer-by in the background than someone the user
     * would name, and a People screen full of one-photo strangers is one nobody opens.
     */
    const val MIN_CLUSTER_SIZE = 3

    /** One face, with the quality the detector reported. */
    data class Face(val id: String, val embedding: FaceEmbedding, val quality: Float = 1f)

    /**
     * A group of faces believed to be one person.
     *
     * [centroid] is the running mean of its members, which is what a new face is compared
     * against: comparing against the first member instead would let a cluster drift with
     * whichever face happened to arrive first.
     */
    data class Cluster(val faces: List<Face>, val centroid: FloatArray) {
        val size: Int get() = faces.size

        override fun equals(other: Any?): Boolean =
            this === other || (other is Cluster && faces == other.faces && centroid.contentEquals(other.centroid))

        override fun hashCode(): Int = faces.hashCode() * 31 + centroid.contentHashCode()
    }

    data class Result(
        /** Clusters large enough to show as people. */
        val people: List<Cluster>,
        /**
         * Faces that formed no useful cluster.
         *
         * Kept rather than discarded: a second photograph of the same passer-by next month
         * turns two singletons into a person, and throwing the first away would mean the
         * app never noticed.
         */
        val unassigned: List<Face>,
    )

    /**
     * Greedy single-pass clustering, largest-quality faces first.
     *
     * Not k-means: nobody knows how many people are in a library, and k-means on a wrong k
     * splits or merges without any signal that it has. Not full agglomerative either — that
     * is quadratic in faces and a library can hold a hundred thousand of them. This is one
     * pass, assigning each face to the nearest cluster above the threshold or starting a
     * new one, which is O(faces × clusters) and stops being a problem because the number of
     * clusters is the number of people the user knows.
     *
     * Ordering by quality first is what makes a single pass work: the clearest faces form
     * the clusters, so by the time an ambiguous one is considered there is a well-defined
     * centroid to compare it against.
     */
    fun cluster(faces: List<Face>, threshold: Double = SAME_PERSON, minClusterSize: Int = MIN_CLUSTER_SIZE): Result {
        val ordered = faces.sortedWith(compareByDescending<Face> { it.quality }.thenBy { it.id })
        val clusters = mutableListOf<MutableList<Face>>()
        val centroids = mutableListOf<FloatArray>()

        for (face in ordered) {
            val vector = normalise(face.embedding.vector)

            var bestIndex = -1
            var bestScore = threshold
            centroids.forEachIndexed { index, centroid ->
                val score = cosine(vector, centroid)
                if (score >= bestScore) {
                    bestScore = score
                    bestIndex = index
                }
            }

            if (bestIndex >= 0) {
                clusters[bestIndex] += face
                centroids[bestIndex] = updated(centroids[bestIndex], clusters[bestIndex].size, vector)
            } else if (face.quality >= MIN_SEED_QUALITY) {
                clusters += mutableListOf(face)
                centroids += vector
            }
            // A poor-quality face that matched nothing is left unassigned rather than
            // seeding a cluster it would then pull off course.
        }

        val people = mutableListOf<Cluster>()
        val unassigned = mutableListOf<Face>()

        clusters.forEachIndexed { index, members ->
            if (members.size >= minClusterSize) {
                people += Cluster(members.sortedByDescending { it.quality }, centroids[index])
            } else {
                unassigned += members
            }
        }

        val clustered = clusters.flatten().map { it.id }.toSet()
        unassigned += ordered.filter { it.id !in clustered }

        return Result(
            people = people.sortedByDescending { it.size },
            unassigned = unassigned.sortedBy { it.id },
        )
    }

    /**
     * Merges two clusters the user says are the same person.
     *
     * USER_JOURNEY.md § 8: *"Tap → name it → merges/suggestions."* A merge the user asked
     * for is unconditional — no threshold gets a say, because they can see the faces and
     * the algorithm cannot.
     */
    fun merge(a: Cluster, b: Cluster): Cluster {
        val faces = (a.faces + b.faces).distinctBy { it.id }
        var centroid = FloatArray(a.centroid.size)
        faces.forEachIndexed { index, face ->
            centroid = updated(centroid, index + 1, normalise(face.embedding.vector))
        }
        return Cluster(faces.sortedByDescending { it.quality }, centroid)
    }

    /**
     * Suggests pairs of clusters that may be the same person.
     *
     * The other half of under-merging: the app splits rather than merges, then offers the
     * splits back. The band is between [suggestBelow] and the clustering threshold — close
     * enough to be worth asking about, not close enough to have merged on its own.
     */
    fun mergeSuggestions(
        people: List<Cluster>,
        suggestBelow: Double = SAME_PERSON,
        suggestAbove: Double = SUGGEST_ABOVE,
    ): List<Pair<Int, Int>> {
        val pairs = mutableListOf<Triple<Int, Int, Double>>()
        for (i in people.indices) {
            for (j in i + 1 until people.size) {
                val score = cosine(people[i].centroid, people[j].centroid)
                if (score in suggestAbove..suggestBelow) pairs += Triple(i, j, score)
            }
        }
        return pairs.sortedByDescending { it.third }.map { it.first to it.second }
    }

    /** Cosine similarity of two unit vectors, which is their dot product. */
    fun cosine(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size) { "embeddings of different lengths: ${a.size} and ${b.size}" }
        var dot = 0.0
        for (i in a.indices) dot += a[i].toDouble() * b[i].toDouble()
        return dot.coerceIn(-1.0, 1.0)
    }

    /**
     * Scales a vector to unit length so cosine similarity is a dot product.
     *
     * Done once per face on the way in rather than inside the comparison loop, which runs
     * once per cluster per face.
     */
    fun normalise(vector: FloatArray): FloatArray {
        var sum = 0.0
        for (v in vector) sum += v.toDouble() * v
        val length = sqrt(sum)
        if (length == 0.0) return vector.copyOf()
        return FloatArray(vector.size) { i -> (vector[i] / length).toFloat() }
    }

    /** A running mean, re-normalised so it stays a unit vector. */
    private fun updated(centroid: FloatArray, size: Int, added: FloatArray): FloatArray {
        val next = FloatArray(centroid.size) { i ->
            centroid[i] + (added[i] - centroid[i]) / size
        }
        return normalise(next)
    }

    /** Below this two clusters are different enough not to be worth asking about. */
    const val SUGGEST_ABOVE = 0.6
}
