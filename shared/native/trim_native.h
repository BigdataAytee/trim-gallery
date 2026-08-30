/*
 * ARCHITECTURE.md § 10 — the whole native surface.
 *
 * Bound by JNI on Android and Kotlin/Native cinterop on iOS. Because both bindings read
 * this one header, a signature change breaks them together at compile time.
 *
 * Rules:
 *  - Buffers, never paths. Native code has no idea what a SAF URI or a PHAsset is, and
 *    no business opening a user's file.
 *  - Every buffer carries an explicit length. Nothing is null-terminated.
 *  - Return 0 on success, negative on failure. Never abort(): a metric failure marks the
 *    item FAILED("metric error") and the night pass continues (§ 13).
 *  - Every long call takes `cancel`, polled between windows, so night work can stop on
 *    unplug, thermal, cap or stop-by time.
 */

#ifndef TRIM_NATIVE_H
#define TRIM_NATIVE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define TRIM_OK                 0
#define TRIM_ERR_INVALID_ARG   -1
#define TRIM_ERR_UNSUPPORTED   -2
#define TRIM_ERR_CANCELLED     -3
#define TRIM_ERR_INTERNAL      -4

/** A planar 8-bit YUV 4:2:0 window held in memory. Strides are in bytes. */
typedef struct {
    const uint8_t *y, *u, *v;
    int32_t y_stride, u_stride, v_stride;
    int32_t width, height, frame_count;
} trim_yuv_window;

/** Packed 8-bit RGBA. */
typedef struct {
    const uint8_t *rgba;
    int32_t stride, width, height;
} trim_image;

/*
 * Search metric. 10-20x cheaper than VMAF, so it drives the binary search.
 *
 * `frame_rate` selects the temporal activity term: XPSNR uses a first-order frame
 * difference at or below 32 fps and a second-order one above it, so passing the wrong
 * rate changes the score. Luma only -- see src/xpsnr_score.c.
 */
int32_t xpsnr_score(const trim_yuv_window *reference,
                    const trim_yuv_window *distorted,
                    int32_t frame_rate,
                    volatile const int32_t *cancel,
                    double *out_score);

/* Verification metric. vmaf_v0.6.1, sampled windows only (BUILD.md § 5). */
int32_t vmaf_score(const trim_yuv_window *reference,
                   const trim_yuv_window *distorted,
                   int32_t subsample,
                   volatile const int32_t *cancel,
                   double *out_score);

/* Photo quality gate. */
int32_t ssim2_score(const trim_image *reference,
                    const trim_image *distorted,
                    volatile const int32_t *cancel,
                    double *out_score);

/*
 * Photo codecs. Each writes into `out` and sets `*out_len`; call with out == NULL to
 * ask for the required size first.
 */
int32_t jpegli_encode(const uint8_t *src, size_t src_len, int32_t quality,
                      uint8_t *out, size_t *out_len);

int32_t jxl_recompress(const uint8_t *src, size_t src_len,
                       uint8_t *out, size_t *out_len);

int32_t png_optimise(const uint8_t *src, size_t src_len,
                     uint8_t *out, size_t *out_len);

#ifdef __cplusplus
}
#endif

#endif /* TRIM_NATIVE_H */
