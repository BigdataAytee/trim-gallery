package app.trimgallery.core.model

import kotlin.random.Random

/**
 * Row identifiers.
 *
 * SCHEMA.md: *"IDs are TEXT UUIDv7 unless noted."* Version 7 rather than 4 because the
 * first 48 bits are a millisecond timestamp, so ids sort by creation order. Every index
 * in SCHEMA.md that matters for the night pass — `(status, est_saving DESC)`,
 * `(state, expires_at)`, `(finished_at DESC)` — is appended to in roughly id order, and a
 * random v4 primary key would scatter those B-tree writes across the whole index on a
 * database that lives on phone flash.
 *
 * The generator is deliberately explicit about its clock and randomness so the layout can
 * be asserted on a JVM rather than assumed.
 */
class Uuid7(private val random: Random = Random.Default) {

    private var lastMs = Long.MIN_VALUE
    private var counter = 0

    /**
     * A new id for something created at [nowMs].
     *
     * **Confine to one thread.** ARCHITECTURE.md § 8 puts every database write on the IO
     * dispatcher, and ids are minted where rows are; the counter below is not atomic and
     * does not need to be.
     *
     * Ids minted in the same millisecond stay strictly increasing: RFC 9562's method 1
     * uses the 12-bit `rand_a` field as a counter, seeded randomly so that two devices do
     * not produce the same sequence. If a millisecond ever produces more than 4096 ids the
     * timestamp is carried forward rather than the ordering being broken.
     */
    fun next(nowMs: Long): String {
        var ms = nowMs
        if (ms <= lastMs) {
            // Either the same millisecond, or a clock that stepped backwards — phones do
            // move their clocks. Both are handled the same way: hold the timestamp and
            // count up. Re-seeding the counter here instead would let an id sort *before*
            // one already handed out, which is the one thing v7 is chosen to prevent.
            ms = lastMs
            counter += 1
            if (counter > MAX_COUNTER) {
                ms = lastMs + 1
                counter = random.nextInt(SEED_CEILING)
            }
        } else {
            counter = random.nextInt(SEED_CEILING)
        }
        lastMs = ms

        // 48 bits of timestamp, then version 7, then the 12-bit counter.
        val hi = (ms and TIMESTAMP_MASK shl SHIFT_TO_VERSION) or
            (VERSION_7 shl SHIFT_VERSION) or
            counter.toLong()

        // Variant 0b10 in the top two bits, 62 bits of randomness under it.
        val lo = (random.nextLong() and VARIANT_CLEAR_MASK) or VARIANT_RFC4122

        return format(hi, lo)
    }

    /** `tttttttt-tttt-7aaa-bRRR-RRRRRRRRRRRR` */
    private fun format(hi: Long, lo: Long): String = buildString(UUID_LENGTH) {
        append(hex(hi ushr 32, 8))
        append('-')
        append(hex(hi ushr 16 and 0xFFFF, 4))
        append('-')
        append(hex(hi and 0xFFFF, 4))
        append('-')
        append(hex(lo ushr 48 and 0xFFFF, 4))
        append('-')
        append(hex(lo and 0xFFFF_FFFFFFFF, 12))
    }

    private fun hex(value: Long, digits: Int): String {
        val out = CharArray(digits)
        var v = value
        for (i in digits - 1 downTo 0) {
            out[i] = HEX[(v and 0xF).toInt()]
            v = v ushr 4
        }
        return out.concatToString()
    }

    companion object {
        private const val MAX_COUNTER = 0xFFF
        /** Seeded in the low half so a busy millisecond has room to count up. */
        private const val SEED_CEILING = 0x800
        private const val TIMESTAMP_MASK = 0xFFFF_FFFFFFFFL
        private const val SHIFT_TO_VERSION = 16
        private const val SHIFT_VERSION = 12
        private const val VERSION_7 = 7L
        private const val VARIANT_CLEAR_MASK = 0x3FFF_FFFF_FFFF_FFFFL
        /** Top two bits `10`; `Long.MIN_VALUE` is exactly that bit pattern. */
        private const val VARIANT_RFC4122 = Long.MIN_VALUE
        private const val UUID_LENGTH = 36
        private val HEX = "0123456789abcdef".toCharArray()

        /** The millisecond a v7 id was minted, for tests and for diagnostics. */
        fun timestampOf(id: String): Long = id.replace("-", "").substring(0, 12).toLong(16)

        /** The version nibble; 7 for anything this class made. */
        fun versionOf(id: String): Int = id.replace("-", "")[12].digitToInt(16)
    }
}
