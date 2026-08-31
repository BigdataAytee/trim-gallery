package app.trimgallery.core.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SCHEMA.md: *"IDs are TEXT UUIDv7 unless noted."* This checks it really is one. */
class Uuid7Test {

    private val ms = 1_700_000_000_000L

    @Test
    fun `the layout is a valid version 7 variant 1 uuid`() {
        val id = Uuid7(Random(1)).next(ms)

        assertEquals(36, id.length)
        assertEquals(listOf(8, 13, 18, 23), id.indices.filter { id[it] == '-' })
        assertEquals(7, Uuid7.versionOf(id), "version nibble")
        // Variant: the first character of the fourth group must be 8, 9, a or b.
        assertTrue(id[19] in "89ab", "variant nibble was ${id[19]}")
        assertTrue(id.replace("-", "").all { it in "0123456789abcdef" }, id)
    }

    @Test
    fun `the timestamp is the first 48 bits, in milliseconds`() {
        assertEquals(ms, Uuid7.timestampOf(Uuid7(Random(1)).next(ms)))
    }

    @Test
    fun `ids sort in creation order as plain text`() {
        // The reason for v7 over v4: SCHEMA.md's hot indexes are appended to in id order,
        // and lexical sorting has to match time order for that to hold.
        val gen = Uuid7(Random(7))
        val ids = (0..99).map { gen.next(ms + it * 37L) }
        assertEquals(ids.sorted(), ids)
    }

    @Test
    fun `ids minted in the same millisecond stay strictly increasing`() {
        val gen = Uuid7(Random(3))
        val ids = (0..500).map { gen.next(ms) }
        assertEquals(ids.sorted(), ids)
        assertEquals(ids.size, ids.toSet().size, "ids must be unique")
    }

    @Test
    fun `a clock that steps backwards does not produce a smaller id`() {
        // Phones do move their clocks. An id that went backwards would break the ordering
        // every index in SCHEMA.md depends on.
        val gen = Uuid7(Random(5))
        val first = gen.next(ms)
        val second = gen.next(ms - 60_000)
        assertTrue(second > first, "$second should sort after $first")
    }

    @Test
    fun `two generators do not produce the same sequence`() {
        val a = (0..20).map { Uuid7(Random(11)).next(ms) }
        val b = (0..20).map { Uuid7(Random(12)).next(ms) }
        assertTrue(a.toSet().intersect(b.toSet()).isEmpty())
    }
}
