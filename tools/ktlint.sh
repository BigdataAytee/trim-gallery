#!/usr/bin/env bash
#
# ktlint, without Gradle and without the Android plugin.
#
# Why this exists: every module that has ktlint also has the Android plugin, so
# `./gradlew ktlintCheck` cannot configure in an environment that cannot reach Google
# Maven — which is the environment most of this project is written in. The one check that
# would catch a formatting slip in ten seconds was the one check that could only run in
# CI, and five ktlint round trips over two pull requests were the result: ten minutes each
# to be told about an import order.
#
# The CLI is a fat jar from Maven Central, which *is* reachable, and it reads the same
# `.editorconfig` the Gradle plugin does.
#
# Pinned to the version the Gradle plugin runs, so the two give the same answers. That is
# the entire value of this script: a local pass that CI then contradicts is worse than no
# local check, because it teaches you to stop believing the local one. `ktlintVersion` in
# the root build.gradle.kts pins the plugin to the same number, and the comment there
# points back here.
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION=1.5.0
SHA256=a27854622198800f50971a049dcdba38f2105d47e7e7258786c7d28045c5735d
URL="https://repo.maven.apache.org/maven2/com/pinterest/ktlint/ktlint-cli/${VERSION}/ktlint-cli-${VERSION}-all.jar"

# Outside the repository: a 50 MB jar is not source, and a downloaded executable inside a
# working tree is one `git add -A` away from being committed.
cache="${XDG_CACHE_HOME:-$HOME/.cache}/trim-gallery"
jar="$cache/ktlint-cli-$VERSION-all.jar"

verify() {
    [ -f "$jar" ] || return 1
    local actual
    actual=$(sha256sum "$jar" | cut -d' ' -f1)
    [ "$actual" = "$SHA256" ]
}

if ! verify; then
    mkdir -p "$cache"
    echo "downloading ktlint $VERSION"
    # To a temporary name, then moved: an interrupted download must not leave a truncated
    # jar that the next run treats as cached.
    if ! curl -sSL --fail "$URL" -o "$jar.part"; then
        rm -f "$jar.part"
        echo "could not download ktlint from $URL" >&2
        echo "(Maven Central is reachable from this environment; Google Maven is not.)" >&2
        exit 1
    fi
    mv "$jar.part" "$jar"
    if ! verify; then
        # Fail rather than run it: a jar that is not the one we pinned is a jar nobody
        # has reviewed, and this one is about to be executed.
        actual=$(sha256sum "$jar" | cut -d' ' -f1)
        rm -f "$jar"
        echo "checksum mismatch for ktlint $VERSION" >&2
        echo "  expected $SHA256" >&2
        echo "  actual   $actual" >&2
        exit 1
    fi
fi

# The same exclusions the Gradle plugin is given in build.gradle.kts: generated sources are
# not ours to format, and the parked prototype and the native submodules are not ours at
# all. Kept in step by hand, which is a real cost — but the alternative is no local check.
# `build-logic` is excluded because the Gradle plugin does not scan it: it is an included
# build rather than a subproject, and `subprojects { }` in the root build file never
# reaches it. Including it here produced 85 violations in code CI is perfectly happy with,
# which is the failure this script exists to prevent, pointed the other way — a local check
# that cries wolf gets ignored exactly as fast as one that misses things.
#
# That build-logic is unlinted at all is a real gap: it holds the three build guards and
# their 72 tests, which is the code that enforces this project's hard rules. Recorded in
# PROJECT.md as a follow-up rather than fixed here, because it is 85 formatting changes to
# files this change does not otherwise touch.
# `**/src/**/*.kt` alone misses every build script, and CI does not: `ktlintCheck`
# includes `runKtlintCheckOverKotlinScripts`, which lints `*.gradle.kts`. That gap let a
# stray blank line in androidApp/build.gradle.kts pass locally and fail CI — precisely the
# "a local check CI then contradicts" failure this script exists to prevent, so the
# patterns now cover both.
exec java -jar "$jar" \
    "**/src/**/*.kt" \
    "**/*.gradle.kts" \
    "!build-logic/**" \
    "!**/build/**" \
    "!design/**" \
    "!shared/native/**" \
    "!buyer-gallery-spec/**" \
    "$@"
