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
# Needs Google Maven (dl.google.com) for anything that configures an Android module.
# Where that is unreachable, this script fails loudly rather than skipping quietly —
# a harness that silently covers less than you think is worse than no harness.

set -uo pipefail
cd "$(dirname "$0")/.."
fail=0

echo "=== gradle wrapper version ==="
version=$(tools/wrapper-version.sh) || exit 1
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
run "configure every project"  help
run "shared JVM tests"         sharedTest
run "build guards"             guards
run "ktlint + detekt"          ktlintCheck detekt
# build-logic is an included build (settings.gradle.kts: includeBuild), so its
# tests are reached with -p, not with a :build-logic: task path.
run "guard self-tests"         -p build-logic test

echo
if [ "$fail" = "0" ]; then
    echo "ALL LOCAL CHECKS PASSED (Gradle $version, via ./gradlew)"
else
    echo "SOMETHING FAILED — see above"
fi
exit "$fail"
