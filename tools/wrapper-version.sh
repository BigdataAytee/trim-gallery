#!/usr/bin/env bash
#
# The one question every local check has to answer before it means anything:
# is the Gradle running this build the Gradle this repository pins?
#
# It exists because the answer was "no" for the whole of the AGP 9 upgrade. The
# local harness invoked the *system* `gradle` — 8.14.3 — while the wrapper had
# moved to 9.7.1, so every "local checks passed" was exercising the version the
# branch was upgrading away from. The mismatch was silent: both are real Gradle,
# both build, and nothing in the output names the version unless you look.
#
# Prints the pinned version on success. Exit 1 on any mismatch.

set -uo pipefail
cd "$(dirname "$0")/.."

props=gradle/wrapper/gradle-wrapper.properties
[ -f "$props" ] || { echo "guardrail: $props not found" >&2; exit 1; }

# distributionUrl=https\://services.gradle.org/distributions/gradle-9.7.1-bin.zip
pinned=$(sed -n 's/^distributionUrl=.*\/gradle-\([0-9][^-]*\)-\(bin\|all\)\.zip$/\1/p' "$props")
[ -n "$pinned" ] || {
    echo "guardrail: could not read a version out of distributionUrl in $props" >&2
    grep '^distributionUrl' "$props" >&2
    exit 1
}

[ -x ./gradlew ] || { echo "guardrail: ./gradlew is missing or not executable" >&2; exit 1; }

actual=$(./gradlew --version 2>/dev/null | sed -n 's/^Gradle \([0-9][^ ]*\)$/\1/p' | head -1)
[ -n "$actual" ] || { echo "guardrail: ./gradlew --version produced no version line" >&2; exit 1; }

if [ "$pinned" != "$actual" ]; then
    cat >&2 <<MSG
guardrail: Gradle version mismatch.

  pinned in $props : $pinned
  reported by ./gradlew          : $actual

The wrapper is the only Gradle this project is verified against. Do not work
around this by invoking a different gradle — fix the wrapper or the properties
file so the two agree.
MSG
    exit 1
fi

echo "$pinned"
