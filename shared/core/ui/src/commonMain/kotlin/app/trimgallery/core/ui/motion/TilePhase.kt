package app.trimgallery.core.ui.motion

/**
 * A per-tile offset into the breathing cycle, so neighbouring thumbnails never pulse
 * together.
 *
 * Derived from the item's stable id rather than a random number: the spread across tiles
 * is just as arbitrary, but it survives recomposition, scrolling a tile out of view and
 * back, and process death — so the pulse never jumps — and it makes a screenshot test
 * reproducible.
 */
object TilePhase {

    /** Offset in milliseconds, in `0 until MotionSpec.Breathing.PERIOD_MS`. */
    fun offsetMs(id: String): Int {
        // FNV-1a, then a murmur3 finalizer.
        //
        // The finalizer is the point. A plain `hash * 31 + char` gives sequential ids
        // hashes about 31 apart, so `% 4600` maps "item-1" and "item-2" to phases 31 ms
        // apart out of 4600 — mathematically distinct, visually identical, and the whole
        // reason for the offset is that neighbours must not pulse together. The
        // avalanche makes a one-character difference land anywhere in the cycle.
        var hash = FNV_OFFSET_BASIS
        for (index in id.indices) {
            hash = hash xor id[index].code
            hash *= FNV_PRIME
        }
        hash = hash xor (hash ushr 16)
        hash *= MIX_A
        hash = hash xor (hash ushr 15)
        hash *= MIX_B
        hash = hash xor (hash ushr 16)

        // Unsigned before the modulo: Kotlin's `%` keeps the sign of the dividend, which
        // would otherwise produce negative offsets for half of all ids.
        return (hash.toUInt() % MotionSpec.Breathing.PERIOD_MS.toUInt()).toInt()
    }

    /** The same offset as a 0..1 fraction of the cycle. */
    fun fraction(id: String): Float =
        offsetMs(id).toFloat() / MotionSpec.Breathing.PERIOD_MS

    private const val FNV_OFFSET_BASIS = -0x7EE3623B // 0x811C9DC5
    private const val FNV_PRIME = 0x01000193
    private const val MIX_A = 0x7FEB352D
    private const val MIX_B = -0x7B935975 // 0x846CA68B
}
