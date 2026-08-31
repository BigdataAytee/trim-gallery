/*
 * vmaf_score — the verification metric, over Netflix's libvmaf.
 *
 * BUILD.md § 5 fixes the configuration: vmaf_v0.6.1, three 5-second windows, and
 * n_subsample=10. This is the metric that gates every replacement, so a wrong number
 * here means a file replaced with one the user can see is worse. It is deliberately the
 * expensive metric, run on sampled windows only; XPSNR does the search.
 */

#include "../trim_native.h"

#include <stdlib.h>
#include <string.h>

#include <libvmaf/libvmaf.h>
#include <libvmaf/model.h>
#include <libvmaf/picture.h>

/* BUILD.md § 5. Not configurable: a different model is a different threshold. */
static const char *const TRIM_VMAF_MODEL = "vmaf_v0.6.1";

/**
 * One thread inside the metric.
 *
 * ARCHITECTURE.md § 8 gives metric work its own pool sized to cores-2, so parallelism
 * belongs to the caller across windows. Nesting a second thread pool underneath would
 * oversubscribe the exact cores the encoder is competing for.
 */
static const unsigned TRIM_VMAF_THREADS = 1;

static int trim_window_valid(const trim_yuv_window *w)
{
    return w && w->y && w->u && w->v && w->width > 0 && w->height > 0 && w->frame_count > 0;
}

/** Copies one plane into a VmafPicture, honouring both strides. */
static void trim_copy_plane(VmafPicture *pic, int plane, const uint8_t *src,
                            int32_t src_stride, unsigned w, unsigned h)
{
    uint8_t *dst = pic->data[plane];
    for (unsigned row = 0; row < h; row++) {
        memcpy(dst + (size_t)row * pic->stride[plane], src + (size_t)row * src_stride, w);
    }
}

/**
 * Fills `pic` from frame `index` of `win`.
 *
 * Frames are contiguous in the window: the caller decoded them once into one buffer
 * (PROJECT.md § Speed), so plane offsets are computed rather than passed.
 */
static int trim_fill_picture(VmafPicture *pic, const trim_yuv_window *win, int32_t index)
{
    const unsigned w = (unsigned)win->width;
    const unsigned h = (unsigned)win->height;
    const unsigned cw = (w + 1) / 2;
    const unsigned ch = (h + 1) / 2;

    if (vmaf_picture_alloc(pic, VMAF_PIX_FMT_YUV420P, 8, w, h) != 0) {
        return TRIM_ERR_INTERNAL;
    }

    trim_copy_plane(pic, 0, win->y + (size_t)index * win->y_stride * h, win->y_stride, w, h);
    trim_copy_plane(pic, 1, win->u + (size_t)index * win->u_stride * ch, win->u_stride, cw, ch);
    trim_copy_plane(pic, 2, win->v + (size_t)index * win->v_stride * ch, win->v_stride, cw, ch);
    return TRIM_OK;
}

int32_t vmaf_score(const trim_yuv_window *reference,
                   const trim_yuv_window *distorted,
                   int32_t subsample,
                   volatile const int32_t *cancel,
                   double *out_score)
{
    if (!out_score || !trim_window_valid(reference) || !trim_window_valid(distorted)) {
        return TRIM_ERR_INVALID_ARG;
    }
    if (reference->width != distorted->width || reference->height != distorted->height ||
        reference->frame_count != distorted->frame_count) {
        return TRIM_ERR_INVALID_ARG;
    }
    if (subsample < 1) {
        return TRIM_ERR_INVALID_ARG;
    }

    VmafConfiguration cfg = {
        /* Silent: a metric failure is reported through the return code and surfaces as
           FAILED("metric error") (ARCHITECTURE.md § 13), not as logcat noise. */
        .log_level = VMAF_LOG_LEVEL_NONE,
        .n_threads = TRIM_VMAF_THREADS,
        .n_subsample = (unsigned)subsample,
    };

    VmafContext *vmaf = NULL;
    VmafModel *model = NULL;
    int32_t status = TRIM_ERR_INTERNAL;

    if (vmaf_init(&vmaf, cfg) != 0) {
        return TRIM_ERR_INTERNAL;
    }

    VmafModelConfig model_cfg = { .name = NULL, .flags = VMAF_MODEL_FLAGS_DEFAULT };
    if (vmaf_model_load(&model, &model_cfg, TRIM_VMAF_MODEL) != 0) {
        /* The model is compiled into the library, so this is a build fault rather than a
           missing asset — hence UNSUPPORTED, which the caller reports differently. */
        status = TRIM_ERR_UNSUPPORTED;
        goto done;
    }
    if (vmaf_use_features_from_model(vmaf, model) != 0) {
        goto done;
    }

    for (int32_t i = 0; i < reference->frame_count; i++) {
        /* Polled between frames so night work can stop on unplug, thermal, cap or the
           user's alarm without waiting out a whole window. */
        if (cancel && *cancel) {
            status = TRIM_ERR_CANCELLED;
            goto done;
        }

        VmafPicture ref_pic, dist_pic;
        if (trim_fill_picture(&ref_pic, reference, i) != TRIM_OK) {
            goto done;
        }
        if (trim_fill_picture(&dist_pic, distorted, i) != TRIM_OK) {
            vmaf_picture_unref(&ref_pic);
            goto done;
        }

        /* Takes ownership of both pictures, whether it succeeds or fails. */
        if (vmaf_read_pictures(vmaf, &ref_pic, &dist_pic, (unsigned)i) != 0) {
            goto done;
        }
    }

    /* Flush: libvmaf needs the end-of-stream signal before it will pool. */
    if (vmaf_read_pictures(vmaf, NULL, NULL, 0) != 0) {
        goto done;
    }

    if (vmaf_score_pooled(vmaf, model, VMAF_POOL_METHOD_MEAN, out_score,
                          0, (unsigned)(reference->frame_count - 1)) != 0) {
        goto done;
    }

    status = TRIM_OK;

done:
    if (model) {
        vmaf_model_destroy(model);
    }
    if (vmaf) {
        vmaf_close(vmaf);
    }
    return status;
}
