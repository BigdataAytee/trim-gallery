#!/usr/bin/env bash
#
# The pre-push scope hook, tested against the failure that motivated it and
# against the cases it must not block. A guard with no planted violation is a
# guard nobody has ever seen work.
set -uo pipefail
cd "$(dirname "$0")/.."
hook="$PWD/tools/git-hooks/pre-push"
hookdir="$PWD/tools/git-hooks"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
pass=0; fail=0

check() { # name, expected(0|1), files...
    local name=$1 expect=$2; shift 2
    rm -rf "$tmp/r"; mkdir -p "$tmp/r"
    (
        cd "$tmp/r"
        git init -q -b main
        git config user.email t@t; git config user.name t
        mkdir -p .github/pr-scope tools/git-hooks
        echo seed > seed.txt; git add -A; git commit -qm seed
        git branch -f origin/main main 2>/dev/null
        git update-ref refs/remotes/origin/main main
        git checkout -qb scoped
        printf 'androidApp/**\ntools/*.sh\n' > .github/pr-scope/scoped.txt
        for f in "$@"; do mkdir -p "$(dirname "$f")"; echo x >> "$f"; done
        git add -A; git commit -qm change
        cp "$hook" .git/hooks/pre-push
        HOME=$tmp git rev-parse --abbrev-ref HEAD >/dev/null
        bash .git/hooks/pre-push >/dev/null 2>&1
        echo $?
    ) > "$tmp/out"
    local got; got=$(cat "$tmp/out")
    if [ "$got" = "$expect" ]; then
        echo "  ok    $name"; pass=$((pass+1))
    else
        echo "  FAIL  $name (expected exit $expect, got $got)"; fail=$((fail+1))
    fi
}

echo "pre-push scope hook:"
check "the real leak: a version bump on an ABI branch" 1 \
      androidApp/build.gradle.kts gradle/libs.versions.toml
check "in scope only"                                  0 \
      androidApp/build.gradle.kts tools/checkall.sh
check "docs are always allowed"                        0 \
      androidApp/build.gradle.kts PROJECT.md CHANGELOG.md
check "the scope file itself is allowed"               0 \
      androidApp/build.gradle.kts
check "a single stray file is caught"                  1 \
      androidApp/build.gradle.kts shared/core/model/build.gradle.kts


commit_check() { # name, expected, branch, where(primary|worktree)
    local name=$1 expect=$2 branch=$3 where=$4
    rm -rf "$tmp/c"; mkdir -p "$tmp/c"
    (
        cd "$tmp/c"
        git init -q -b main
        git config user.email t@t; git config user.name t
        echo seed > seed.txt; git add -A; git commit -qm seed
        mkdir -p .git/hooks; cp "$PWD/../../tools/git-hooks/pre-commit" .git/hooks/ 2>/dev/null || true
        cp "$hookdir/pre-commit" .git/hooks/pre-commit
        chmod +x .git/hooks/pre-commit
        if [ "$where" = worktree ]; then
            git worktree add -q -b "$branch" wt main
            cd wt
        elif [ "$branch" != main ]; then
            git checkout -qb "$branch"
        fi
        echo y >> f.txt; git add -A
        bash "$(git rev-parse --git-common-dir)/hooks/pre-commit" >/dev/null 2>&1
        echo $?
    ) > "$tmp/out2"
    local got; got=$(cat "$tmp/out2")
    if [ "$got" = "$expect" ]; then
        echo "  ok    $name"; pass=$((pass+1))
    else
        echo "  FAIL  $name (expected exit $expect, got $got)"; fail=$((fail+1))
    fi
}

echo
echo "pre-commit primary-checkout hook:"
commit_check "main in the primary checkout is fine"      0 main    primary
commit_check "a branch in the primary checkout is not"   1 feature primary
commit_check "a branch in a linked worktree is fine"     0 feature worktree

echo
echo "$pass passed, $fail failed"
[ "$fail" = "0" ]
