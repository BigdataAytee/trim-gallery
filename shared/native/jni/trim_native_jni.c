/*
 * The Android binding for trim_native.
 *
 * One bridge for the whole C ABI rather than one per library: the ABI is small, and a
 * single JNI_OnLoad keeps registration in one place where a missing method fails at
 * library load rather than at first call, halfway through a night pass.
 *
 * Rules this file exists to enforce (see the `ndk-build` skill):
 *  - Direct ByteBuffers only. Frames are megabytes; GetByteArrayElements would copy them.
 *  - No JNI in hot loops. A whole window crosses once and is scored natively.
 *  - RegisterNatives in JNI_OnLoad, not name mangling: survives obfuscation and fails
 *    loudly at load time.
 *  - Return codes, never exceptions from C. The Kotlin wrapper turns a negative code into
 *    an exception; a metric failure marks the item FAILED and the night pass continues.
 */

#include <jni.h>
#include <stddef.h>

#include "../trim_native.h"

/** Reads a direct ByteBuffer, or NULL if the caller passed a heap buffer by mistake. */
static const uint8_t *direct(JNIEnv *env, jobject buffer)
{
    return buffer ? (const uint8_t *)(*env)->GetDirectBufferAddress(env, buffer) : NULL;
}

/**
 * Builds a window from the Kotlin side's plane buffers.
 *
 * Returns 0 when any plane is missing or not direct, which the callers turn into
 * TRIM_ERR_INVALID_ARG — a heap buffer here would silently score whatever memory
 * happened to be at address zero.
 */
static int fill_window(JNIEnv *env, trim_yuv_window *w,
                       jobject y, jobject u, jobject v,
                       jint y_stride, jint u_stride, jint v_stride,
                       jint width, jint height, jint frames)
{
    w->y = direct(env, y);
    w->u = direct(env, u);
    w->v = direct(env, v);
    if (!w->y || !w->u || !w->v) {
        return 0;
    }
    w->y_stride = y_stride;
    w->u_stride = u_stride;
    w->v_stride = v_stride;
    w->width = width;
    w->height = height;
    w->frame_count = frames;
    return 1;
}

/** The cancel flag is a one-element direct buffer the Kotlin side flips from any thread. */
static volatile const int32_t *cancel_flag(JNIEnv *env, jobject buffer)
{
    return (volatile const int32_t *)direct(env, buffer);
}

static jdouble result_or(JNIEnv *env, jdoubleArray out, double value, int32_t rc)
{
    if (rc == TRIM_OK && out) {
        (*env)->SetDoubleArrayRegion(env, out, 0, 1, &value);
    }
    return (jdouble)rc;
}

static jint nativeXpsnr(JNIEnv *env, jclass clazz,
                        jobject ry, jobject ru, jobject rv, jint rys, jint rus, jint rvs,
                        jobject dy, jobject du, jobject dv, jint dys, jint dus, jint dvs,
                        jint width, jint height, jint frames, jint frameRate,
                        jobject cancel, jdoubleArray out)
{
    (void)clazz;
    trim_yuv_window ref, dist;
    if (!fill_window(env, &ref, ry, ru, rv, rys, rus, rvs, width, height, frames) ||
        !fill_window(env, &dist, dy, du, dv, dys, dus, dvs, width, height, frames)) {
        return TRIM_ERR_INVALID_ARG;
    }
    double score = 0.0;
    const int32_t rc = xpsnr_score(&ref, &dist, frameRate, cancel_flag(env, cancel), &score);
    return (jint)result_or(env, out, score, rc);
}

static jint nativeVmaf(JNIEnv *env, jclass clazz,
                       jobject ry, jobject ru, jobject rv, jint rys, jint rus, jint rvs,
                       jobject dy, jobject du, jobject dv, jint dys, jint dus, jint dvs,
                       jint width, jint height, jint frames, jint subsample,
                       jobject cancel, jdoubleArray out)
{
    (void)clazz;
    trim_yuv_window ref, dist;
    if (!fill_window(env, &ref, ry, ru, rv, rys, rus, rvs, width, height, frames) ||
        !fill_window(env, &dist, dy, du, dv, dys, dus, dvs, width, height, frames)) {
        return TRIM_ERR_INVALID_ARG;
    }
    double score = 0.0;
    const int32_t rc = vmaf_score(&ref, &dist, subsample, cancel_flag(env, cancel), &score);
    return (jint)result_or(env, out, score, rc);
}

/* ------------------------------------------------------------- the photo path */

/**
 * Fills a trim_image from a direct RGBA buffer.
 *
 * Returns 0 for a heap buffer, which the callers turn into TRIM_ERR_INVALID_ARG: a
 * megapixel still is megabytes, and GetByteArrayElements would copy every one of them
 * twice per probe.
 */
static int fill_image(JNIEnv *env, trim_image *image, jobject rgba,
                      jint stride, jint width, jint height)
{
    image->rgba = direct(env, rgba);
    if (!image->rgba) {
        return 0;
    }
    image->stride = stride;
    image->width = width;
    image->height = height;
    return 1;
}

static jint nativeSsim2(JNIEnv *env, jclass clazz,
                        jobject refRgba, jint refStride,
                        jobject distRgba, jint distStride,
                        jint width, jint height,
                        jobject cancel, jdoubleArray out)
{
    (void)clazz;
    trim_image ref, dist;
    if (!fill_image(env, &ref, refRgba, refStride, width, height) ||
        !fill_image(env, &dist, distRgba, distStride, width, height)) {
        return TRIM_ERR_INVALID_ARG;
    }

    double score = 0.0;
    const int32_t rc = ssim2_score(&ref, &dist, cancel_flag(env, cancel), &score);
    return (jint)result_or(env, out, score, rc);
}

/**
 * The shape every byte-in/byte-out codec shares.
 *
 * Kotlin allocates the destination after asking for the size, so each of these is called
 * twice: once with `out == NULL` to size it, once to fill it. `outLen` is a one-element
 * int array carrying the answer back, which keeps the ABI's two-call convention intact
 * across JNI without inventing a handle type.
 */
static jint transcode(JNIEnv *env, jobject src, jint srcLen, jobject out, jintArray outLen,
                      int32_t (*fn)(const uint8_t *, size_t, uint8_t *, size_t *))
{
    const uint8_t *in = direct(env, src);
    if (!in || srcLen <= 0 || !outLen) {
        return TRIM_ERR_INVALID_ARG;
    }

    jint capacity = 0;
    (*env)->GetIntArrayRegion(env, outLen, 0, 1, &capacity);

    uint8_t *dst = out ? (uint8_t *)(*env)->GetDirectBufferAddress(env, out) : NULL;
    if (out && !dst) {
        return TRIM_ERR_INVALID_ARG;
    }

    size_t len = (size_t)capacity;
    const int32_t rc = fn(in, (size_t)srcLen, dst, &len);

    /* Written even on failure: a short buffer is told how much it needed. */
    const jint written = (jint)len;
    (*env)->SetIntArrayRegion(env, outLen, 0, 1, &written);
    return (jint)rc;
}

static jint nativeJpegli(JNIEnv *env, jclass clazz, jobject src, jint srcLen, jint quality,
                         jobject out, jintArray outLen)
{
    (void)clazz;
    const uint8_t *in = direct(env, src);
    if (!in || srcLen <= 0 || !outLen || quality < 1 || quality > 100) {
        return TRIM_ERR_INVALID_ARG;
    }

    /*
     * Written out rather than routed through `transcode`: jpegli_encode takes a quality the
     * other two do not, and the alternatives were a mutable global or a trampoline. Fifteen
     * duplicated lines are cheaper than either, and a JNI bridge is the last place to put
     * state that two threads could reach.
     */
    jint capacity = 0;
    (*env)->GetIntArrayRegion(env, outLen, 0, 1, &capacity);

    uint8_t *dst = out ? (uint8_t *)(*env)->GetDirectBufferAddress(env, out) : NULL;
    if (out && !dst) {
        return TRIM_ERR_INVALID_ARG;
    }

    size_t len = (size_t)capacity;
    const int32_t rc = jpegli_encode(in, (size_t)srcLen, quality, dst, &len);

    const jint written = (jint)len;
    (*env)->SetIntArrayRegion(env, outLen, 0, 1, &written);
    return (jint)rc;
}

static jint nativeJxlRecompress(JNIEnv *env, jclass clazz, jobject src, jint srcLen,
                                jobject out, jintArray outLen)
{
    (void)clazz;
    return transcode(env, src, srcLen, out, outLen, jxl_recompress);
}

static jint nativePngOptimise(JNIEnv *env, jclass clazz, jobject src, jint srcLen,
                              jobject out, jintArray outLen)
{
    (void)clazz;
    return transcode(env, src, srcLen, out, outLen, png_optimise);
}

#define WINDOW_ARGS "Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;III"

static const JNINativeMethod METHODS[] = {
    {
        "nativeXpsnr",
        "(" WINDOW_ARGS WINDOW_ARGS "IIIILjava/nio/ByteBuffer;[D)I",
        (void *)nativeXpsnr,
    },
    {
        "nativeVmaf",
        "(" WINDOW_ARGS WINDOW_ARGS "IIIILjava/nio/ByteBuffer;[D)I",
        (void *)nativeVmaf,
    },
    {
        "nativeSsim2",
        "(Ljava/nio/ByteBuffer;ILjava/nio/ByteBuffer;IIILjava/nio/ByteBuffer;[D)I",
        (void *)nativeSsim2,
    },
    {
        "nativeJpegli",
        "(Ljava/nio/ByteBuffer;IILjava/nio/ByteBuffer;[I)I",
        (void *)nativeJpegli,
    },
    {
        "nativeJxlRecompress",
        "(Ljava/nio/ByteBuffer;ILjava/nio/ByteBuffer;[I)I",
        (void *)nativeJxlRecompress,
    },
    {
        "nativePngOptimise",
        "(Ljava/nio/ByteBuffer;ILjava/nio/ByteBuffer;[I)I",
        (void *)nativePngOptimise,
    },
};

/** Must match the Kotlin object that declares the external functions. */
static const char *const OWNER = "app/trimgallery/engine/android/TrimNative";

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved)
{
    (void)reserved;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    jclass owner = (*env)->FindClass(env, OWNER);
    if (!owner) {
        return JNI_ERR;
    }
    if ((*env)->RegisterNatives(env, owner, METHODS,
                                (jint)(sizeof(METHODS) / sizeof(METHODS[0]))) != JNI_OK) {
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}
