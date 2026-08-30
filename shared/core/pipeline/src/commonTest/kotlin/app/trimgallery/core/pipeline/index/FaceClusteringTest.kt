package app.trimgallery.core.pipeline.index

import app.trimgallery.core.model.BoundingBox
import app.trimgallery.core.model.FaceEmbedding
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The failure that matters is merging two people, because it puts one person's photographs
 * under another's name and the user cannot undo it without opening every picture. These
 * tests are weighted accordingly.
 */
class FaceClusteringTest {

    private val dimensions = 128

    /** A person, as a direction in embedding space. */
    private fun person(seed: Int): FloatArray {
        val random = Random(seed)
        return FaceClustering.normalise(FloatArray(dimensions) { random.nextFloat() * 2f - 1f })
    }

    /**
     * One sighting of [base] — lighting, age, angle.
     *
     * [drift] mixes in a random direction: 0 is the same embedding, 1 is unrelated. Written
     * as an interpolation rather than as per-component noise because a unit vector in 128
     * dimensions has components around 0.09, so "add up to 0.25 to each" produces noise, not
     * a sighting. The first version did exactly that and every test failed with zero
     * clusters — the fixture was wrong, not the clustering.
     */
    private fun sighting(base: FloatArray, drift: Double, seed: Int): FloatArray {
        val random = Random(seed)
        val direction = FaceClustering.normalise(FloatArray(dimensions) { random.nextFloat() * 2f - 1f })
        return FaceClustering.normalise(
            FloatArray(dimensions) { i -> ((1 - drift) * base[i] + drift * direction[i]).toFloat() },
        )
    }

    private var counter = 0

    private fun face(vector: FloatArray, quality: Float = 1f) = FaceClustering.Face(
        id = "face-${counter++}",
        embedding = FaceEmbedding("media", vector, BoundingBox(0f, 0f, 1f, 1f)),
        quality = quality,
    )

    @Test
    fun `sightings of one person become one cluster`() {
        val mum = person(1)
        val faces = (1..8).map { face(sighting(mum, drift = 0.35, seed = it)) }

        val result = FaceClustering.cluster(faces)

        assertEquals(1, result.people.size, "split into ${result.people.size}")
        assertEquals(8, result.people.single().size)
    }

    @Test
    fun `two different people are never merged`() {
        // The failure the user cannot undo by hand.
        val faces = (1..6).map { face(sighting(person(1), 0.4, it)) } +
            (1..6).map { face(sighting(person(2), 0.3, it + 100)) }

        val result = FaceClustering.cluster(faces)

        assertEquals(2, result.people.size, "merged two people into ${result.people.size} cluster(s)")
        result.people.forEach { assertEquals(6, it.size) }
    }

    @Test
    fun `a blurred face cannot start a cluster`() {
        // An embedding halfway between everybody absorbs strangers if it seeds a cluster.
        val blurred = (1..5).map { face(person(it + 50), quality = 0.2f) }
        val result = FaceClustering.cluster(blurred)

        assertTrue(result.people.isEmpty())
        assertEquals(5, result.unassigned.size)
    }

    @Test
    fun `a blurred face still joins a cluster good faces formed`() {
        val dad = person(3)
        val clear = (1..5).map { face(sighting(dad, 0.25, it)) }
        val blurry = face(sighting(dad, 0.3, 99), quality = 0.1f)

        val result = FaceClustering.cluster(clear + blurry)

        assertEquals(1, result.people.size)
        assertEquals(6, result.people.single().size)
    }

    @Test
    fun `a single sighting is not shown as a person`() {
        // A People screen full of one-photo strangers is one nobody opens.
        val faces = (1..6).map { face(sighting(person(4), 0.25, it)) } + face(person(999))
        val result = FaceClustering.cluster(faces)

        assertEquals(1, result.people.size)
        assertEquals(1, result.unassigned.size)
    }

    @Test
    fun `an unassigned face is kept, not discarded`() {
        // A second photograph of the same passer-by next month turns two singletons into a
        // person; throwing the first away means the app never notices.
        val result = FaceClustering.cluster(listOf(face(person(7))))
        assertEquals(1, result.unassigned.size)
    }

    @Test
    fun `the cluster centroid follows its members, not whoever arrived first`() {
        val base = person(11)
        val faces = (1..10).map { face(sighting(base, 0.4, it)) }
        val result = FaceClustering.cluster(faces)

        val centroid = result.people.single().centroid
        // A unit vector, and closer to the mean of its members than to any one of them.
        assertTrue(abs(FaceClustering.cosine(centroid, centroid) - 1.0) < 1e-6)
        val toBase = FaceClustering.cosine(centroid, base)
        val toFirst = FaceClustering.cosine(centroid, FaceClustering.normalise(faces.first().embedding.vector))
        assertTrue(toBase > toFirst, "centroid drifted to the first face: $toBase vs $toFirst")
    }

    @Test
    fun `clustering is deterministic regardless of input order`() {
        // Otherwise the People screen reshuffles itself between two nights for no reason.
        val faces = (1..6).map { face(sighting(person(12), 0.4, it)) } +
            (1..6).map { face(sighting(person(13), 0.3, it + 200)) }

        val a = FaceClustering.cluster(faces).people.map { it.faces.map { f -> f.id }.toSet() }.toSet()
        val b = FaceClustering.cluster(faces.reversed()).people.map { it.faces.map { f -> f.id }.toSet() }.toSet()
        assertEquals(a, b)
    }

    @Test
    fun `the biggest person is offered first`() {
        val faces = (1..12).map { face(sighting(person(14), 0.25, it)) } +
            (1..4).map { face(sighting(person(15), 0.25, it + 300)) }

        val people = FaceClustering.cluster(faces).people
        assertEquals(listOf(12, 4), people.map { it.size })
    }

    // ------------------------------------------------------------ user actions

    @Test
    fun `a merge the user asked for is unconditional`() {
        // They can see the faces; the algorithm cannot.
        val a = FaceClustering.cluster((1..4).map { face(sighting(person(16), 0.2, it)) }).people.single()
        val b = FaceClustering.cluster((1..4).map { face(sighting(person(17), 0.2, it + 400)) }).people.single()

        val merged = FaceClustering.merge(a, b)

        assertEquals(8, merged.size)
        assertTrue(abs(FaceClustering.cosine(merged.centroid, merged.centroid) - 1.0) < 1e-6)
    }

    @Test
    fun `merging is idempotent on overlapping clusters`() {
        val cluster = FaceClustering.cluster((1..5).map { face(sighting(person(18), 0.2, it)) }).people.single()
        assertEquals(5, FaceClustering.merge(cluster, cluster).size)
    }

    @Test
    fun `near misses are offered back as merge suggestions`() {
        // The app under-merges on purpose, then asks. Two clusters just below the
        // threshold are exactly what a user would want joined.
        val base = person(19)
        val one = FaceClustering.cluster((1..4).map { face(sighting(base, 0.15, it)) }).people.single()
        val two = FaceClustering.cluster((1..4).map { face(sighting(base, 0.15, it + 500)) }).people.single()

        val similarity = FaceClustering.cosine(one.centroid, two.centroid)
        val suggestions = FaceClustering.mergeSuggestions(
            listOf(one, two),
            // Pin the band around the pair so the test asserts the mechanism, not the
            // particular numbers this fixture happens to produce.
            suggestBelow = similarity + 0.01,
            suggestAbove = similarity - 0.01,
        )
        assertEquals(listOf(0 to 1), suggestions)
    }

    @Test
    fun `clusters that are nothing alike are not suggested`() {
        val a = FaceClustering.cluster((1..4).map { face(sighting(person(20), 0.2, it)) }).people.single()
        val b = FaceClustering.cluster((1..4).map { face(sighting(person(21), 0.2, it + 600)) }).people.single()
        assertTrue(FaceClustering.mergeSuggestions(listOf(a, b), suggestAbove = 0.9).isEmpty())
    }

    // ------------------------------------------------------------------ maths

    @Test
    fun `normalising produces a unit vector, and a zero vector survives`() {
        val unit = FaceClustering.normalise(floatArrayOf(3f, 4f))
        assertTrue(abs(FaceClustering.cosine(unit, unit) - 1.0) < 1e-6)
        // A detector can emit an all-zero embedding; dividing by its length must not
        // produce NaN and poison every comparison it takes part in.
        val zero = FaceClustering.normalise(FloatArray(4))
        assertTrue(zero.all { it == 0f })
        assertEquals(0.0, FaceClustering.cosine(zero, unit + FloatArray(2)))
    }

    @Test
    fun `cosine is bounded, so a rounding error cannot exceed one`() {
        val v = person(22)
        assertTrue(FaceClustering.cosine(v, v) <= 1.0)
        assertTrue(FaceClustering.cosine(v, FloatArray(dimensions) { -v[it] }) >= -1.0)
    }

    @Test
    fun `an empty library produces no people`() {
        val result = FaceClustering.cluster(emptyList())
        assertTrue(result.people.isEmpty() && result.unassigned.isEmpty())
    }
}
