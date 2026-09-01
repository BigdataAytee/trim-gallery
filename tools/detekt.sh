#!/usr/bin/env bash
#
# detekt, without Gradle and without the Android plugin.
#
# The same reason `tools/ktlint.sh` exists: every module that runs detekt also has the
# Android plugin, so `./gradlew detekt` cannot configure in an environment that cannot
# reach Google Maven — which is where most of this project is written. Detekt findings were
# therefore only ever discovered in CI, at ten minutes a round trip, and they arrived
# without their text: `.github/failure-summary.sh` cuts the log at `* Try:` and Gradle
# prints the findings *before* that, so a red run said "2 weighted issues" and nothing else.
#
# ## The flag that makes this honest
#
# `--build-upon-default-config` is not optional. `config/detekt/detekt.yml` holds only
# this project's deviations from the defaults, so without that flag the CLI runs *only the
# rules named in it* and reports a clean tree. That is worse than no local check: it is a
# local check that confidently disagrees with CI in the reassuring direction. It was tried,
# it said zero smells, and Gradle then failed on two.
#
# Pinned to the version the Gradle plugin runs (`detekt` in gradle/libs.versions.toml), so
# the two give the same answers. A local pass that CI contradicts teaches you to stop
# believing the local one.
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION=1.23.8
SHA256=3afe89a11120303c73c9bdda3d8fe558dd9070a6937d27819ddc04b275381245
URL="https://repo.maven.apache.org/maven2/io/gitlab/arturbosch/detekt/detekt-cli/${VERSION}/detekt-cli-${VERSION}-all.jar"

# Outside the repository: a 70 MB jar is not source, and a downloaded executable inside a
# working tree is one `git add -A` away from being committed.
cache="${XDG_CACHE_HOME:-$HOME/.cache}/trim-gallery"
jar="$cache/detekt-cli-$VERSION-all.jar"

if [[ ! -f "$jar" ]]; then
    mkdir -p "$cache"
    echo "Fetching detekt $VERSION..." >&2
    curl -sS -L -o "$jar.part" "$URL"
    echo "$SHA256  $jar.part" | sha256sum -c - >/dev/null
    mv "$jar.part" "$jar"
fi

# Every module's sources, which is what the per-module Gradle tasks cover between them.
#
# `build-logic` is excluded to match what CI actually runs: it is a separate included build
# and no `detekt` task covers it, so scanning it here would report five findings that no
# pipeline will ever fail on. That it is unchecked at all is a real gap — it holds the three
# build guards, which is the code that enforces this project's hard rules — and `ktlint.sh`
# records the same one. But a local check that reports problems CI does not is a check that
# gets ignored, and then the ones that matter get ignored with it.
inputs=$(find . -type d -name src -not -path "*/build/*" -not -path "./shared/native/*" \
    -not -path "./design/*" -not -path "./buyer-gallery-spec/*" \
    -not -path "./build-logic/*" | paste -sd,)

exec java -jar "$jar" \
    --build-upon-default-config \
    --config config/detekt/detekt.yml \
    --baseline config/detekt/baseline.xml \
    --input "$inputs" \
    "$@"
