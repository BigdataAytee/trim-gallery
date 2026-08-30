/*
 * Golden verification for the photo half of the C ABI (ndk-build skill, "Verifying a
 * build"): *"Score a known input on device and against the upstream desktop binary — the
 * numbers must match to the documented tolerance."*
 *
 * So this deliberately does not assert against constants baked in by whoever wrote it. It
 * takes a PNG, runs our `ssim2_score` and `jpegli_encode`, writes the intermediates out,
 * and prints the numbers in a form the driver script compares against upstream's own
 * `ssimulacra2` and `cjpegli`. A metric that is fast and wrong silently ruins every replace
 * decision, and only upstream can say what right is.
 */

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "../trim_native.h"

extern "C" {
int32_t ssim2_score(const trim_image *, const trim_image *, volatile const int32_t *, double *);
int32_t jpegli_encode(const uint8_t *, size_t, int32_t, uint8_t *, size_t *);
int32_t jxl_recompress(const uint8_t *, size_t, uint8_t *, size_t *);
int32_t png_optimise(const uint8_t *, size_t, uint8_t *, size_t *);
}

namespace {

int failures = 0;

void check(bool ok, const char *what) {
    printf("%s %s\n", ok ? "ok  " : "FAIL", what);
    if (!ok) failures++;
}

std::vector<uint8_t> ReadFile(const char *path) {
    std::vector<uint8_t> data;
    FILE *f = fopen(path, "rb");
    if (f == nullptr) return data;
    fseek(f, 0, SEEK_END);
    long size = ftell(f);
    fseek(f, 0, SEEK_SET);
    data.resize(static_cast<size_t>(size));
    if (fread(data.data(), 1, data.size(), f) != data.size()) data.clear();
    fclose(f);
    return data;
}

bool WriteFile(const char *path, const uint8_t *data, size_t len) {
    FILE *f = fopen(path, "wb");
    if (f == nullptr) return false;
    const bool ok = fwrite(data, 1, len, f) == len;
    fclose(f);
    return ok;
}

/** Reads a binary PPM (P6) into packed RGBA, which is what `trim_image` carries. */
bool ReadPpmAsRgba(const char *path, std::vector<uint8_t> *rgba, int32_t *w, int32_t *h) {
    std::vector<uint8_t> raw = ReadFile(path);
    if (raw.size() < 16 || raw[0] != 'P' || raw[1] != '6') return false;

    size_t pos = 2;
    int values[3] = {0, 0, 0};
    for (int i = 0; i < 3; ++i) {
        while (pos < raw.size() && (isspace(raw[pos]) || raw[pos] == '#')) {
            if (raw[pos] == '#') while (pos < raw.size() && raw[pos] != '\n') pos++;
            else pos++;
        }
        int value = 0;
        while (pos < raw.size() && isdigit(raw[pos])) value = value * 10 + (raw[pos++] - '0');
        values[i] = value;
    }
    pos++;  // the single whitespace byte after maxval

    *w = values[0];
    *h = values[1];
    const size_t pixels = static_cast<size_t>(*w) * static_cast<size_t>(*h);
    if (values[2] != 255 || raw.size() < pos + pixels * 3) return false;

    rgba->resize(pixels * 4);
    for (size_t i = 0; i < pixels; ++i) {
        (*rgba)[i * 4 + 0] = raw[pos + i * 3 + 0];
        (*rgba)[i * 4 + 1] = raw[pos + i * 3 + 1];
        (*rgba)[i * 4 + 2] = raw[pos + i * 3 + 2];
        (*rgba)[i * 4 + 3] = 255;
    }
    return true;
}

trim_image AsImage(const std::vector<uint8_t> &rgba, int32_t w, int32_t h) {
    trim_image image;
    image.rgba = rgba.data();
    image.stride = w * 4;
    image.width = w;
    image.height = h;
    return image;
}

}  // namespace

int main(int argc, char **argv) {
    if (argc < 4) {
        fprintf(stderr,
                "usage: %s reference.ppm distorted.ppm source.jpg [out_dir] [source.png]\n",
                argv[0]);
        return 2;
    }
    const std::string out_dir = argc > 4 ? argv[4] : ".";

    // ------------------------------------------------------------ ssim2_score

    std::vector<uint8_t> ref_rgba;
    std::vector<uint8_t> dist_rgba;
    int32_t rw = 0, rh = 0, dw = 0, dh = 0;
    check(ReadPpmAsRgba(argv[1], &ref_rgba, &rw, &rh), "read reference PPM");
    check(ReadPpmAsRgba(argv[2], &dist_rgba, &dw, &dh), "read distorted PPM");
    check(rw == dw && rh == dh, "the two images are the same size");

    const trim_image reference = AsImage(ref_rgba, rw, rh);
    const trim_image distorted = AsImage(dist_rgba, dw, dh);

    double score = -1.0;
    check(ssim2_score(&reference, &distorted, nullptr, &score) == TRIM_OK, "ssim2_score returns OK");
    printf("SSIM2 %.8f\n", score);

    // Identical images score 100 exactly: the metric's own definition, and the cheapest
    // possible check that the plumbing has not transposed a channel or a row.
    double identical = -1.0;
    check(ssim2_score(&reference, &reference, nullptr, &identical) == TRIM_OK, "ssim2_score on itself");
    check(identical > 99.999, "an image against itself scores 100");
    printf("SSIM2_SELF %.8f\n", identical);

    // A worse image must score lower. Monotonicity is what the binary search rests on.
    check(score < identical, "a distorted image scores below an identical one");

    // The contract, not the happy path.
    check(ssim2_score(nullptr, &distorted, nullptr, &score) == TRIM_ERR_INVALID_ARG, "null reference is rejected");
    check(ssim2_score(&reference, &distorted, nullptr, nullptr) == TRIM_ERR_INVALID_ARG, "null out is rejected");
    const int32_t cancelled = 1;
    check(ssim2_score(&reference, &distorted, &cancelled, &score) == TRIM_ERR_CANCELLED, "cancellation is honoured");

    trim_image mismatched = distorted;
    mismatched.width = dw - 1;
    check(ssim2_score(&reference, &mismatched, nullptr, &score) == TRIM_ERR_INVALID_ARG, "size mismatch is rejected");

    trim_image tiny = reference;
    tiny.width = 4;
    tiny.height = 4;
    check(ssim2_score(&tiny, &tiny, nullptr, &score) == TRIM_ERR_INVALID_ARG, "an image below 8x8 is rejected");

    // --------------------------------------------------------- jpegli_encode

    std::vector<uint8_t> source = ReadFile(argv[3]);
    check(!source.empty(), "read the source JPEG");

    size_t needed = 0;
    check(jpegli_encode(source.data(), source.size(), 85, nullptr, &needed) == TRIM_OK,
          "jpegli_encode reports the size it needs");
    check(needed > 0, "the reported size is non-zero");

    std::vector<uint8_t> encoded(needed);
    size_t written = encoded.size();
    check(jpegli_encode(source.data(), source.size(), 85, encoded.data(), &written) == TRIM_OK,
          "jpegli_encode writes into the buffer");
    check(written == needed, "the second call writes exactly what the first promised");
    check(encoded[0] == 0xFF && encoded[1] == 0xD8, "the output starts with a JPEG SOI marker");

    const std::string q85 = out_dir + "/ours-q85.jpg";
    check(WriteFile(q85.c_str(), encoded.data(), written), "wrote ours-q85.jpg");
    printf("JPEGLI_Q85_BYTES %zu\n", written);

    // Higher quality must produce a larger file. The photo search bisects on exactly this
    // assumption, and a search over a non-monotone size is meaningless.
    size_t high = 0;
    check(jpegli_encode(source.data(), source.size(), 95, nullptr, &high) == TRIM_OK, "size at q95");
    size_t low = 0;
    check(jpegli_encode(source.data(), source.size(), 60, nullptr, &low) == TRIM_OK, "size at q60");
    printf("JPEGLI_Q95_BYTES %zu\nJPEGLI_Q60_BYTES %zu\n", high, low);
    check(low < written && written < high, "file size rises with quality");

    std::vector<uint8_t> at95(high);
    size_t at95_len = at95.size();
    check(jpegli_encode(source.data(), source.size(), 95, at95.data(), &at95_len) == TRIM_OK, "encode at q95");
    const std::string q95 = out_dir + "/ours-q95.jpg";
    check(WriteFile(q95.c_str(), at95.data(), at95_len), "wrote ours-q95.jpg");

    // A buffer that is too small must report the size rather than overrunning it.
    size_t small = 1;
    uint8_t one_byte = 0;
    check(jpegli_encode(source.data(), source.size(), 85, &one_byte, &small) == TRIM_ERR_INVALID_ARG,
          "a short buffer is refused");
    check(small == needed, "and is told how much it needed");

    check(jpegli_encode(source.data(), source.size(), 0, nullptr, &needed) == TRIM_ERR_INVALID_ARG,
          "quality 0 is rejected");
    check(jpegli_encode(source.data(), source.size(), 101, nullptr, &needed) == TRIM_ERR_INVALID_ARG,
          "quality 101 is rejected");

    const uint8_t rubbish[] = {1, 2, 3, 4, 5, 6, 7, 8};
    check(jpegli_encode(rubbish, sizeof(rubbish), 85, nullptr, &needed) == TRIM_ERR_INVALID_ARG,
          "a malformed JPEG fails without taking the process with it");

    // -------------------------------------------------------- jxl_recompress

    size_t jxl_len = 0;
    check(jxl_recompress(source.data(), source.size(), nullptr, &jxl_len) == TRIM_OK,
          "jxl_recompress reports the size it needs");
    std::vector<uint8_t> jxl(jxl_len);
    size_t jxl_written = jxl.size();
    check(jxl_recompress(source.data(), source.size(), jxl.data(), &jxl_written) == TRIM_OK,
          "jxl_recompress writes into the buffer");
    check(jxl_written < source.size(), "the JXL is smaller than the JPEG it came from");
    const std::string jxl_path = out_dir + "/ours-lossless.jxl";
    check(WriteFile(jxl_path.c_str(), jxl.data(), jxl_written), "wrote ours-lossless.jxl");
    printf("JXL_BYTES %zu FROM %zu\n", jxl_written, source.size());

    // The whole promise of reversible mode: the driver script decodes this back with djxl
    // and compares it to source.jpg byte for byte.
    check(jxl_recompress(nullptr, 0, nullptr, &jxl_len) == TRIM_ERR_INVALID_ARG, "null src is rejected");
    check(jxl_recompress(rubbish, sizeof(rubbish), nullptr, &jxl_len) != TRIM_OK,
          "a malformed JPEG is refused rather than silently mangled");

    // ----------------------------------------------------------- png_optimise

    if (argc > 5) {
        std::vector<uint8_t> png = ReadFile(argv[5]);
        check(!png.empty(), "read the source PNG");

        size_t png_len = 0;
        check(png_optimise(png.data(), png.size(), nullptr, &png_len) == TRIM_OK, "png_optimise sizes");
        std::vector<uint8_t> repacked(png_len);
        size_t png_written = repacked.size();
        check(png_optimise(png.data(), png.size(), repacked.data(), &png_written) == TRIM_OK,
              "png_optimise writes");
        check(png_written < png.size(), "the repack is smaller");
        check(memcmp(repacked.data(), "\x89PNG", 4) == 0, "the output is a PNG");
        const std::string png_path = out_dir + "/ours-repacked.png";
        check(WriteFile(png_path.c_str(), repacked.data(), png_written), "wrote ours-repacked.png");
        printf("PNG_BYTES %zu FROM %zu\n", png_written, png.size());

        check(png_optimise(rubbish, sizeof(rubbish), nullptr, &png_len) == TRIM_ERR_INVALID_ARG,
              "a malformed PNG fails without taking the process with it");
    }

    printf("\n%d check(s) failed\n", failures);
    return failures == 0 ? 0 : 1;
}
