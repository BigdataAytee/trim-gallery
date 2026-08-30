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
