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

# --- trim_native -------------------------------------------------------------
#
# XPSNR is compiled from src/xpsnr_score.c, a standalone extraction of the algorithm in
# the xpsnr submodule. Upstream ships it only as an FFmpeg filter, and pulling FFmpeg in
# to reach one function is not a trade worth making — see PROJECT.md.
set(TRIM_SOURCES
    src/xpsnr_score.c
    src/vmaf_score.c
    src/photo_stubs.c
)

if(ANDROID)
    list(APPEND TRIM_SOURCES jni/trim_native_jni.c)
    add_library(${TRIM_TARGET_NAME} SHARED ${TRIM_SOURCES})
    set_target_properties(${TRIM_TARGET_NAME} PROPERTIES LINKER_LANGUAGE CXX)
    target_link_libraries(${TRIM_TARGET_NAME} PRIVATE vmaf log m)
else()
    add_library(${TRIM_TARGET_NAME} STATIC ${TRIM_SOURCES})
    target_link_libraries(${TRIM_TARGET_NAME} PRIVATE vmaf m)
endif()

target_include_directories(${TRIM_TARGET_NAME}
    PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}
    PRIVATE ${VMAF_SOURCE_DIR}/include
)
add_dependencies(${TRIM_TARGET_NAME} vmaf_build)

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
