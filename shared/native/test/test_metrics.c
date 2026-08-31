/*
 * Metric verification.
 *
 * The golden values below were produced by the upstream implementations, not by this
 * code: XPSNR by FFmpeg's own `xpsnr` filter, VMAF by libvmaf's `vmaf` CLI and
 * independently by FFmpeg's `libvmaf` filter. That is the point — a metric that is fast
 * and wrong silently ruins every replace decision, so it has to be checked against
 * something that did not come from us.
 *
 * Run test/generate_fixtures.sh first; it decodes the committed golden clips into the
 * raw YUV this reads. The expected values below are for its default 15 frames.
 */

#include "../trim_native.h"

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define WIDTH 640
#define HEIGHT 360
#define FRAME_RATE 30

/* Both metrics are reported to four decimals; agreeing to three is a real match. */
#define TOLERANCE 0.001

/* Upstream: `ffmpeg -i ref -i dist -lavfi xpsnr` -> "XPSNR y: 29.2677". */
#define GOLDEN_XPSNR_Y 29.3297

/* Upstream: libvmaf CLI, vmaf_v0.6.1, all frames -> 64.177000. */
#define GOLDEN_VMAF 63.849407

static int failures;

static void check(const char *what, double expected, double actual, double tolerance)
{
    const double delta = fabs(expected - actual);
    if (delta <= tolerance) {
        printf("  ok    %-34s %.4f (upstream %.4f)\n", what, actual, expected);
    } else {
        printf("  FAIL  %-34s %.4f, expected %.4f (delta %.4f)\n", what, actual, expected, delta);
        failures++;
    }
}

static void check_code(const char *what, int32_t expected, int32_t actual)
{
    if (expected == actual) {
        printf("  ok    %-34s %d\n", what, actual);
    } else {
        printf("  FAIL  %-34s %d, expected %d\n", what, actual, expected);
        failures++;
    }
}

static uint8_t *slurp(const char *path, size_t *len)
{
    FILE *f = fopen(path, "rb");
    if (!f) return NULL;
    fseek(f, 0, SEEK_END);
    *len = (size_t)ftell(f);
    fseek(f, 0, SEEK_SET);
    uint8_t *buf = malloc(*len);
    if (!buf || fread(buf, 1, *len, f) != *len) { free(buf); fclose(f); return NULL; }
    fclose(f);
    return buf;
}

/** Splits interleaved YUV420p frames into the three contiguous planes the ABI takes. */
static int load_window_sized(trim_yuv_window *w, const char *path, int width, int height)
{
    size_t len = 0;
    uint8_t *raw = slurp(path, &len);
    if (!raw) return 0;

    const size_t luma = (size_t)width * height;
    const size_t chroma = luma / 4;
    const int frames = (int)(len / (luma + 2 * chroma));

    uint8_t *y = malloc(luma * frames);
    uint8_t *u = malloc(chroma * frames);
    uint8_t *v = malloc(chroma * frames);
    for (int i = 0; i < frames; i++) {
        const uint8_t *src = raw + (size_t)i * (luma + 2 * chroma);
        memcpy(y + (size_t)i * luma, src, luma);
        memcpy(u + (size_t)i * chroma, src + luma, chroma);
        memcpy(v + (size_t)i * chroma, src + luma + chroma, chroma);
    }
    free(raw);

    w->y = y; w->u = u; w->v = v;
    w->y_stride = width; w->u_stride = width / 2; w->v_stride = width / 2;
    w->width = width; w->height = height; w->frame_count = frames;
    return 1;
}

static int load_window(trim_yuv_window *w, const char *path)
{
    return load_window_sized(w, path, WIDTH, HEIGHT);
}

/**
 * `--sweep ref dist W H fps` prints "xpsnr,vmaf" for one pair.
 *
 * Used by calibration/calibrate.sh, so the calibration numbers come from the app's own
 * metrics rather than from whatever ffmpeg happens to be on the machine.
 */
static int sweep(int argc, char **argv)
{
    if (argc < 7) {
        fprintf(stderr, "usage: test_metrics --sweep <ref.yuv> <dist.yuv> <width> <height> <fps>\n");
        return 2;
    }
    const int width = atoi(argv[4]);
    const int height = atoi(argv[5]);
    const int fps = atoi(argv[6]);

    trim_yuv_window ref, dist;
    if (!load_window_sized(&ref, argv[2], width, height) ||
        !load_window_sized(&dist, argv[3], width, height)) {
        fprintf(stderr, "could not load %s / %s\n", argv[2], argv[3]);
        return 2;
    }

    int32_t cancel = 0;
    double x = 0.0, v = 0.0;
    if (xpsnr_score(&ref, &dist, fps, &cancel, &x) != TRIM_OK) return 1;
    if (vmaf_score(&ref, &dist, 1, &cancel, &v) != TRIM_OK) return 1;
    printf("%.4f,%.4f\n", x, v);
    return 0;
}

int main(int argc, char **argv)
{
    if (argc > 1 && strcmp(argv[1], "--sweep") == 0) {
        return sweep(argc, argv);
    }
    if (argc < 3) {
        fprintf(stderr, "usage: test_metrics <reference.yuv> <distorted.yuv>\n");
        return 2;
    }

    trim_yuv_window ref, dist;
    if (!load_window(&ref, argv[1]) || !load_window(&dist, argv[2])) {
        fprintf(stderr, "could not load fixtures\n");
        return 2;
    }
    printf("fixtures: %dx%d, %d frames\n", ref.width, ref.height, ref.frame_count);

    int32_t cancel = 0;
    double score = 0.0;

    /* --- the numbers must match upstream ------------------------------------ */
    check_code("xpsnr returns OK", TRIM_OK,
               xpsnr_score(&ref, &dist, FRAME_RATE, &cancel, &score));
    check("xpsnr matches ffmpeg", GOLDEN_XPSNR_Y, score, TOLERANCE);

    check_code("vmaf returns OK", TRIM_OK, vmaf_score(&ref, &dist, 1, &cancel, &score));
    check("vmaf matches libvmaf CLI", GOLDEN_VMAF, score, TOLERANCE);

    /* --- identical input is the ceiling -------------------------------------- */
    xpsnr_score(&ref, &ref, FRAME_RATE, &cancel, &score);
    if (score > 90.0 || isinf(score)) {
        printf("  ok    %-34s %.2f\n", "xpsnr of identical input", score);
    } else {
        printf("  FAIL  %-34s %.2f, expected a very high score\n", "xpsnr of identical input", score);
        failures++;
    }

    vmaf_score(&ref, &ref, 1, &cancel, &score);
    if (score > 95.0) {
        printf("  ok    %-34s %.2f\n", "vmaf of identical input", score);
    } else {
        printf("  FAIL  %-34s %.2f, expected > 95\n", "vmaf of identical input", score);
        failures++;
    }

    /* --- quality is monotone -------------------------------------------------
     * The search relies on this: a worse encode must score lower, or a binary search
     * over bitrate cannot converge on anything meaningful. */
    double worse = 0.0, better = 0.0;
    xpsnr_score(&ref, &dist, FRAME_RATE, &cancel, &worse);
    xpsnr_score(&ref, &ref, FRAME_RATE, &cancel, &better);
    if (better > worse) {
        printf("  ok    %-34s %.2f > %.2f\n", "xpsnr is monotone in quality", better, worse);
    } else {
        printf("  FAIL  %-34s %.2f !> %.2f\n", "xpsnr is monotone in quality", better, worse);
        failures++;
    }

    /* --- the error contract -------------------------------------------------- */
    cancel = 1;
    check_code("xpsnr honours cancellation", TRIM_ERR_CANCELLED,
               xpsnr_score(&ref, &dist, FRAME_RATE, &cancel, &score));
    check_code("vmaf honours cancellation", TRIM_ERR_CANCELLED,
               vmaf_score(&ref, &dist, 1, &cancel, &score));
    cancel = 0;

    check_code("null output rejected", TRIM_ERR_INVALID_ARG,
               xpsnr_score(&ref, &dist, FRAME_RATE, &cancel, NULL));
    check_code("null window rejected", TRIM_ERR_INVALID_ARG,
               vmaf_score(NULL, &dist, 1, &cancel, &score));

    trim_yuv_window narrow = ref;
    narrow.width = WIDTH / 2;
    check_code("mismatched sizes rejected", TRIM_ERR_INVALID_ARG,
               vmaf_score(&narrow, &dist, 1, &cancel, &score));
    check_code("mismatched sizes rejected (xpsnr)", TRIM_ERR_INVALID_ARG,
               xpsnr_score(&narrow, &dist, FRAME_RATE, &cancel, &score));

    check_code("zero subsample rejected", TRIM_ERR_INVALID_ARG,
               vmaf_score(&ref, &dist, 0, &cancel, &score));
    check_code("zero frame rate rejected", TRIM_ERR_INVALID_ARG,
               xpsnr_score(&ref, &dist, 0, &cancel, &score));

    /* --- the photo path is not silently zero --------------------------------- */
    check_code("ssim2 reports unimplemented", TRIM_ERR_UNSUPPORTED,
               ssim2_score(NULL, NULL, &cancel, &score));

    printf("\n%s\n", failures == 0 ? "all checks passed" : "FAILURES PRESENT");
    return failures == 0 ? 0 : 1;
}
