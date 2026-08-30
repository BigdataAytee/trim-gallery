# ARCHITECTURE.md § 10 — one build, two targets.
#
# Produces the metric and photo-codec static libraries for android-arm64-v8a (NDK) and
# ios-arm64 (Xcode), behind the single C ABI in trim_native.h. Android binds it with
# JNI; iOS binds the same header with Kotlin/Native cinterop, so a signature change
# breaks both bindings at compile time instead of one of them at runtime.
#
# NOT WIRED INTO THE GRADLE BUILD YET. The submodules are declared in .gitmodules but
# are not initialised, and no Gradle module runs this file. Milestone 2 turns it on.
# See the `ndk-build` skill.

cmake_minimum_required(VERSION 3.22)
project(trim_native C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# arm64 only, NEON on. The metrics are the bottleneck (PROJECT.md § Speed); a scalar
# build is not worth shipping, and a second ABI would ship code that cannot run these.
if(ANDROID)
    if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
        message(FATAL_ERROR "Trim Gallery builds arm64-v8a only; got ${ANDROID_ABI}")
    endif()
    add_compile_options(-march=armv8-a+simd)
elseif(APPLE)
    if(NOT CMAKE_OSX_ARCHITECTURES STREQUAL "arm64")
        message(FATAL_ERROR "Trim Gallery builds ios-arm64 only; got ${CMAKE_OSX_ARCHITECTURES}")
    endif()
else()
    message(FATAL_ERROR "Unsupported target: Trim Gallery native builds for Android or iOS only")
endif()

# -O3 is fine on metric code. -ffast-math is NOT: it changes float results, and
# VMAF/XPSNR scores have to stay reproducible against the desktop reference.
add_compile_options($<$<CONFIG:Release>:-O3>)

option(TRIM_NATIVE_BUILD_SUBMODULES "Build the upstream submodules" OFF)

add_library(trim_native STATIC
    jni/placeholder.c
)

target_include_directories(trim_native PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})

if(TRIM_NATIVE_BUILD_SUBMODULES)
    # Milestone 2. Each of these is a submodule under this directory:
    #   xpsnr/   standalone C only -- do NOT pull FFmpeg in
    #   vmaf/    meson/ninja, driven by a custom command, -Dbuilt_in_models=true
    #   libjxl/  jpegli + tools/ssimulacra2 only, everything else OFF
    #   libheif/ x265 encoder OFF (software video encoding is banned)
    #   oxipng/  cargo-ndk (Android) / cargo --target aarch64-apple-ios (iOS)
    message(FATAL_ERROR "Submodule builds land with milestone 2; see the ndk-build skill.")
endif()
