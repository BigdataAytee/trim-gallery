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

cmake -S "$root/shared/native" -B "$build" -GNinja -DCMAKE_BUILD_TYPE=Release
cmake --build "$build" --target trim_native "-j$(nproc 2>/dev/null || echo 4)"

echo
echo "OK — shared/native configures and builds for the host."
echo "This does not prove the arm64 or iOS build: no NDK here, and no Mac."
