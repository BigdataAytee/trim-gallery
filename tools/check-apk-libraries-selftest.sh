#!/usr/bin/env bash
#
# The check in check-apk-libraries.sh guards a property nothing else in this repository
# can see, so it needs a planted violation of its own — a check that cannot fail is not a
# check. Three cases, built as real ELF objects and real zips rather than as fixtures:
#
#   1. a library whose DT_NEEDED is satisfied inside the APK          -> must pass
#   2. the same APK with that dependency removed                      -> must fail
#   3. an APK with no native libraries at all                         -> must fail
#
# It runs against the host toolchain, because DT_NEEDED is DT_NEEDED whatever the target.
set -uo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
check="$root/tools/check-apk-libraries.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

for tool in cc zip unzip readelf; do
    command -v "$tool" >/dev/null || { echo "missing: $tool" >&2; exit 2; }
done

cd "$work"
echo 'int helper(void) { return 7; }' > helper.c
echo 'int helper(void); int entry(void) { return helper(); }' > main.c
# -nostdlib keeps the host's libc off the DT_NEEDED list: the check knows Android's
# stable ABI, not glibc's, and the point here is the dependency between these two objects.
cc -shared -fPIC -nostdlib -o libhelper.so helper.c
# -Wl,--no-as-needed so the dependency is recorded even though only one symbol is used.
cc -shared -fPIC -nostdlib -o libmain.so main.c -L. -Wl,--no-as-needed -lhelper

mkdir -p lib/arm64-v8a
cp libmain.so libhelper.so lib/arm64-v8a/
zip -q -r complete.apk lib
rm lib/arm64-v8a/libhelper.so
zip -q -r missing.apk lib
mkdir -p empty && (cd empty && zip -q -r ../nolibs.apk . -i '*' 2>/dev/null || zip -q ../nolibs.apk /dev/null)

fail=0
expect() { # expect <name> <wanted-exit> <apk>
    bash "$check" "$3" >"$work/$1.log" 2>&1
    got=$?
    if [ "$got" -eq "$2" ]; then
        echo "  ok        $1 (exit $got)"
    else
        echo "  FAILED    $1: wanted exit $2, got $got"
        sed 's/^/            /' "$work/$1.log"
        fail=1
    fi
}

echo "check-apk-libraries.sh self-test:"
expect "a complete APK passes"            0 "$work/complete.apk"
expect "a missing dependency fails"       1 "$work/missing.apk"
expect "an APK with no libraries fails"   1 "$work/nolibs.apk"

# The failure has to name the missing library, or the annotation is useless to whoever
# reads it at 2am.
if grep -q "libhelper.so" "$work/a missing dependency fails.log"; then
    echo "  ok        the failure names the missing library"
else
    echo "  FAILED    the failure does not name libhelper.so"
    fail=1
fi

[ "$fail" -eq 0 ] && echo "check-apk-libraries.sh guards what it claims to guard."
exit "$fail"
