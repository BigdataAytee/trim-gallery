#!/usr/bin/env bash
#
# Every library `libtrim_native.so` names must be in the APK, or on the device already.
#
# An APK that builds and is missing a library it loads is not a build failure — it is a
# crash the first time a night pass touches a photo, on a user's device, from a run that
# went green. So the property is checked rather than assumed.
#
# The check reads the answer out of the ELF instead of comparing against a list somebody
# maintained: `readelf -d` gives the DT_NEEDED entries the loader will actually resolve,
# so it stays correct when the link line changes. A name is satisfied if it is packaged in
# the APK for the same ABI, or if it is part of the Android NDK's stable public ABI.
#
# Usage: tools/check-apk-libraries.sh <apk> [readelf]
set -uo pipefail

apk="${1:?usage: check-apk-libraries.sh <apk> [readelf]}"
readelf_bin="${2:-readelf}"

# The libraries every Android device provides. Deliberately short: this is the NDK's
# documented stable ABI, not "whatever happened to be on the emulator". Anything outside
# it has to ship in the APK.
platform_libs="libc.so libm.so libdl.so liblog.so libz.so libandroid.so libjnigraphics.so \
libGLESv2.so libGLESv3.so libEGL.so libOpenSLES.so libmediandk.so libnativewindow.so \
libaaudio.so libcamera2ndk.so libc++_shared.so"

command -v unzip >/dev/null || { echo "missing: unzip" >&2; exit 2; }
[ -f "$apk" ] || { echo "no such APK: $apk" >&2; exit 2; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# `unzip -Z1` writes "Empty zipfile." to *stdout*, not stderr, so an unfiltered read of
# its output treats that sentence as two filenames and the check passes an APK with no
# libraries at all. Found by the self-test. Keep only real entries.
packaged="$(unzip -Z1 "$apk" 'lib/*/*.so' 2>/dev/null | grep -E '^lib/[^/]+/.+\.so$' || true)"
if [ -z "$packaged" ]; then
    echo "::error::$apk contains no native libraries under lib/"
    exit 1
fi

echo "Packaged native libraries:"
echo "$packaged" | sed 's/^/  /'

abis="$(echo "$packaged" | cut -d/ -f2 | sort -u)"
status=0

for abi in $abis; do
    echo
    echo "ABI $abi:"
    in_apk="$(echo "$packaged" | grep "^lib/$abi/" | xargs -n1 basename)"
    for entry in $in_apk; do
        unzip -o -q "$apk" "lib/$abi/$entry" -d "$work" || continue
        so="$work/lib/$abi/$entry"
        # A stripped or unreadable object is not a pass: say so rather than skipping.
        needed="$("$readelf_bin" -d "$so" 2>/dev/null \
            | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p')"
        if [ -z "$needed" ] && ! "$readelf_bin" -d "$so" >/dev/null 2>&1; then
            echo "::error::could not read the dynamic section of lib/$abi/$entry"
            status=1
            continue
        fi
        for dep in $needed; do
            if echo "$in_apk" | grep -qx "$dep"; then
                echo "  ok        $entry -> $dep (in the APK)"
            elif echo "$platform_libs" | tr ' ' '\n' | grep -qx "$dep"; then
                echo "  ok        $entry -> $dep (platform)"
            else
                echo "::error::$entry needs $dep, which is neither in the APK nor part of the NDK's stable ABI"
                status=1
            fi
        done
    done
done

echo
if [ "$status" -eq 0 ]; then
    echo "Every library the APK loads is either packaged or provided by the platform."
else
    echo "The APK is missing at least one library it loads."
fi
exit "$status"
