/*
 * The photo path arrives with milestone 7 (jpegli, JPEG XL, oxipng, SSIMULACRA2).
 *
 * These return TRIM_ERR_UNSUPPORTED rather than being absent so the ABI is complete from
 * the start: a caller that reaches them early fails loudly with a code the pipeline
 * already knows how to report, instead of failing to link or silently scoring zero.
 */

#include "../trim_native.h"

int32_t ssim2_score(const trim_image *reference, const trim_image *distorted,
                    volatile const int32_t *cancel, double *out_score)
{
    (void)reference; (void)distorted; (void)cancel; (void)out_score;
    return TRIM_ERR_UNSUPPORTED;
}

int32_t jpegli_encode(const uint8_t *src, size_t src_len, int32_t quality,
                      uint8_t *out, size_t *out_len)
{
    (void)src; (void)src_len; (void)quality; (void)out; (void)out_len;
    return TRIM_ERR_UNSUPPORTED;
}

int32_t jxl_recompress(const uint8_t *src, size_t src_len, uint8_t *out, size_t *out_len)
{
    (void)src; (void)src_len; (void)out; (void)out_len;
    return TRIM_ERR_UNSUPPORTED;
}

int32_t png_optimise(const uint8_t *src, size_t src_len, uint8_t *out, size_t *out_len)
{
    (void)src; (void)src_len; (void)out; (void)out_len;
    return TRIM_ERR_UNSUPPORTED;
}
