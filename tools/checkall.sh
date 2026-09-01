#!/usr/bin/env bash
#
# Every check this repository can run locally, through the wrapper and nothing else.
#
# Two rules, both learned the hard way:
#
#   1. `./gradlew` only. An earlier version of this harness called the *system*
#      `gradle`, which was a different version from the one the wrapper pinned. Every
#      "local checks passed" it printed during the AGP 9 upgrade was testing the
#      version being upgraded away from. tools/wrapper-version.sh now refuses to
#      proceed unless the two agree, and this script has no way to invoke anything
#      else.
#
#   2. Configuration counts as a check. Four faults have reached CI that this harness
#      could not see, because it ran the build's *tasks* against reconstructed copies
#      of the sources rather than configuring the build's own scripts: an ABI split, an
#      eager tasks.named, a top-level `const val`, and a plugin AGP 9 rejects. Every
#      one of them failed at configuration time and took all six CI jobs down. So the
#      first real task here is `help`, which does nothing except make Gradle configure
#      every project in the build.
#
# Not covered here, deliberately: `assembleDebug` and the real
# tools/check-apk-libraries.sh run against a built APK. An assemble is expensive
# and CI does it (build.yml). The self-test below proves the checker itself
# works; it does not prove this tree's APK is clean. Saying so beats letting
# "every check" quietly mean "every cheap check".
#
# Needs Google Maven (dl.google.com) for anything that configures an Android module.
# Where that is unreachable, this script fails loudly rather than skipping quietly —
# a harness that silently covers less than you think is worse than no harness.

set -uo pipefail
cd "$(dirname "$0")/.."
fail=0

echo "=== gradle wrapper version ==="
version=$(bash tools/wrapper-version.sh) || exit 1
echo "wrapper and gradle-wrapper.properties agree on Gradle $version"

run() {
    local name=$1; shift
    echo
    echo "=== $name ==="
    if ./gradlew "$@" --console=plain; then
        echo "-- $name OK"
    else
        echo "-- $name FAILED"
        fail=1
    fi
}

# Configuration first: this is the class of fault the old harness was blind to.
# Before Gradle, because it needs neither Gradle nor the Android SDK and finishes in eight
# seconds. Five ktlint failures reached CI over two pull requests while this check was
# reachable only through `./gradlew`, which cannot configure without Google Maven.
echo "=== ktlint (standalone) ==="
if bash tools/ktlint.sh; then
    echo "no formatting violations"
else
    fail=1
fi

run "configure every project"  help
run "shared JVM tests"         sharedTest
run "build guards"             guards
run "ktlint + detekt"          ktlintCheck detekt
run "compile androidApp"       :androidApp:compileDebugKotlin
# build-logic is an included build (settings.gradle.kts: includeBuild), so its
# tests are reached with -p, not with a :build-logic: task path.
run "guard self-tests"         -p build-logic test

# The shell-level self-tests. A check nobody runs is a check that does not exist,
# which is this file's whole argument — so the harness runs its own hook tests
# rather than leaving them as something someone ran once by hand.
# iosCompile needs a Mac. Say so out loud when skipping: this file's own argument
# is that a harness covering less than its wording promises is the failure mode.
if [ "$(uname)" = Darwin ]; then
    run "iOS cross-compile"    iosCompile
else
    echo
    echo "=== iOS cross-compile ==="
    echo "-- SKIPPED: needs macOS. CI covers it (build.yml, iosCompile); this run does not."
fi

# `bash "$f"`, not `./"$f"`: the executable bit is exactly what install-hooks.sh
# resets because it is not to be trusted on every clone. Same call as build.yml.
# Exit 2 is these scripts' "I cannot run here" (check-apk-libraries-selftest.sh
# needs cc/zip/unzip/readelf, and its Mach-O output on macOS has no DT_NEEDED to
# read). Collapsing that into failure would make the harness's green state
# unreachable on a Mac — and an unreachable green is how a harness gets ignored,
# which is the disease this file replaced.
for selftest in tools/git-hooks-selftest.sh tools/check-apk-libraries-selftest.sh; do
    echo
    echo "=== $selftest ==="
    bash "$selftest"; rc=$?
    case $rc in
        0) echo "-- $selftest OK" ;;
        2) echo "-- $selftest SKIPPED (toolchain not available here; CI runs it)" ;;
        *) echo "-- $selftest FAILED"; fail=1 ;;
    esac
done

echo
if [ "$fail" = "0" ]; then
    echo "ALL LOCAL CHECKS PASSED (Gradle $version, via ./gradlew)"
else
    echo "SOMETHING FAILED — see above"
fi
exit "$fail"
