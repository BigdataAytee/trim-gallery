/*
 * SSIMULACRA 2, the photo quality gate (BUILD.md § 5).
 *
 * The metric itself is upstream's `tools/ssimulacra2.cc` from libjxl, used unmodified.
 * All this file does is get a `trim_image` into the shape upstream expects and hand back a
 * double across the C ABI.
 *
 * It gets there by writing a PPM in memory and calling `jxl::SetFromBytes`, which is the
 * exact path `tools/ssimulacra2_main.cc` takes. Building a `jxl::ImageBundle` by hand would
 * avoid the copy, but it would also mean reimplementing colour setup against internal APIs
 * that move between releases — and the number this returns has to equal the number the
 * upstream binary prints, or the calibration in `calibration/` is measuring two different
 * things. A PPM header plus a memcpy is a rounding error next to the metric itself.
 *
 * Alpha is dropped. The formats this gate protects — JPEG and HEIC — cannot carry it, and
 * `PhotoOptimiseStep` refuses a transparent source outright rather than letting the metric
 * compare two images that have both been flattened the same way and call it perfect.
 */

#include <cstdio>
#include <cstring>
#include <utility>
#include <string>
#include <vector>

#include <jxl/memory_manager.h>

#include "lib/extras/codec.h"
#include "lib/extras/codec_in_out.h"
#include "lib/extras/dec/color_hints.h"
#include "lib/jxl/base/span.h"
#include "lib/jxl/base/status.h"
#include "tools/no_memory_manager.h"
#include "tools/ssimulacra2.h"

#include "../trim_native.h"

namespace {

/** SSIMULACRA 2 needs at least this many pixels on each side to build its scales. */
constexpr int32_t kMinSide = 8;

/** Packs RGB (alpha dropped) into a binary PPM, the simplest thing SetFromBytes accepts. */
bool ToPpm(const trim_image *image, std::vector<uint8_t> *out) {
    if (image == nullptr || image->rgba == nullptr) return false;
    if (image->width < kMinSide || image->height < kMinSide) return false;
    if (image->stride < image->width * 4) return false;

    char header[64];
    const int header_len =
        snprintf(header, sizeof(header), "P6\n%d %d\n255\n", image->width, image->height);
    if (header_len <= 0) return false;

    const size_t pixels = static_cast<size_t>(image->width) * image->height;
    out->resize(static_cast<size_t>(header_len) + pixels * 3);
    memcpy(out->data(), header, static_cast<size_t>(header_len));

    uint8_t *dst = out->data() + header_len;
    for (int32_t y = 0; y < image->height; ++y) {
        const uint8_t *row = image->rgba + static_cast<size_t>(y) * image->stride;
        for (int32_t x = 0; x < image->width; ++x) {
            *dst++ = row[x * 4 + 0];
            *dst++ = row[x * 4 + 1];
            *dst++ = row[x * 4 + 2];
        }
    }
    return true;
}

bool Load(const trim_image *image, jxl::CodecInOut *io) {
    std::vector<uint8_t> ppm;
    if (!ToPpm(image, &ppm)) return false;
    return jxl::SetFromBytes(jxl::Bytes(ppm), jxl::extras::ColorHints(), io);
}

}  // namespace

extern "C" int32_t ssim2_score(const trim_image *reference, const trim_image *distorted,
                               volatile const int32_t *cancel, double *out_score) {
    if (reference == nullptr || distorted == nullptr || out_score == nullptr) {
        return TRIM_ERR_INVALID_ARG;
    }
    if (reference->width != distorted->width || reference->height != distorted->height) {
        return TRIM_ERR_INVALID_ARG;
    }
    if (cancel != nullptr && *cancel != 0) return TRIM_ERR_CANCELLED;

    JxlMemoryManager *memory_manager = jpegxl::tools::NoMemoryManager();
    jxl::CodecInOut ref(memory_manager);
    jxl::CodecInOut dist(memory_manager);

    if (!Load(reference, &ref) || !Load(distorted, &dist)) return TRIM_ERR_INVALID_ARG;

    // The only place the pass can stand down inside this call. The metric itself is one
    // bounded traversal of an image, measured in milliseconds (BUILD.md § 5), so there is
    // nothing longer-running to interrupt part-way.
    if (cancel != nullptr && *cancel != 0) return TRIM_ERR_CANCELLED;

    jxl::StatusOr<Msssim> msssim = ComputeSSIMULACRA2(ref.Main(), dist.Main());
    if (!msssim.ok()) return TRIM_ERR_INTERNAL;

    // JXL_ASSIGN_OR is upstream's macro for this; it expands to the same move, and using
    // it here would mean returning its failure value rather than our ABI's code.
    *out_score = std::move(msssim).value_().Score();
    return TRIM_OK;
}
