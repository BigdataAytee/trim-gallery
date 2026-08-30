---
name: ndk-build
description: Building the native arm64 libraries for trim-gallery (XPSNR, libvmaf, libjxl/jpegli/ssimulacra2, libheif, oxipng) with CMake and wiring them to Kotlin over JNI. Use when adding, updating or debugging anything under native/, when a CMake/meson/cargo-ndk build fails, or when writing a new JNI bridge.
---

# NDK build

All native code in this project exists for one reason: the quality metrics and the still-image codecs have no usable platform equivalent. Video encoding is **never** native — see `codec-priority`.

## Ground rules

1. **arm64-v8a only.** No armeabi-v7a, no x86, no x86_64. One ABI keeps the APK small and the build fast. Emulator work uses an arm64 system image.
2. **NEON on, always.** `-march=armv8-a+simd`. The metrics are the bottleneck (PROJECT.md § Speed); scalar builds are not worth shipping.
3. **Static libs.** Each upstream project builds to a `.a`; exactly one shared object, `libtrimnative.so`, is loaded by the app. Fewer `System.loadLibrary` calls, no symbol collisions, no per-library soname versioning.
4. **Submodules, never vendored copies.** Upstream sources live as git submodules under `native/`. Patches go in `native/patches/*.patch` applied at configure time, so an upstream bump is a submodule bump.
5. **Never add a library that is not in STACK.md.** Ask first.

## Layout

```
native/
  CMakeLists.txt          # top level, arm64-v8a only, -march=armv8-a+simd
  patches/                # upstream patches applied at configure time
  xpsnr/                  # submodule fraunhoferhhi/xpsnr
  vmaf/                   # submodule Netflix/vmaf      (meson -> static lib)
  libjxl/                 # submodule libjxl/libjxl     (jpegli + ssimulacra2)
  libheif/                # submodule strukturag/libheif
  oxipng/                 # cargo-ndk crate wrapper
  jni/                    # one thin bridge per library
```

## Top-level CMake

Driven from the Gradle module via `externalNativeBuild { cmake { path "../native/CMakeLists.txt" } }`, with:

```kotlin
defaultConfig {
    ndk { abiFilters += "arm64-v8a" }
    externalNativeBuild {
        cmake {
            arguments += listOf("-DANDROID_STL=c++_static")
            cppFlags += "-march=armv8-a+simd"
            cFlags   += "-march=armv8-a+simd"
        }
    }
}
```

Release builds add `-O3 -ffast-math` **only** to metric code paths, never to codec or bitstream code — `-ffast-math` changes float results, and VMAF/XPSNR scores must be reproducible against the desktop reference. If in doubt, leave it off and note the decision in PROJECT.md.

## Per-library notes

**XPSNR** (`fraunhoferhhi/xpsnr`) — the upstream repo ships the metric as an FFmpeg filter patch plus standalone C. Build the standalone C only; do **not** pull FFmpeg in. This is the search metric, called thousands of times per night, so it gets the most attention on NEON paths.

**libvmaf** (`Netflix/vmaf`) — meson/ninja, not CMake. Drive it from CMake with a custom command that runs meson with an Android cross file generated at configure time (compiler, ar, strip, `cpu_family = 'aarch64'`, `system = 'android'`), then `add_library(vmaf STATIC IMPORTED)` pointing at the resulting `libvmaf.a`. Build with `-Denable_float=true`; the model `vmaf_v0.6.1` is embedded via `-Dbuilt_in_models=true` so nothing has to be shipped as an asset and loaded at runtime. Verification config is fixed by BUILD.md: `vmaf_v0.6.1`, 1080p, `n_subsample=10`.

**libjxl** (`libjxl/libjxl`) — one submodule gives both `jpegli` and `tools/ssimulacra2`. Turn off everything else: `JPEGXL_ENABLE_TOOLS=OFF` (except ssimulacra2), `JPEGXL_ENABLE_BENCHMARK=OFF`, `JPEGXL_ENABLE_EXAMPLES=OFF`, `JPEGXL_ENABLE_MANPAGES=OFF`, `JPEGXL_ENABLE_SJPEG=OFF`, `JPEGXL_ENABLE_OPENEXR=OFF`, `JPEGXL_ENABLE_SKCMS=ON`. It needs `highway` and `brotli` submodules initialised recursively.

**libheif** (`strukturag/libheif`) — only a fallback for devices where `HeifWriter` is unavailable or broken. Prefer the platform writer. Build with the x265 encoder **off** (software video encoding is banned, and HEIC still images go through the platform encoder where possible).

**oxipng** (`oxipng/oxipng`) — Rust. Built via `cargo-ndk` (`cargo ndk -t arm64-v8a build --release`) into a staticlib, wrapped by a `cdylib`-free thin crate exposing a C ABI. Wire it in as a CMake `IMPORTED` target with a custom command; keep the Rust toolchain pinned in `rust-toolchain.toml` so CI and dev machines agree.

## JNI bridge pattern

One `native/jni/<lib>_jni.cpp` per library, all compiled into `libtrimnative.so`. Rules:

- **Kotlin side owns the lifecycle.** A native handle is a `long` held by a Kotlin class that implements `AutoCloseable`; every allocation has a matching `close()`, and callers use `use { }`.
- **No JNI in hot loops.** Pass a whole plane/window across the boundary once and score it natively. A per-pixel or per-frame callback into Kotlin costs more than the metric.
- **Direct buffers only.** Frames cross as `ByteBuffer.allocateDirect` and are read with `GetDirectBufferAddress`. Never `GetByteArrayElements` on a frame-sized array.
- **Errors are exceptions.** Return a negative code from C, throw a Kotlin exception at the wrapper; do not `ThrowNew` deep inside library code.
- **Everything is cancellable.** Night work stops on unplug, thermal headroom and the nightly cap (BUILD.md § 5). Long native calls take a `volatile int* cancel` flag polled between windows, fed from the coroutine's cancellation.
- **Register with `RegisterNatives`** in `JNI_OnLoad` rather than relying on name mangling; it survives obfuscation and fails loudly at load time instead of at first call.

## Verifying a build

A native library is not "done" when it links. For each one:

1. Score a known input on device and against the upstream desktop binary — the numbers must match to the documented tolerance. A metric that is fast and wrong silently ruins every replace decision.
2. Check `libtrimnative.so` contains only arm64 (`llvm-readelf -h`) and that the APK has no other ABI directory.
3. Run under ASan on a debug build once per upstream bump.

## CI

Submodule checkout is `--recursive`. Native builds are cached by submodule SHA + NDK version; a cache miss is a full rebuild and takes a long time, so bump submodules deliberately, not incidentally.
