#!/usr/bin/env bash
#
# The pre-push scope hook, tested against the failure that motivated it and
# against the cases it must not block. A guard with no planted violation is a
# guard nobody has ever seen work.
set -uo pipefail
cd "$(dirname "$0")/.."
root=$PWD
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
        git update-ref refs/remotes/origin/main main
        git checkout -qb claude/scoped
        mkdir -p .github/pr-scope/claude
        printf 'androidApp/**\ntools/*.sh\n' > .github/pr-scope/claude/scoped.txt
        for f in "$@"; do mkdir -p "$(dirname "$f")"; echo x >> "$f"; done
        git add -A; git commit -qm change
        cp "$hook" .git/hooks/pre-push
        # Feed the hook a real pre-push stdin line, as git does.
        printf 'refs/heads/%s %s refs/heads/%s %s\n' \
            "claude/scoped" "$(git rev-parse HEAD)" "claude/scoped" "$(git rev-parse main)" \
            | bash .git/hooks/pre-push >/dev/null 2>&1
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
check "the scope file alone is allowed"                0
check "a single stray file is caught"                  1 \
      androidApp/build.gradle.kts shared/core/model/build.gradle.kts
check "* does not cross a slash (tools/*.sh vs tools/sub/x.sh)" 1 \
      tools/sub/x.sh
check "** does cross a slash (androidApp/**)"          0 \
      androidApp/a/b/c/Deep.kt


commit_check() { # name, expected, branch, where(primary|worktree)
    local name=$1 expect=$2 branch=$3 where=$4
    rm -rf "$tmp/c"; mkdir -p "$tmp/c"
    (
        cd "$tmp/c"
        git init -q -b main
        git config user.email t@t; git config user.name t
        echo seed > seed.txt; git add -A; git commit -qm seed
        mkdir -p .git/hooks; cp "$hookdir/pre-commit" .git/hooks/pre-commit
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
echo "branch.sh:"
rm -rf "$tmp/b"; mkdir -p "$tmp/b"
(
    cd "$tmp/b"
    git init -q -b main src && cd src
    git config user.email t@t; git config user.name t
    mkdir -p tools; cp "$PWD/../../../tools/branch.sh" tools/ 2>/dev/null || cp "$root/tools/branch.sh" tools/
    echo x > f.txt; git add -A; git commit -qm seed
    git remote add origin .; git update-ref refs/remotes/origin/main main
    TRIM_WORKTREES="$tmp/b/wts" bash tools/branch.sh claude/slashed 'androidApp/**' >/dev/null 2>&1
    echo $?
    [ -f "$tmp/b/wts/claude-slashed/.github/pr-scope/claude/slashed.txt" ] && echo yes || echo no
) > "$tmp/bout"
br_exit=$(sed -n 1p "$tmp/bout"); br_file=$(sed -n 2p "$tmp/bout")
if [ "$br_exit" = "0" ] && [ "$br_file" = "yes" ]; then
    echo "  ok    a slashed branch name gets its scope file"; pass=$((pass+1))
else
    echo "  FAIL  a slashed branch name gets its scope file (exit=$br_exit file=$br_file)"; fail=$((fail+1))
fi

echo
echo "pre-commit primary-checkout hook:"
commit_check "main in the primary checkout is fine"      0 main    primary
commit_check "a branch in the primary checkout is not"   1 feature primary
commit_check "a branch in a linked worktree is fine"     0 feature worktree

echo
echo "$pass passed, $fail failed"
[ "$fail" = "0" ]
