package app.trimgallery.engine.android

import java.nio.ByteBuffer

/**
 * The raw binding to `libtrim_native.so`.
 *
 * Declares exactly the C ABI and nothing more — no policy, no allocation, no error
 * translation. `JNI_OnLoad` registers these by name (see
 * `shared/native/jni/trim_native_jni.c`), so a mismatch between this object and the
 * bridge fails when the library loads rather than at first call, halfway through a
 * night pass.
 *
 * Every buffer must be direct. A heap `ByteBuffer` has no address the native side can
 * read, and the bridge rejects it rather than scoring whatever is at address zero.
 */
internal object TrimNative {

    init {
        System.loadLibrary("trim_native")
    }

    /** Mirrors the return codes in `trim_native.h`. */
    const val OK = 0
    const val ERR_INVALID_ARG = -1
    const val ERR_UNSUPPORTED = -2
    const val ERR_CANCELLED = -3
    const val ERR_INTERNAL = -4

    @Suppress("LongParameterList")
    external fun nativeXpsnr(
        refY: ByteBuffer, refU: ByteBuffer, refV: ByteBuffer,
        refYStride: Int, refUStride: Int, refVStride: Int,
        distY: ByteBuffer, distU: ByteBuffer, distV: ByteBuffer,
        distYStride: Int, distUStride: Int, distVStride: Int,
        width: Int, height: Int, frames: Int, frameRate: Int,
        cancel: ByteBuffer, out: DoubleArray,
    ): Int

    @Suppress("LongParameterList")
    external fun nativeVmaf(
        refY: ByteBuffer, refU: ByteBuffer, refV: ByteBuffer,
        refYStride: Int, refUStride: Int, refVStride: Int,
        distY: ByteBuffer, distU: ByteBuffer, distV: ByteBuffer,
        distYStride: Int, distUStride: Int, distVStride: Int,
        width: Int, height: Int, frames: Int, subsample: Int,
        cancel: ByteBuffer, out: DoubleArray,
    ): Int
}
