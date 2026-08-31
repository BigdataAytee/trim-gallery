#!/usr/bin/env bash
#
# Configure and build shared/native for the host.
#
# The Android and iOS builds of this tree need an NDK or a Mac, and CI is the only place
# either exists — but almost nothing that goes wrong in `shared/native/CMakeLists.txt` is
# specific to the target. Duplicate target names across two vendored copies of highway, a
# missing include directory, a header that moved upstream: every one of those fails the
# same way for the host, in seconds, against the same submodules.
#
# It is the difference between a four-minute CI round trip per error and a local one, and
# it is why milestone 7's CMake shipped with a collision nothing had ever executed.
#
# Needs: cmake, ninja, meson (libvmaf builds with it), cargo (oxipng is Rust), and the
# submodules checked out — `git submodule update --init --recursive`.
#
# Usage: tools/build-native-host.sh [build-dir]
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
build="${1:-$root/shared/native/build-host}"

for tool in cmake ninja meson cargo; do
    command -v "$tool" >/dev/null || { echo "missing: $tool" >&2; exit 1; }
done

if [ ! -f "$root/shared/native/libjxl/CMakeLists.txt" ]; then
    echo "submodules are not checked out — run: git submodule update --init --recursive" >&2
    exit 1
fi

# `TRIM_NATIVE_TESTS=ON` is not about running the tests here — it is about `test_metrics`,
# which is the only target in this tree that *links* trim_native into an executable. On the
# host trim_native is a STATIC library, and archiving never resolves a symbol or looks for a
# dependency, so building it alone proved only that every file compiled. That is exactly how
# `-ljxl_extras-internal` — a target libjxl does not define unless JPEGXL_ENABLE_TOOLS is on
# — reached CI and failed at edge 225 of 225, after a full cross-compile. Linking here makes
# the same mistake fail locally in seconds.
cmake -S "$root/shared/native" -B "$build" -GNinja -DCMAKE_BUILD_TYPE=Release \
    -DTRIM_NATIVE_TESTS=ON
cmake --build "$build" --target trim_native test_metrics "-j$(nproc 2>/dev/null || echo 4)"

echo
echo "OK — shared/native configures, builds and links for the host."
echo "This does not prove the arm64 or iOS build: no NDK here, and no Mac."
