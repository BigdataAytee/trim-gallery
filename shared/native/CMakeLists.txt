# ARCHITECTURE.md § 10 — one build, two targets.
#
# Produces libtrim_native for android-arm64-v8a (NDK) and ios-arm64 (Xcode), behind the
# single C ABI in trim_native.h. Android binds it with JNI, iOS with Kotlin/Native
# cinterop against the same header, so a signature change breaks both bindings at compile
# time rather than one of them at runtime.
#
# See the `ndk-build` skill for the rules this file implements.

cmake_minimum_required(VERSION 3.22)
# C and CXX: our own code is all C, but libvmaf bundles libsvm, which is C++, so the
# final link needs the C++ runtime. Declaring the language here is what makes CMake pick
# the right link driver on every platform, including the NDK's c++_static.
project(trim_native C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# --- one ABI per platform ----------------------------------------------------
#
# The metrics are the bottleneck (PROJECT.md § Speed), so NEON is not optional; and a
# second ABI would ship code that cannot run these libraries anyway.
if(ANDROID)
    if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
        message(FATAL_ERROR "Trim Gallery builds arm64-v8a only; got ${ANDROID_ABI}")
    endif()
    add_compile_options(-march=armv8-a+simd)
    set(TRIM_MESON_SYSTEM android)
    set(TRIM_TARGET_NAME trim_native)
elseif(APPLE)
    if(NOT CMAKE_OSX_ARCHITECTURES STREQUAL "arm64")
        message(FATAL_ERROR "Trim Gallery builds ios-arm64 only; got ${CMAKE_OSX_ARCHITECTURES}")
    endif()
    set(TRIM_MESON_SYSTEM darwin)
    set(TRIM_TARGET_NAME trim_native)
else()
    # A host build exists only to verify the metrics against their upstream reference
    # implementations; it is never shipped. See shared/native/test.
    message(STATUS "trim_native: host build (verification only, not shippable)")
    set(TRIM_MESON_SYSTEM linux)
    set(TRIM_TARGET_NAME trim_native)
endif()

# -O3 on the metrics is fine. -ffast-math is NOT: it changes float results, and the
# scores have to stay reproducible against the desktop reference, which is the only way
# we know the on-device numbers are right.
add_compile_options($<$<CONFIG:Release>:-O3>)

# --- libvmaf, built by meson -------------------------------------------------
#
# libvmaf uses meson/ninja, so CMake drives it through a custom command against a cross
# file generated from the toolchain CMake already resolved. built_in_models embeds
# vmaf_v0.6.1, so nothing has to ship as an asset and be read from disk on a device that
# should be doing nothing but encoding.
find_program(MESON_EXECUTABLE meson REQUIRED)
find_program(NINJA_EXECUTABLE ninja REQUIRED)

set(VMAF_SOURCE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/vmaf/libvmaf)
set(VMAF_BUILD_DIR ${CMAKE_CURRENT_BINARY_DIR}/vmaf-build)
set(VMAF_LIBRARY ${VMAF_BUILD_DIR}/src/libvmaf.a)

if(ANDROID OR APPLE)
    # The NDK's `clang` is one generic driver for every ABI: without `--target` it compiles
    # for the *host*. CMake passes that flag itself, so its own rules are fine — but meson
    # only knows what this file tells it, and the first arm64 build proved the gap exactly:
    #
    #     clang -o sanity_check_for_c_cross.exe … -march=armv8-a+simd
    #     error: unknown target CPU 'armv8-a+simd'
    #     note: valid target CPU values are: nocona, core2, … znver4, x86-64
    #     ld.lld: error: undefined symbol: main
    #     >>> referenced by /lib/x86_64-linux-gnu/Scrt1.o:(_start)
    #
    # x86 CPU names and the host's C runtime, from a compiler invoked to build for arm64.
    # So the target and sysroot come from the values CMake already resolved, on both the
    # compile and the link line — meson runs its sanity check through the linker too.
    set(TRIM_MESON_TOOLCHAIN_ARGS "")
    if(CMAKE_C_COMPILER_TARGET)
        string(APPEND TRIM_MESON_TOOLCHAIN_ARGS "'--target=${CMAKE_C_COMPILER_TARGET}', ")
    elseif(ANDROID AND CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
        # Only reachable if a future NDK toolchain file stops setting the variable above.
        # Composed rather than assumed, and still an ABI this build actually targets.
        string(APPEND TRIM_MESON_TOOLCHAIN_ARGS
            "'--target=aarch64-none-linux-android${CMAKE_SYSTEM_VERSION}', ")
    endif()
    if(CMAKE_SYSROOT)
        string(APPEND TRIM_MESON_TOOLCHAIN_ARGS "'--sysroot=${CMAKE_SYSROOT}', ")
    endif()
    # The same arguments without the trailing separator, for the list that ends with them.
    string(REGEX REPLACE ", $" "" TRIM_MESON_LINK_ARGS "${TRIM_MESON_TOOLCHAIN_ARGS}")
    set(TRIM_MESON_CROSS ${CMAKE_CURRENT_BINARY_DIR}/meson-cross.ini)
    configure_file(${CMAKE_CURRENT_SOURCE_DIR}/cmake/meson-cross.ini.in ${TRIM_MESON_CROSS} @ONLY)
    set(TRIM_MESON_CROSS_ARG --cross-file ${TRIM_MESON_CROSS})
else()
    set(TRIM_MESON_CROSS_ARG "")
endif()

add_custom_command(
    OUTPUT ${VMAF_LIBRARY}
    # meson refuses `--wipe` on a directory CMake has created but never configured, so
    # clear it first: this command only re-runs when libvmaf's own build files change.
    COMMAND ${CMAKE_COMMAND} -E rm -rf ${VMAF_BUILD_DIR}
    COMMAND ${MESON_EXECUTABLE} setup ${VMAF_BUILD_DIR} ${VMAF_SOURCE_DIR}
            --buildtype=release
            --default-library=static
            -Denable_float=true
            -Dbuilt_in_models=true
            -Denable_asm=$<IF:$<BOOL:${ANDROID}>,true,false>
            ${TRIM_MESON_CROSS_ARG}
    COMMAND ${NINJA_EXECUTABLE} -C ${VMAF_BUILD_DIR} src/libvmaf.a
    DEPENDS ${VMAF_SOURCE_DIR}/meson.build
    COMMENT "Building libvmaf (meson)"
    VERBATIM
)
add_custom_target(vmaf_build DEPENDS ${VMAF_LIBRARY})

add_library(vmaf STATIC IMPORTED)
set_target_properties(vmaf PROPERTIES IMPORTED_LOCATION ${VMAF_LIBRARY})
add_dependencies(vmaf vmaf_build)

# --- libjxl: SSIMULACRA 2 and the JPEG XL encoder ----------------------------
#
# One submodule, two things we need. Everything else is off: benchmarks, examples, man
# pages, the extra codecs. `JPEGXL_ENABLE_TOOLS` stays on only because SSIMULACRA 2 is
# shipped as `tools/ssimulacra2.cc` rather than as part of the library, and we compile
# that file directly into trim_native below.
set(JPEGXL_ENABLE_BENCHMARK OFF CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_EXAMPLES OFF CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_MANPAGES OFF CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_SJPEG OFF CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_OPENEXR OFF CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_DOXYGEN OFF CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_JNI OFF CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_PLUGINS OFF CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_VIEWERS OFF CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_DEVTOOLS OFF CACHE BOOL "" FORCE)
# ON, and the comment above is why: `lib/jxl_extras.cmake` — which defines
# `jxl_extras-internal`, the target carrying `lib/extras/codec.h` that `ssim2_score.cc`
# loads pixels through — is included by libjxl only `if(JPEGXL_ENABLE_TOOLS OR
# BUILD_TESTING)`. With it OFF the target simply does not exist, and CMake passes the
# unknown name straight to the linker as `-ljxl_extras-internal`, which fails at the very
# last edge of the build with "unable to find library". Turning it on defines libjxl's tool
# targets; it does not build them, because the Android build names `trim_native` on the
# ninja command line and CMake only builds that target's dependencies.
set(JPEGXL_ENABLE_TOOLS ON CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_SKCMS ON CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
add_subdirectory(libjxl EXCLUDE_FROM_ALL)

# --- jpegli ------------------------------------------------------------------
#
# Its own repository, not part of libjxl. Upstream split it out, and at libjxl's current
# head `lib/jpegli` no longer exists. STACK.md already pointed at google/jpegli; its build
# layout diagram and the ndk-build skill did not, and both have been corrected.
set(JPEGLI_ENABLE_BENCHMARK OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_DOXYGEN OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_MANPAGES OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_SJPEG OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_OPENEXR OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_JNI OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_FUZZERS OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_TOOLS OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_SKCMS ON CACHE BOOL "" FORCE)

# jpegli is a fork of libjxl and vendors its own copy of the same dependencies. Adding
# both subdirectories therefore defines `hwy`, `hwy_test`, `hwy_list_targets` and
# `tool_version_git` twice, and CMake refuses a duplicate target name outright (CMP0002).
# This is the collision PROJECT.md predicted when it noted that libjxl and jpegli each
# pull highway, brotli and skcms recursively.
#
# One highway, not two. `JPEGLI_FORCE_SYSTEM_HWY` sends jpegli down its `find_package(HWY)`
# branch instead of `add_subdirectory(highway)`, and the cache entries below make that find
# succeed against the copy libjxl has already added: jpegli's own `FindHWY.cmake` ends with
# `if (HWY_LIBRARY AND NOT TARGET hwy)`, so with the target already defined it creates
# nothing and every `hwy` reference in jpegli resolves to libjxl's real, in-tree target.
# `hwy` as the library value is the target name on purpose — anything that consumes
# `HWY_LIBRARIES` then links the target rather than a path that does not exist until the
# build runs.
#
# The version is read out of the header rather than written here, so it cannot drift from
# whatever the submodule is pinned at.
file(READ "${CMAKE_CURRENT_SOURCE_DIR}/libjxl/third_party/highway/hwy/base.h" TRIM_HWY_BASE_H)
string(REGEX MATCH "#define HWY_MAJOR +([0-9]+)" _trim_sink "${TRIM_HWY_BASE_H}")
set(TRIM_HWY_MAJOR "${CMAKE_MATCH_1}")
string(REGEX MATCH "#define +HWY_MINOR +([0-9]+)" _trim_sink "${TRIM_HWY_BASE_H}")
set(TRIM_HWY_MINOR "${CMAKE_MATCH_1}")
string(REGEX MATCH "#define +HWY_PATCH +([0-9]+)" _trim_sink "${TRIM_HWY_BASE_H}")
set(TRIM_HWY_PATCH "${CMAKE_MATCH_1}")
set(JPEGLI_FORCE_SYSTEM_HWY ON CACHE BOOL "" FORCE)
set(HWY_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/libjxl/third_party/highway" CACHE PATH "" FORCE)
set(HWY_LIBRARY "hwy" CACHE STRING "" FORCE)
set(HWY_VERSION "${TRIM_HWY_MAJOR}.${TRIM_HWY_MINOR}.${TRIM_HWY_PATCH}" CACHE STRING "" FORCE)

# One `tool_version_git`, not two. Both projects define that custom target when their
# version is not pinned, so pinning jpegli's leaves libjxl's — the one whose `tools/`
# directory supplies ssimulacra2.cc — as the only definition. Read from the submodule so
# the string is the commit actually built, not a guess.
execute_process(
    COMMAND git rev-parse --short HEAD
    WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/jpegli"
    OUTPUT_VARIABLE TRIM_JPEGLI_REV
    OUTPUT_STRIP_TRAILING_WHITESPACE
    ERROR_QUIET
)
if(TRIM_JPEGLI_REV STREQUAL "")
    set(TRIM_JPEGLI_REV "(unknown)")
endif()
set(JPEGLI_VERSION "${TRIM_JPEGLI_REV}" CACHE STRING "" FORCE)

add_subdirectory(jpegli EXCLUDE_FROM_ALL)

# --- oxipng ------------------------------------------------------------------
#
# Rust, behind a thin C-FFI crate. cargo-ndk for Android, plain cargo with the iOS target
# for Apple; the toolchain is pinned in oxipng/rust-toolchain.toml so CI and dev machines
# produce the same binary.
find_program(CARGO_EXECUTABLE cargo REQUIRED)

if(ANDROID)
    set(TRIM_RUST_TARGET aarch64-linux-android)
    # No `-o`. That flag turns on cargo-ndk's artifact collection, which copies **cdylibs**
    # into a jniLibs layout and fails outright when there are none:
    #
    #     error: No usable artifacts produced by cargo
    #     error: Did you set the crate-type in Cargo.toml to include 'cdylib'?
    #
    # This crate is a `staticlib` on purpose — it is archived into `libtrim_native.a`, not
    # loaded as its own `.so` — so there is nothing to collect and nothing that wants
    # collecting. Without `-o`, cargo-ndk does the one job needed here: set the linker, ar
    # and sysroot for the Android target and run cargo. The `.a` is read straight out of
    # `target/aarch64-linux-android/release/`.
    set(TRIM_CARGO_COMMAND ${CARGO_EXECUTABLE} ndk -t arm64-v8a build --release)
elseif(APPLE)
    set(TRIM_RUST_TARGET aarch64-apple-ios)
    set(TRIM_CARGO_COMMAND ${CARGO_EXECUTABLE} build --release --target ${TRIM_RUST_TARGET})
else()
    set(TRIM_RUST_TARGET "")
    set(TRIM_CARGO_COMMAND ${CARGO_EXECUTABLE} build --release)
endif()

set(OXIPNG_DIR ${CMAKE_CURRENT_SOURCE_DIR}/oxipng)
if(TRIM_RUST_TARGET STREQUAL "")
    set(OXIPNG_LIBRARY ${OXIPNG_DIR}/target/release/libtrim_oxipng.a)
else()
    set(OXIPNG_LIBRARY ${OXIPNG_DIR}/target/${TRIM_RUST_TARGET}/release/libtrim_oxipng.a)
endif()

add_custom_command(
    OUTPUT ${OXIPNG_LIBRARY}
    COMMAND ${CMAKE_COMMAND} -E chdir ${OXIPNG_DIR} ${TRIM_CARGO_COMMAND}
    DEPENDS ${OXIPNG_DIR}/src/lib.rs ${OXIPNG_DIR}/Cargo.toml ${OXIPNG_DIR}/Cargo.lock
    COMMENT "Building oxipng (cargo)"
    VERBATIM
)
add_custom_target(oxipng_build DEPENDS ${OXIPNG_LIBRARY})

add_library(trim_oxipng STATIC IMPORTED)
set_target_properties(trim_oxipng PROPERTIES IMPORTED_LOCATION ${OXIPNG_LIBRARY})
add_dependencies(trim_oxipng oxipng_build)

# --- trim_native -------------------------------------------------------------
#
# XPSNR is compiled from src/xpsnr_score.c, a standalone extraction of the algorithm in
# the xpsnr submodule. Upstream ships it only as an FFmpeg filter, and pulling FFmpeg in
# to reach one function is not a trade worth making — see PROJECT.md.
set(TRIM_SOURCES
    src/xpsnr_score.c
    src/vmaf_score.c
    # The photo half (milestone 7). C++ because SSIMULACRA 2 and jpegli are; the ABI they
    # sit behind is still plain C.
    src/ssim2_score.cc
    src/jpegli_codec.cc
    src/jxl_recompress.cc
    # SSIMULACRA 2 itself. Upstream ships it as a tool source rather than in the library,
    # so it is compiled here from the submodule rather than copied into src/.
    libjxl/tools/ssimulacra2.cc
    # `jpegxl::tools::NoMemoryManager()`, which both ssimulacra2.cc and our ssim2_score.cc
    # call through `tools/no_memory_manager.h`. The header only declares it; the definition
    # is a tools source like ssimulacra2.cc, and belongs to a tools *library* this build
    # does not link. Including the header and not the source links cleanly right up to the
    # final executable and then fails with five undefined references.
    libjxl/tools/no_memory_manager.cc
)

set(TRIM_NATIVE_LIBS
    vmaf
    # jxl_gauss_blur is what tools/ssimulacra2.cc needs; jxl-internal and jxl carry the
    # decoder used to load pixels and the encoder used for lossless JPEG recompression.
    jxl_gauss_blur
    jxl_extras-internal
    jxl-internal
    jxl
    jpegli-static
    trim_oxipng
    m
)

if(ANDROID)
    list(APPEND TRIM_SOURCES jni/trim_native_jni.c)
    add_library(${TRIM_TARGET_NAME} SHARED ${TRIM_SOURCES})
    set_target_properties(${TRIM_TARGET_NAME} PROPERTIES LINKER_LANGUAGE CXX)
    target_link_libraries(${TRIM_TARGET_NAME} PRIVATE ${TRIM_NATIVE_LIBS} log)
else()
    add_library(${TRIM_TARGET_NAME} STATIC ${TRIM_SOURCES})
    set_target_properties(${TRIM_TARGET_NAME} PROPERTIES LINKER_LANGUAGE CXX)
    target_link_libraries(${TRIM_TARGET_NAME} PRIVATE ${TRIM_NATIVE_LIBS})
endif()

target_include_directories(${TRIM_TARGET_NAME}
    PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}
    PRIVATE
        ${VMAF_SOURCE_DIR}/include
        ${CMAKE_CURRENT_SOURCE_DIR}/libjxl
        ${CMAKE_CURRENT_SOURCE_DIR}/libjxl/lib/include
        ${CMAKE_CURRENT_BINARY_DIR}/libjxl/lib/include
        # The repository root, not just `lib/`: jpegli's own public headers include each
        # other by repo-relative path — `lib/jpegli/decode.h` opens with
        # `#include "lib/jpegli/common.h"` — so `lib/` on the path resolves the first
        # header and none of the ones it pulls in. Same shape as libjxl above.
        ${CMAKE_CURRENT_SOURCE_DIR}/jpegli
        ${CMAKE_CURRENT_SOURCE_DIR}/jpegli/lib
        ${CMAKE_CURRENT_SOURCE_DIR}/jpegli/lib/include
        ${CMAKE_CURRENT_BINARY_DIR}/jpegli/lib/include/jpegli
)
add_dependencies(${TRIM_TARGET_NAME} vmaf_build oxipng_build)

# --- verification ------------------------------------------------------------
#
# The metrics are checked against upstream, not against themselves: a metric that is fast
# and wrong silently ruins every replace decision. `shared/native/test` holds the golden
# values and the script that regenerates them.
option(TRIM_NATIVE_TESTS "Build the metric verification tests" OFF)
if(TRIM_NATIVE_TESTS)
    enable_testing()
    add_subdirectory(test)
endif()
