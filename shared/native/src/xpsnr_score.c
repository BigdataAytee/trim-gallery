/*
 * xpsnr_score — the search metric.
 *
 * A standalone extraction of the Fraunhofer HHI XPSNR algorithm from the submodule at
 * shared/native/xpsnr (libavfilter/vf_xpsnr.c), with the FFmpeg AVFilter plumbing
 * removed. The arithmetic is upstream's, unchanged; only the frame plumbing, the
 * context and the memory management are ours.
 *
 * Why extracted rather than linked: the upstream repository ships the metric *only* as
 * an FFmpeg filter (its README says the maintained copy now lives in FFmpeg itself), and
 * pulling FFmpeg into the app to reach one function is not a trade worth making. See
 * PROJECT.md.
 *
 * Luma only. The search needs a monotone proxy for coding quality, luma dominates that,
 * and this is the metric run thousands of times a night — scoring chroma would cost
 * roughly half as much again for a number the search does not read. It also means the
 * value matches FFmpeg's per-component "XPSNR y" exactly, which is what it is verified
 * against.
 *
 * Upstream copyright (c) 2019-2024 Fraunhofer-Gesellschaft; see
 * shared/native/xpsnr/libavfilter/vf_xpsnr.c for the full licence. Commercial use is
 * permitted; note that the licence grants no patent rights.
 */

#include "../trim_native.h"

#include <math.h>
#include <stdlib.h>
#include <string.h>

#define XPSNR_GAMMA 2

static int trim_window_valid(const trim_yuv_window *w)
{
    return w && w->y && w->width > 0 && w->height > 0 && w->frame_count > 0;
}

static uint64_t sse_line(const int16_t *org, const int16_t *rec, int width)
{
    uint64_t sse = 0;
    for (int x = 0; x < width; x++) {
        const int64_t error = (int64_t)org[x] - (int64_t)rec[x];
        sse += (uint64_t)(error * error);
    }
    return sse;
}

static uint64_t squared_error(const int16_t *org, uint32_t stride_org,
                              const int16_t *rec, uint32_t stride_rec,
                              uint32_t width, uint32_t height)
{
    uint64_t sse = 0;
    for (uint32_t y = 0; y < height; y++) {
        sse += sse_line(org, rec, (int)width);
        org += stride_org;
        rec += stride_rec;
    }
    return sse;
}

/** Upstream's high-pass with downsampling, used above roughly HD. */
static uint64_t highds(int x_act, int y_act, int w_act, int h_act, const int16_t *o, int O)
{
    uint64_t sa_act = 0;
    for (int y = y_act; y < h_act; y += 2) {
        for (int x = x_act; x < w_act; x += 2) {
            const int f = 12 * ((int)o[y * O + x] + (int)o[y * O + x + 1]
                                + (int)o[(y + 1) * O + x] + (int)o[(y + 1) * O + x + 1])
                - 3 * ((int)o[(y - 1) * O + x] + (int)o[(y - 1) * O + x + 1]
                       + (int)o[(y + 2) * O + x] + (int)o[(y + 2) * O + x + 1])
                - 3 * ((int)o[y * O + x - 1] + (int)o[y * O + x + 2]
                       + (int)o[(y + 1) * O + x - 1] + (int)o[(y + 1) * O + x + 2])
                - 2 * ((int)o[(y - 1) * O + x - 1] + (int)o[(y - 1) * O + x + 2]
                       + (int)o[(y + 2) * O + x - 1] + (int)o[(y + 2) * O + x + 2])
                - ((int)o[(y - 2) * O + x - 1] + (int)o[(y - 2) * O + x] + (int)o[(y - 2) * O + x + 1]
                   + (int)o[(y - 2) * O + x + 2] + (int)o[(y + 3) * O + x - 1] + (int)o[(y + 3) * O + x]
                   + (int)o[(y + 3) * O + x + 1] + (int)o[(y + 3) * O + x + 2]
                   + (int)o[(y - 1) * O + x - 2] + (int)o[y * O + x - 2]
                   + (int)o[(y + 1) * O + x - 2] + (int)o[(y + 2) * O + x - 2]
                   + (int)o[(y - 1) * O + x + 3] + (int)o[y * O + x + 3]
                   + (int)o[(y + 1) * O + x + 3] + (int)o[(y + 2) * O + x + 3]);
            sa_act += (uint64_t)abs(f);
        }
    }
    return sa_act;
}

static uint64_t diff1st(uint32_t w_act, uint32_t h_act, const int16_t *o, int16_t *o_m1, int O)
{
    uint64_t ta_act = 0;
    for (uint32_t y = 0; y < h_act; y += 2) {
        for (uint32_t x = 0; x < w_act; x += 2) {
            const int t = (int)o[y * O + x] + (int)o[y * O + x + 1]
                + (int)o[(y + 1) * O + x] + (int)o[(y + 1) * O + x + 1]
                - ((int)o_m1[y * O + x] + (int)o_m1[y * O + x + 1]
                   + (int)o_m1[(y + 1) * O + x] + (int)o_m1[(y + 1) * O + x + 1]);
            ta_act += (uint64_t)abs(t);
            o_m1[y * O + x] = o[y * O + x];
            o_m1[y * O + x + 1] = o[y * O + x + 1];
            o_m1[(y + 1) * O + x] = o[(y + 1) * O + x];
            o_m1[(y + 1) * O + x + 1] = o[(y + 1) * O + x + 1];
        }
    }
    return XPSNR_GAMMA * ta_act;
}

static uint64_t diff2nd(uint32_t w_act, uint32_t h_act, const int16_t *o,
                        int16_t *o_m1, int16_t *o_m2, int O)
{
    uint64_t ta_act = 0;
    for (uint32_t y = 0; y < h_act; y += 2) {
        for (uint32_t x = 0; x < w_act; x += 2) {
            const int t = (int)o[y * O + x] + (int)o[y * O + x + 1]
                + (int)o[(y + 1) * O + x] + (int)o[(y + 1) * O + x + 1]
                - 2 * ((int)o_m1[y * O + x] + (int)o_m1[y * O + x + 1]
                       + (int)o_m1[(y + 1) * O + x] + (int)o_m1[(y + 1) * O + x + 1])
                + (int)o_m2[y * O + x] + (int)o_m2[y * O + x + 1]
                + (int)o_m2[(y + 1) * O + x] + (int)o_m2[(y + 1) * O + x + 1];
            ta_act += (uint64_t)abs(t);
            o_m2[y * O + x] = o_m1[y * O + x];
            o_m2[y * O + x + 1] = o_m1[y * O + x + 1];
            o_m2[(y + 1) * O + x] = o_m1[(y + 1) * O + x];
            o_m2[(y + 1) * O + x + 1] = o_m1[(y + 1) * O + x + 1];
            o_m1[y * O + x] = o[y * O + x];
            o_m1[y * O + x + 1] = o[y * O + x + 1];
            o_m1[(y + 1) * O + x] = o[(y + 1) * O + x];
            o_m1[(y + 1) * O + x + 1] = o[(y + 1) * O + x + 1];
        }
    }
    return XPSNR_GAMMA * ta_act;
}

/** Upstream's per-frame averaging, unchanged. */
static double avg_xpsnr(double sqrt_wsse, double sum_xpsnr,
                        uint32_t width, uint32_t height,
                        uint64_t max_error, uint64_t frames)
{
    if (frames == 0) {
        return INFINITY;
    }
    if (sqrt_wsse >= (double)frames) { /* square-mean-root distortion averaging */
        const double mean_dist = sqrt_wsse / (double)frames;
        const uint64_t num = (uint64_t)width * (uint64_t)height * max_error;
        return 10.0 * log10((double)num / (mean_dist * mean_dist));
    }
    return sum_xpsnr / (double)frames; /* older log-domain averaging */
}

/** Everything the metric carries between frames. */
typedef struct {
    uint32_t width, height;
    int depth;
    int frame_rate;
    uint64_t max_error;
    uint32_t block, blocks_wide, block_count;
    double avg_act;
    int smooth_weights;
    double *sse_luma, *weights;
    int16_t *org, *org_m1, *org_m2, *rec;
    double sum_w_dist, sum_xpsnr;
    uint64_t frames;
} trim_xpsnr_ctx;

/**
 * Block SSE and its perceptual weight. Upstream's `calcSquaredErrorAndWeight`, with the
 * filter context replaced by the fields it actually read.
 */
static double squared_error_and_weight(const trim_xpsnr_ctx *s,
                                       const int16_t *pic_org, uint32_t stride_org,
                                       int16_t *pic_org_m1, int16_t *pic_org_m2,
                                       const int16_t *pic_rec, uint32_t stride_rec,
                                       uint32_t offset_x, uint32_t offset_y,
                                       uint32_t block_w, uint32_t block_h,
                                       double *ms_act)
{
    const int O = (int)stride_org;
    const int R = (int)stride_rec;
    const int16_t *o = pic_org + offset_y * O + offset_x;
    int16_t *o_m1 = pic_org_m1 + offset_y * O + offset_x;
    int16_t *o_m2 = pic_org_m2 + offset_y * O + offset_x;
    const int16_t *r = pic_rec + offset_y * R + offset_x;
    /* Above roughly HD the high-pass runs on a downsampled grid. */
    const int b_val = (s->width * s->height > 2048 * 1152 ? 2 : 1);
    const int x_act = (offset_x > 0 ? 0 : b_val);
    const int y_act = (offset_y > 0 ? 0 : b_val);
    const int w_act = (offset_x + block_w < s->width ? (int)block_w : (int)block_w - b_val);
    const int h_act = (offset_y + block_h < s->height ? (int)block_h : (int)block_h - b_val);

    const double sse = (double)squared_error(o, stride_org, r, stride_rec, block_w, block_h);
    uint64_t sa_act = 0;
    uint64_t ta_act = 0;

    if (w_act <= x_act || h_act <= y_act) { /* block too small to weight */
        return sse;
    }

    if (b_val > 1) {
        sa_act = highds(x_act, y_act, w_act, h_act, o, O);
    } else {
        for (int y = y_act; y < h_act; y++) {
            for (int x = x_act; x < w_act; x++) {
                const int f = 12 * (int)o[y * O + x]
                    - 2 * ((int)o[y * O + x - 1] + (int)o[y * O + x + 1]
                           + (int)o[(y - 1) * O + x] + (int)o[(y + 1) * O + x])
                    - ((int)o[(y - 1) * O + x - 1] + (int)o[(y - 1) * O + x + 1]
                       + (int)o[(y + 1) * O + x - 1] + (int)o[(y + 1) * O + x + 1]);
                sa_act += (uint64_t)abs(f);
            }
        }
    }

    *ms_act = (double)sa_act / ((double)(w_act - x_act) * (double)(h_act - y_act));

    if (b_val > 1) {
        ta_act = (s->frame_rate <= 32)
            ? diff1st(block_w, block_h, o, o_m1, O)
            : diff2nd(block_w, block_h, o, o_m1, o_m2, O);
    } else if (s->frame_rate <= 32) {
        for (uint32_t y = 0; y < block_h; y++) {
            for (uint32_t x = 0; x < block_w; x++) {
                const int t = (int)o[y * O + x] - (int)o_m1[y * O + x];
                ta_act += XPSNR_GAMMA * (uint64_t)abs(t);
                o_m1[y * O + x] = o[y * O + x];
            }
        }
    } else {
        for (uint32_t y = 0; y < block_h; y++) {
            for (uint32_t x = 0; x < block_w; x++) {
                const int t = (int)o[y * O + x] - 2 * (int)o_m1[y * O + x] + (int)o_m2[y * O + x];
                ta_act += XPSNR_GAMMA * (uint64_t)abs(t);
                o_m2[y * O + x] = o_m1[y * O + x];
                o_m1[y * O + x] = o[y * O + x];
            }
        }
    }

    *ms_act += (double)ta_act / ((double)block_w * (double)block_h);

    /* Lower limit, accounting for high-pass gain. */
    if (*ms_act < (double)(1 << (s->depth - 6))) {
        *ms_act = (double)(1 << (s->depth - 6));
    }
    *ms_act *= *ms_act; /* because the error is squared */

    return sse;
}

#define TRIM_MAX(a, b) (((a) > (b)) ? (a) : (b))

/** Weighted SSE for one luma frame. Upstream's `getWSSE`, luma path only. */
static uint64_t frame_wsse(trim_xpsnr_ctx *s)
{
    const uint32_t W = s->width;
    const uint32_t H = s->height;
    const uint32_t B = s->block;
    double wsse_luma = 0.0;
    uint32_t idx = 0;

    if (B < 4) { /* picture too small for XPSNR: plain SSE, i.e. unweighted PSNR */
        return squared_error(s->org, W, s->rec, W, W, H);
    }

    for (uint32_t y = 0; y < H; y += B) {
        const uint32_t block_h = (y + B > H ? H - y : B);
        for (uint32_t x = 0; x < W; x += B, idx++) {
            const uint32_t block_w = (x + B > W ? W - x : B);
            double ms_act = 1.0;
            double ms_act_prev = 0.0;

            s->sse_luma[idx] = squared_error_and_weight(s, s->org, W, s->org_m1, s->org_m2,
                                                        s->rec, W, x, y, block_w, block_h, &ms_act);
            s->weights[idx] = 1.0 / sqrt(ms_act);

            if (s->smooth_weights) { /* inline minimum-smoothing, as in the paper */
                if (x == 0) {
                    ms_act_prev = (idx > 1 ? s->weights[idx - 2] : 0);
                } else {
                    ms_act_prev = (x > B ? TRIM_MAX(s->weights[idx - 2], s->weights[idx])
                                         : s->weights[idx]);
                }
                if (idx > s->blocks_wide) {
                    ms_act_prev = TRIM_MAX(ms_act_prev, s->weights[idx - 1 - s->blocks_wide]);
                }
                if (idx > 0 && s->weights[idx - 1] > ms_act_prev) {
                    s->weights[idx - 1] = ms_act_prev;
                }
                if (x + B >= W && y + B >= H && idx > s->blocks_wide) {
                    ms_act_prev = TRIM_MAX(s->weights[idx - 1], s->weights[idx - s->blocks_wide]);
                    if (s->weights[idx] > ms_act_prev) {
                        s->weights[idx] = ms_act_prev;
                    }
                }
            }
        }
    }

    for (uint32_t i = 0; i < idx; i++) {
        wsse_luma += s->sse_luma[i] * s->weights[i];
    }
    return wsse_luma <= 0.0 ? 0 : (uint64_t)(wsse_luma * s->avg_act + 0.5);
}

/** Widens an 8-bit plane into the int16 buffer the algorithm works in. */
static void widen(int16_t *dst, const uint8_t *src, int32_t src_stride, uint32_t w, uint32_t h)
{
    for (uint32_t y = 0; y < h; y++) {
        for (uint32_t x = 0; x < w; x++) {
            dst[y * w + x] = (int16_t)src[(size_t)y * src_stride + x];
        }
    }
}

int32_t xpsnr_score(const trim_yuv_window *reference,
                    const trim_yuv_window *distorted,
                    int32_t frame_rate,
                    volatile const int32_t *cancel,
                    double *out_score)
{
    if (!out_score || !trim_window_valid(reference) || !trim_window_valid(distorted)) {
        return TRIM_ERR_INVALID_ARG;
    }
    if (reference->width != distorted->width || reference->height != distorted->height ||
        reference->frame_count != distorted->frame_count || frame_rate <= 0) {
        return TRIM_ERR_INVALID_ARG;
    }

    trim_xpsnr_ctx s;
    memset(&s, 0, sizeof(s));
    s.width = (uint32_t)reference->width;
    s.height = (uint32_t)reference->height;
    s.depth = 8; /* HDR is skipped entirely (BUILD.md § 2.5), so 8-bit is the only case */
    s.frame_rate = frame_rate;
    s.max_error = ((uint64_t)(1 << s.depth) - 1);
    s.max_error *= s.max_error;

    const double ratio = (double)(s.width * s.height) / (3840.0 * 2160.0); /* UHD ratio */
    const int block = 4 * (int)(32.0 * sqrt(ratio) + 0.5); /* multiple of 4, for SIMD */
    s.block = (uint32_t)TRIM_MAX(0, block);
    s.blocks_wide = s.block ? (s.width + s.block - 1) / s.block : 0;
    s.avg_act = sqrt(16.0 * (double)(1 << (2 * s.depth - 9))
                     / sqrt(TRIM_MAX(0.00001, ratio)));
    s.smooth_weights = (s.width * s.height <= 640u * 480u); /* per the XPSNR paper */

    const size_t pixels = (size_t)s.width * s.height;
    const uint32_t blocks_high = s.block ? (s.height + s.block - 1) / s.block : 0;
    s.block_count = s.blocks_wide * blocks_high;

    s.sse_luma = calloc(s.block_count ? s.block_count : 1, sizeof(double));
    s.weights = calloc(s.block_count ? s.block_count : 1, sizeof(double));
    s.org = calloc(pixels, sizeof(int16_t));
    s.org_m1 = calloc(pixels, sizeof(int16_t));
    s.org_m2 = calloc(pixels, sizeof(int16_t));
    s.rec = calloc(pixels, sizeof(int16_t));

    int32_t status = TRIM_ERR_INTERNAL;
    if (!s.sse_luma || !s.weights || !s.org || !s.org_m1 || !s.org_m2 || !s.rec) {
        goto done;
    }

    for (int32_t i = 0; i < reference->frame_count; i++) {
        /* Polled between frames so night work stops promptly on unplug or thermal. */
        if (cancel && *cancel) {
            status = TRIM_ERR_CANCELLED;
            goto done;
        }

        widen(s.org, reference->y + (size_t)i * reference->y_stride * s.height,
              reference->y_stride, s.width, s.height);
        widen(s.rec, distorted->y + (size_t)i * distorted->y_stride * s.height,
              distorted->y_stride, s.width, s.height);

        const double sqrt_wsse = sqrt((double)frame_wsse(&s));
        const double frame_score = avg_xpsnr(sqrt_wsse, INFINITY, s.width, s.height,
                                             s.max_error, 1);
        s.sum_w_dist += sqrt_wsse;
        s.sum_xpsnr += frame_score;
        s.frames++;
    }

    *out_score = avg_xpsnr(s.sum_w_dist, s.sum_xpsnr, s.width, s.height,
                           s.max_error, s.frames);
    status = TRIM_OK;

done:
    free(s.sse_luma);
    free(s.weights);
    free(s.org);
    free(s.org_m1);
    free(s.org_m2);
    free(s.rec);
    return status;
}
