/*
 * JPEG → JPEG through jpegli (BUILD.md § 5, the default photo path).
 *
 * jpegli's API is deliberately libjpeg-shaped, so this is a decompress into RGB followed by
 * a compress at the requested quality. Two things about it are worth stating:
 *
 * **Why decode and re-encode rather than transcode.** jpegli's gain comes from its encoder —
 * adaptive quantisation and better colour handling — which needs pixels. A coefficient-level
 * transcode would keep the original's quantisation decisions, which are the thing being
 * improved on.
 *
 * **Why setjmp.** libjpeg's default error handler calls `exit()`. A malformed JPEG in a
 * user's library must mark one item `FAILED` and let the night continue
 * (ARCHITECTURE.md § 13), not take the process with it, so the error manager is replaced
 * with one that longjmps back here.
 *
 * jpegli lives at github.com/google/jpegli, not inside libjxl — upstream split it out.
 * STACK.md and the ndk-build skill said otherwise and have been corrected.
 */

#include <csetjmp>
#include <cstdlib>
#include <cstring>
#include <vector>

#include "jpegli/decode.h"
#include "jpegli/encode.h"

#include "../trim_native.h"

namespace {

struct TrimErrorManager {
    jpeg_error_mgr base;
    jmp_buf escape;
};

void OnFatalError(j_common_ptr cinfo) {
    auto *manager = reinterpret_cast<TrimErrorManager *>(cinfo->err);
    longjmp(manager->escape, 1);
}

void Quiet(j_common_ptr, int) {}
void QuietMessage(j_common_ptr) {}

/** Decodes a JPEG to packed 8-bit RGB. Returns false on any malformed input. */
bool DecodeRgb(const uint8_t *src, size_t src_len, std::vector<uint8_t> *rgb,
               int *width, int *height) {
    jpeg_decompress_struct cinfo;
    TrimErrorManager err;
    memset(&cinfo, 0, sizeof(cinfo));

    cinfo.err = jpegli_std_error(&err.base);
    err.base.error_exit = OnFatalError;
    err.base.emit_message = Quiet;
    err.base.output_message = QuietMessage;

    if (setjmp(err.escape)) {
        jpegli_destroy_decompress(&cinfo);
        return false;
    }

    jpegli_create_decompress(&cinfo);
    jpegli_mem_src(&cinfo, src, src_len);
    if (jpegli_read_header(&cinfo, TRUE) != JPEG_HEADER_OK) {
        jpegli_destroy_decompress(&cinfo);
        return false;
    }

    cinfo.out_color_space = JCS_RGB;
    // Stated rather than assumed. jpegli does default to 8-bit, but JPEGLI_TYPE_FLOAT is
    // the zero value of the enum, so anything that ever zeroes this struct gets float
    // samples read into a byte buffer — an image that is still the right size and still a
    // valid JPEG, merely wrong.
    jpegli_set_output_format(&cinfo, JPEGLI_TYPE_UINT8, JPEGLI_NATIVE_ENDIAN);
    if (!jpegli_start_decompress(&cinfo)) {
        jpegli_destroy_decompress(&cinfo);
        return false;
    }

    *width = static_cast<int>(cinfo.output_width);
    *height = static_cast<int>(cinfo.output_height);
    const size_t row_bytes = static_cast<size_t>(*width) * 3;
    rgb->resize(row_bytes * static_cast<size_t>(*height));

    while (cinfo.output_scanline < cinfo.output_height) {
        uint8_t *row = rgb->data() + row_bytes * cinfo.output_scanline;
        JSAMPROW rows[1] = {row};
        if (jpegli_read_scanlines(&cinfo, rows, 1) != 1) {
            jpegli_destroy_decompress(&cinfo);
            return false;
        }
    }

    jpegli_finish_decompress(&cinfo);
    jpegli_destroy_decompress(&cinfo);
    return true;
}

bool EncodeRgb(const std::vector<uint8_t> &rgb, int width, int height, int quality,
               std::vector<uint8_t> *out) {
    jpeg_compress_struct cinfo;
    TrimErrorManager err;
    memset(&cinfo, 0, sizeof(cinfo));

    cinfo.err = jpegli_std_error(&err.base);
    err.base.error_exit = OnFatalError;
    err.base.emit_message = Quiet;
    err.base.output_message = QuietMessage;

    unsigned char *buffer = nullptr;
    unsigned long buffer_len = 0;  // NOLINT(runtime/int) - libjpeg's signature

    if (setjmp(err.escape)) {
        jpegli_destroy_compress(&cinfo);
        if (buffer != nullptr) free(buffer);
        return false;
    }

    jpegli_create_compress(&cinfo);
    jpegli_mem_dest(&cinfo, &buffer, &buffer_len);

    cinfo.image_width = static_cast<JDIMENSION>(width);
    cinfo.image_height = static_cast<JDIMENSION>(height);
    cinfo.input_components = 3;
    cinfo.in_color_space = JCS_RGB;

    // The same, for the encoder. Must precede jpegli_set_defaults.
    jpegli_set_input_format(&cinfo, JPEGLI_TYPE_UINT8, JPEGLI_NATIVE_ENDIAN);

    jpegli_set_defaults(&cinfo);

    /*
     * 4:4:4, explicitly.
     *
     * Left to itself this path produced 4:2:0, and the cross-check against upstream's
     * cjpegli caught it: the blue channel came back with five times the error of red or
     * green and SSIMULACRA2 fell from 93 to 67 — while the file got *larger*, because the
     * chroma artefacts cost more bits than the subsampling saved. Upstream's own quality
     * scale makes the same point from the other direction, describing 85 as "quality 85
     * (4:2:2)" and 90 as "quality 90 (4:4:4)".
     *
     * This app gates at SSIMULACRA2 85-90 (BUILD.md § 5), which is squarely in the range
     * where full chroma is what the gate is asking for. Subsampling here would mean the
     * search spending probes climbing back to a score full chroma reaches for free.
     */
    cinfo.comp_info[0].h_samp_factor = 1;
    cinfo.comp_info[0].v_samp_factor = 1;

    // Progressive, as upstream's own tool produces: the same pixels in fewer bytes, at no
    // cost to quality. Every decoder this app will meet has supported it for twenty years.
    jpegli_set_progressive_level(&cinfo, 2);

    // The whole point of jpegli over libjpeg-turbo: quantisation that follows the image
    // rather than a fixed table.
    jpegli_enable_adaptive_quantization(&cinfo, TRUE);
    jpegli_set_quality(&cinfo, quality, TRUE);

    jpegli_start_compress(&cinfo, TRUE);
    const size_t row_bytes = static_cast<size_t>(width) * 3;
    while (cinfo.next_scanline < cinfo.image_height) {
        JSAMPROW rows[1] = {const_cast<uint8_t *>(rgb.data() + row_bytes * cinfo.next_scanline)};
        if (jpegli_write_scanlines(&cinfo, rows, 1) != 1) {
            jpegli_destroy_compress(&cinfo);
            if (buffer != nullptr) free(buffer);
            return false;
        }
    }
    jpegli_finish_compress(&cinfo);
    jpegli_destroy_compress(&cinfo);

    out->assign(buffer, buffer + buffer_len);
    free(buffer);
    return true;
}

}  // namespace

extern "C" int32_t jpegli_encode(const uint8_t *src, size_t src_len, int32_t quality,
                                 uint8_t *out, size_t *out_len) {
    if (src == nullptr || src_len == 0 || out_len == nullptr) return TRIM_ERR_INVALID_ARG;
    if (quality < 1 || quality > 100) return TRIM_ERR_INVALID_ARG;

    std::vector<uint8_t> rgb;
    int width = 0;
    int height = 0;
    if (!DecodeRgb(src, src_len, &rgb, &width, &height)) return TRIM_ERR_INVALID_ARG;

    std::vector<uint8_t> encoded;
    if (!EncodeRgb(rgb, width, height, quality, &encoded)) return TRIM_ERR_INTERNAL;

    // The ABI's two-call convention: ask for the size, then supply a buffer.
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
