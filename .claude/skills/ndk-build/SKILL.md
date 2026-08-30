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
  cmake/                toolchain files, meson cross files
  patches/              upstream patches applied at configure time
  xpsnr/                submodule fraunhoferhhi/xpsnr
  vmaf/                 submodule Netflix/vmaf      (libvmaf via meson -> static lib)
  libjxl/               submodule libjxl/libjxl     (jpegli + ssimulacra2 only)
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

**XPSNR** (`fraunhoferhhi/xpsnr`) — upstream ships the metric as an FFmpeg filter patch
plus standalone C. Build the standalone C only; do **not** pull FFmpeg in. This is the
search metric, called thousands of times a night, so it gets the most attention on the
NEON paths.

**libvmaf** (`Netflix/vmaf`) — meson/ninja, not CMake. Drive it from CMake with a custom
command running meson against a generated cross file (compiler, ar, strip,
`cpu_family = 'aarch64'`, `system = 'android'` or `'darwin'`), then
`add_library(vmaf STATIC IMPORTED)` on the resulting `libvmaf.a`. Build with
`-Denable_float=true` and `-Dbuilt_in_models=true` so `vmaf_v0.6.1` is embedded and
nothing has to ship as an asset. No CUDA, no AVX. Verification config is fixed by
BUILD.md: `vmaf_v0.6.1`, 1080p, `n_subsample=10`.

**libjxl** (`libjxl/libjxl`) — one submodule gives both `jpegli` and
`tools/ssimulacra2`. Turn everything else off: `JPEGXL_ENABLE_BENCHMARK`,
`_EXAMPLES`, `_MANPAGES`, `_SJPEG`, `_OPENEXR` `=OFF`, `JPEGXL_ENABLE_SKCMS=ON`. Needs
`highway` and `brotli` initialised recursively.

**libheif** (`strukturag/libheif`) — fallback only, where `HeifWriter` (Android) or
`CGImageDestination` (iOS) is unavailable or broken. Prefer the platform writer. Build
with the x265 encoder **off**: software video encoding is banned, and HEIC stills go
through the platform encoder where possible.

**oxipng** (`oxipng/oxipng`) — Rust. `cargo-ndk` for Android (`cargo ndk -t arm64-v8a
build --release`), plain cargo with the `aarch64-apple-ios` target for iOS, both into a
staticlib behind a thin C-FFI crate. Pin the toolchain in `rust-toolchain.toml` so CI
and dev machines agree.

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
