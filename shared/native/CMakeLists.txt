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
set(JPEGXL_ENABLE_TOOLS OFF CACHE BOOL "" FORCE)
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
add_subdirectory(jpegli EXCLUDE_FROM_ALL)

# --- oxipng ------------------------------------------------------------------
#
# Rust, behind a thin C-FFI crate. cargo-ndk for Android, plain cargo with the iOS target
# for Apple; the toolchain is pinned in oxipng/rust-toolchain.toml so CI and dev machines
# produce the same binary.
find_program(CARGO_EXECUTABLE cargo REQUIRED)

if(ANDROID)
    set(TRIM_RUST_TARGET aarch64-linux-android)
    set(TRIM_CARGO_COMMAND ${CARGO_EXECUTABLE} ndk -t arm64-v8a -o ${CMAKE_CURRENT_BINARY_DIR}/rust build --release)
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
