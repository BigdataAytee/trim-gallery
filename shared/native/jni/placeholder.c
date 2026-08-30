/*
 * Keeps the CMake target valid until milestone 2 wires the real libraries in.
 * Every function returns TRIM_ERR_UNSUPPORTED so a caller that reaches native code
 * early fails loudly rather than silently scoring 0.
 */
#include "../trim_native.h"

int32_t xpsnr_score(const trim_yuv_window *reference, const trim_yuv_window *distorted,
                    volatile const int32_t *cancel, double *out_score) {
    (void)reference; (void)distorted; (void)cancel; (void)out_score;
    return TRIM_ERR_UNSUPPORTED;
}

int32_t vmaf_score(const trim_yuv_window *reference, const trim_yuv_window *distorted,
                   int32_t subsample, volatile const int32_t *cancel, double *out_score) {
    (void)reference; (void)distorted; (void)subsample; (void)cancel; (void)out_score;
    return TRIM_ERR_UNSUPPORTED;
}

int32_t ssim2_score(const trim_image *reference, const trim_image *distorted,
                    volatile const int32_t *cancel, double *out_score) {
    (void)reference; (void)distorted; (void)cancel; (void)out_score;
    return TRIM_ERR_UNSUPPORTED;
}

int32_t jpegli_encode(const uint8_t *src, size_t src_len, int32_t quality,
                      uint8_t *out, size_t *out_len) {
    (void)src; (void)src_len; (void)quality; (void)out; (void)out_len;
    return TRIM_ERR_UNSUPPORTED;
}

int32_t jxl_recompress(const uint8_t *src, size_t src_len, uint8_t *out, size_t *out_len) {
    (void)src; (void)src_len; (void)out; (void)out_len;
    return TRIM_ERR_UNSUPPORTED;
}

int32_t png_optimise(const uint8_t *src, size_t src_len, uint8_t *out, size_t *out_len) {
    (void)src; (void)src_len; (void)out; (void)out_len;
    return TRIM_ERR_UNSUPPORTED;
}
