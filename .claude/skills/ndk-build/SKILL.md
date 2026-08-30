---
name: ndk-build
description: Building Trim Gallery's native metric and photo-codec libraries (XPSNR, libvmaf, libjxl/jpegli/ssimulacra2, oxipng, libheif) once for two targets — android-arm64 via the NDK and ios-arm64 via Xcode — behind the single C ABI in trim_native.h, bound with JNI on Android and Kotlin/Native cinterop on iOS. Use when adding, updating or debugging anything under shared/native, when a CMake/meson/cargo build fails, or when changing the C ABI.
---

# NDK build

Native code exists here for one reason: the quality metrics and the still-image codecs
have no usable platform equivalent on either OS. Video encoding is **never** native —
see `codec-priority`.

## Ground rules

1. **arm64 only, both platforms.** `arm64-v8a` on Android, `arm64` on iOS. No
   armeabi-v7a, no x86, no x86_64, no simulator-x86. One ABI per platform keeps the
   binary small and the build fast; emulator work uses an arm64 system image.
2. **NEON on, always.** `-march=armv8-a+simd`. The metrics are the bottleneck
   (PROJECT.md § Speed); a scalar build is not worth shipping.
3. **One build, two targets.** The same CMake project produces
   `libtrim_native.a` for android-arm64 and an XCFramework for ios-arm64. Platform
   differences live in toolchain files, never in `#ifdef`s scattered through the code.
4. **One C ABI.** Everything crosses through `trim_native.h`. Kotlin binds it with JNI
   on Android and cinterop on iOS — the *same header*, so a signature change breaks
   both bindings at compile time instead of one at runtime.
5. **Buffers, never paths.** Every function takes planar YUV or byte buffers.
   The native layer has no idea what a SAF URI or a `PHAsset` is, and no business
   opening a user's file — that is `LibraryStorage`'s job (and see `safe-replace`).
6. **Submodules, never vendored copies.** Upstream lives as git submodules; patches go
   in `shared/native/patches/*.patch`, applied at configure time, so an upstream bump
   is a submodule bump.
7. **Never add a library that is not in STACK.md.** Ask first.

## Layout

```
shared/native/
  CMakeLists.txt        top level; targets android-arm64-v8a and ios-arm64
  trim_native.h         the C ABI, shared by JNI and cinterop
  src/                  the ABI implementation over the upstream libraries
  test/                 golden-value verification against upstream
  calibration/          the XPSNR-to-VMAF harness (PROJECT.md open question)
  cmake/                toolchain files, meson cross-file template
  patches/              upstream patches applied at configure time
  xpsnr/                submodule fraunhoferhhi/xpsnr
  vmaf/                 submodule Netflix/vmaf      (libvmaf via meson -> static lib)
  libjxl/               submodule libjxl/libjxl     (ssimulacra2 + JPEG XL encoder)
  jpegli/               submodule google/jpegli     (its own repo since the split)
  libheif/              submodule strukturag/libheif
  oxipng/               cargo wrapper crate, C FFI
  jni/                  one thin bridge per library (Android only)
```

## The C ABI

`trim_native.h` exposes exactly: `xpsnr_score`, `vmaf_score`, `ssim2_score`,
`jpegli_encode`, `jxl_recompress`, `png_optimise`. Rules:

- Plain C, no C++ types across the boundary, `extern "C"` guarded.
- Every call takes an explicit length for each buffer. No null-terminated anything.
- Return `int32_t`: `0` success, negative for failure. Never `abort()` — a metric
  failure marks the item `FAILED("metric error")` (ARCHITECTURE.md § 13) and the night
  pass continues.
- Every long-running call takes a `volatile int32_t* cancel`, polled between windows.
  Night work stops on unplug, thermal, cap and stop-by; native code that cannot be
  interrupted breaks that promise.

## Per-library notes

**XPSNR** (`fraunhoferhhi/xpsnr`) — **there is no standalone C in that repository.** It
contains only an FFmpeg filter (`libavfilter/vf_xpsnr.c`), and its README says the
maintained copy now lives in FFmpeg itself. So the metric is *extracted*:
`shared/native/src/xpsnr_score.c` keeps upstream's arithmetic verbatim and replaces the
AVFilter plumbing, context and allocation. Do **not** pull FFmpeg in to reach one
function. Luma only — the search needs a monotone quality proxy, luma dominates it, and
this is the metric run thousands of times a night. Verify against FFmpeg's own `xpsnr`
filter, whose per-component "XPSNR y" is the same number.

The licence is a Fraunhofer BSD-3 variant: commercial use is permitted, but it grants
**no patent rights** and disclaims patent non-infringement. Worth a product decision
before shipping, not just an engineering one.

**libvmaf** (`Netflix/vmaf`) — meson/ninja, not CMake. It bundles libsvm, which is C++,
so the final link needs the C++ runtime even though everything of ours is C; declare CXX
in the CMake project or the link fails on `__gxx_personality_v0`. Embedding the models
needs `xxd` on PATH at configure time — without it meson silently builds with no built-in
models and every `vmaf_model_load` fails at runtime. Drive it from CMake with a custom
command running meson against a generated cross file (compiler, ar, strip,
`cpu_family = 'aarch64'`, `system = 'android'` or `'darwin'`), then
`add_library(vmaf STATIC IMPORTED)` on the resulting `libvmaf.a`. Build with
`-Denable_float=true` and `-Dbuilt_in_models=true` so `vmaf_v0.6.1` is embedded and
nothing has to ship as an asset. No CUDA, no AVX. Verification config is fixed by
BUILD.md: `vmaf_v0.6.1`, 1080p, `n_subsample=10`.

**libjxl** (`libjxl/libjxl`) — SSIMULACRA 2 and the JPEG XL encoder. **It no longer
contains jpegli**: upstream split that out and at libjxl's head `lib/jpegli` does not
exist, so it is a second submodule (below). Turn everything else off:
`JPEGXL_ENABLE_BENCHMARK`, `_EXAMPLES`, `_MANPAGES`, `_SJPEG`, `_OPENEXR` `=OFF`,
`JPEGXL_ENABLE_SKCMS=ON`. Needs `highway`, `brotli` and `skcms` initialised recursively,
plus `libpng`/`zlib` if you want the reference tools for verification.

SSIMULACRA 2 is `tools/ssimulacra2.cc`, a **tool source, not part of any library** — the
`ssimulacra2` binary is gated behind `JPEGXL_ENABLE_DEVTOOLS`, and the function is not in
`libjxl.a`. Compile that one file directly into `libtrim_native`. Feed it images through
`jxl::SetFromBytes` on an in-memory PPM rather than building an `ImageBundle` by hand: it
is the path the upstream tool takes, so the number matches to the last digit, and hand-built
colour setup against internal APIs breaks on every release.

**jpegli** (`google/jpegli`) — libjpeg-shaped API, so JPEG → JPEG is a decompress into RGB
and a compress at the target quality. Two things that a link-and-run check will not catch:

- **Set the chroma sampling factors explicitly.** Left alone this path produced 4:2:0
  baseline where upstream's `cjpegli` produces 4:4:4 progressive; the blue channel came back
  with five times the error of red or green, SSIMULACRA 2 fell from 93.6 to 67.0, and the
  file got *larger*, because the chroma artefacts cost more bits than the subsampling saved.
  Set `comp_info[0].h_samp_factor = v_samp_factor = 1` after `jpegli_set_defaults`, and
  `jpegli_set_progressive_level(cinfo, 2)`.
- **Replace the error manager.** libjpeg's default calls `exit()`. A malformed JPEG in a
  user's library must mark one item `FAILED` and let the night continue
  (ARCHITECTURE.md § 13), so use a `setjmp` handler.

**libheif** (`strukturag/libheif`) — fallback only, where `HeifWriter` (Android) or
`CGImageDestination` (iOS) is unavailable or broken. Prefer the platform writer. Build
with the x265 encoder **off**: software video encoding is banned, and HEIC stills go
through the platform encoder where possible.

**oxipng** (`oxipng/oxipng`) — Rust, and a crates.io dependency of the thin C-FFI wrapper
crate in `oxipng/` rather than a submodule: upstream ships a library crate and there is no
C to build. `cargo-ndk` for Android (`cargo ndk -t arm64-v8a build --release`), plain cargo
with the `aarch64-apple-ios` target for iOS, both into a staticlib. Pin the toolchain in
`rust-toolchain.toml` and the versions in `Cargo.lock` so CI and dev machines agree.

Build it with `default-features = false`: `parallel` starts a rayon pool that competes with
the encoder for cores on a phone trying to stay cool, and `zopfli` spends minutes per image
for a few per cent, against a nightly cap measured in minutes. **Wrap the call in
`catch_unwind`** — oxipng panics on some malformed input, and a panic across an FFI boundary
is undefined behaviour.

## Binding

**Android — JNI.** One `shared/native/jni/<lib>_jni.cpp` per library, all compiled into
`libtrim_native.so`.

- **Kotlin owns the lifecycle.** A native handle is a `long` held by a Kotlin
  `AutoCloseable`; every allocation has a matching `close()` and callers use `use { }`.
- **No JNI in hot loops.** Pass a whole plane or window across once and score it
  natively. A per-frame callback into Kotlin costs more than the metric.
- **Direct buffers only** — `ByteBuffer.allocateDirect` read with
  `GetDirectBufferAddress`. Never `GetByteArrayElements` on a frame-sized array.
- **`RegisterNatives` in `JNI_OnLoad`**, not name mangling: it survives obfuscation and
  fails loudly at load time instead of at first call.

**iOS — cinterop.** A `.def` file pointing at `trim_native.h` and the static lib. Same
rules on buffer ownership; wrap the raw `CPointer` in the same Kotlin `AutoCloseable`
type so `shared/` sees one API.

## Verifying a build

A native library is not done when it links. For each one:

1. Score a known input on device and against the upstream desktop binary — the numbers
   must match to the documented tolerance. A metric that is fast and wrong silently
   ruins every replace decision.
2. Check the artefact contains only arm64 (`llvm-readelf -h`, `lipo -info`) and that
   the APK has no second ABI directory.
3. Run once under ASan on a debug build per upstream bump.

Release builds may add `-O3` to metric code, but **not** `-ffast-math`: it changes
float results, and VMAF/XPSNR scores have to stay reproducible against the desktop
reference. If in doubt leave it off and note the decision in PROJECT.md.

## CI

Submodules check out `--recursive`. Native builds cache by submodule SHA + NDK/Xcode
version; a cache miss is a full rebuild and takes a long time, so bump submodules
deliberately, not incidentally.
