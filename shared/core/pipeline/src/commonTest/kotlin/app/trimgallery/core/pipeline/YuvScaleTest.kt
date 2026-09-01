package app.trimgallery.core.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals

class YuvScaleTest {

    @Test
    fun `a plane copied at its own size comes back unchanged`() {
        val src = byteArrayOf(1, 2, 3, 4)
        val dst = ByteArray(4)

        YuvScale.plane(src, rowStride = 2, pixelStride = 1, srcW = 2, srcH = 2, dst, 0, dstW = 2, dstH = 2)

        assertEquals(listOf<Byte>(1, 2, 3, 4), dst.toList())
    }

    @Test
    fun `halving averages each two by two box rather than picking a corner`() {
        // Nearest-neighbour would give 1 here. The mean of 1, 3, 5 and 7 is 4, and the
        // difference between those two answers is exactly the aliasing the metrics must not
        // be handed.
        val src = byteArrayOf(
            1,
            3,
            5,
            7,
        )
        val dst = ByteArray(1)

        YuvScale.plane(src, rowStride = 2, pixelStride = 1, srcW = 2, srcH = 2, dst, 0, dstW = 1, dstH = 1)

        assertEquals(4, dst[0].toInt())
    }

    @Test
    fun `row padding is skipped rather than read as picture`() {
        // A decoder plane is padded to hardware alignment. Read as if packed, the padding
        // becomes pixels and the image shears — and it would still produce a score.
        val src = byteArrayOf(
            10,
            20,
            99,
            99,
            30,
            40,
            99,
            99,
        )
        val dst = ByteArray(4)

        YuvScale.plane(src, rowStride = 4, pixelStride = 1, srcW = 2, srcH = 2, dst, 0, dstW = 2, dstH = 2)

        assertEquals(listOf<Byte>(10, 20, 30, 40), dst.toList())
    }

    @Test
    fun `an interleaved chroma plane reads only its own samples`() {
        // NV12: U and V share a buffer, so U is every other byte. Reading it packed would
        // return half U and half V, which is a colour cast the metric would blame on the
        // encoder.
        val src = byteArrayOf(
            10,
            77,
            20,
            77,
            30,
            77,
            40,
            77,
        )
        val dst = ByteArray(4)

        YuvScale.plane(src, rowStride = 4, pixelStride = 2, srcW = 2, srcH = 2, dst, 0, dstW = 2, dstH = 2)

        assertEquals(listOf<Byte>(10, 20, 30, 40), dst.toList())
    }

    @Test
    fun `values above 127 survive the round trip`() {
        // Bytes are signed in Kotlin and luma is not. Without the mask, bright pixels
        // average to something dark — the brighter the picture, the wronger the score.
        val src = byteArrayOf(200.toByte(), 200.toByte(), 200.toByte(), 200.toByte())
        val dst = ByteArray(1)

        YuvScale.plane(src, rowStride = 2, pixelStride = 1, srcW = 2, srcH = 2, dst, 0, dstW = 1, dstH = 1)

        assertEquals(200, dst[0].toInt() and 0xFF)
    }

    @Test
    fun `a truncated plane does not throw`() {
        val src = byteArrayOf(1, 2)
        val dst = ByteArray(4)

        YuvScale.plane(src, rowStride = 2, pixelStride = 1, srcW = 2, srcH = 2, dst, 0, dstW = 2, dstH = 2)

        assertEquals(listOf<Byte>(1, 2, 0, 0), dst.toList())
    }
}
