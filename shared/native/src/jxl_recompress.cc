/*
 * JPEG → JPEG XL, losslessly (BUILD.md § 5, the "reversible mode" setting).
 *
 * This is the one path in the app where "lossless" is literally true: JPEG XL can store a
 * JPEG's own DCT coefficients, so the original file can be reconstructed byte for byte from
 * the result. Everything else the app does is *visually* lossless and PROJECT.md is
 * emphatic that the two must never be confused in front of the user.
 *
 * `JxlEncoderStoreJPEGMetadata` is what makes it reversible: without it the encoder keeps
 * the pixels but not the JPEG container, and the file the user gets back would be a
 * different JPEG rather than the same one.
 */

#include <cstdint>
#include <cstring>
#include <vector>

#include <jxl/encode.h>
#include <jxl/encode_cxx.h>

#include "../trim_native.h"

extern "C" int32_t jxl_recompress(const uint8_t *src, size_t src_len,
                                  uint8_t *out, size_t *out_len) {
    if (src == nullptr || src_len == 0 || out_len == nullptr) return TRIM_ERR_INVALID_ARG;

    JxlEncoderPtr encoder = JxlEncoderMake(nullptr);
    if (encoder == nullptr) return TRIM_ERR_INTERNAL;

    // Reconstruction data. Without this the result is a JXL of the same picture, not a
    // container the original JPEG can be recovered from — which is the entire promise.
    if (JxlEncoderStoreJPEGMetadata(encoder.get(), JXL_TRUE) != JXL_ENC_SUCCESS) {
        return TRIM_ERR_INTERNAL;
    }

    JxlEncoderFrameSettings *settings = JxlEncoderFrameSettingsCreate(encoder.get(), nullptr);
    if (settings == nullptr) return TRIM_ERR_INTERNAL;
    if (JxlEncoderSetFrameLossless(settings, JXL_TRUE) != JXL_ENC_SUCCESS) {
        return TRIM_ERR_INTERNAL;
    }

    if (JxlEncoderAddJPEGFrame(settings, src, src_len) != JXL_ENC_SUCCESS) {
        // A JPEG jxl cannot take coefficients from — an arithmetic-coded or otherwise
        // unusual one. Not an internal failure: the file is simply not a candidate.
        return TRIM_ERR_UNSUPPORTED;
    }
    JxlEncoderCloseInput(encoder.get());

    std::vector<uint8_t> encoded;
    encoded.resize(src_len);  // A lossless JXL of a JPEG is smaller; this is a safe start.
    uint8_t *next = encoded.data();
    size_t avail = encoded.size();

    JxlEncoderStatus status = JXL_ENC_NEED_MORE_OUTPUT;
    while (status == JXL_ENC_NEED_MORE_OUTPUT) {
        status = JxlEncoderProcessOutput(encoder.get(), &next, &avail);
        if (status == JXL_ENC_NEED_MORE_OUTPUT) {
            const size_t used = encoded.size() - avail;
            encoded.resize(encoded.size() * 2);
            next = encoded.data() + used;
            avail = encoded.size() - used;
        }
    }
    if (status != JXL_ENC_SUCCESS) return TRIM_ERR_INTERNAL;
    encoded.resize(encoded.size() - avail);

    if (out == nullptr) {
        *out_len = encoded.size();
        return TRIM_OK;
    }
    if (*out_len < encoded.size()) {
        *out_len = encoded.size();
        return TRIM_ERR_INVALID_ARG;
    }
    memcpy(out, encoded.data(), encoded.size());
    *out_len = encoded.size();
    return TRIM_OK;
}
