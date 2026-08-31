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

# Isolate the fixtures from whoever is running them. A global `core.hooksPath`
# (or an init.templateDir carrying hooks) would make the fixture's own commits
# run this repo's pre-commit, which rejects a non-main branch in a primary
# checkout — and the case would then measure a hook against a commit that never
# happened.
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null
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
        printf 'androidApp/**  \ntools/*.sh\n' > .github/pr-scope/claude/scoped.txt
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
check "a trailing space on a pattern still matches"    0 \
      androidApp/Trailing.kt


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

# The case the previous fixture could not express. Every other case pushes the
# branch that is already checked out, so the HEAD fallback and the stdin path
# give the same answer — which is exactly why a broken stdin path (no timeout(1)
# on macOS) and a scope file read from the wrong worktree both went unnoticed.
# A `git mv` out of shared/ reports only the destination unless --no-renames is
# used, so a branch scoped to androidApp/** would pass while deleting a file in
# the directory ARCHITECTURE.md guards hardest.
rename_check() {
    rm -rf "$tmp/r2"; mkdir -p "$tmp/r2"
    (
        cd "$tmp/r2"
        git init -q -b main
        git config user.email t@t; git config user.name t
        mkdir -p shared/core/pipeline .github/pr-scope/claude androidApp
        echo x > shared/core/pipeline/Foo.kt
        printf 'androidApp/**\n' > .github/pr-scope/claude/scoped.txt
        git add -A; git commit -qm seed; git update-ref refs/remotes/origin/main main
        git checkout -qb claude/scoped
        git mv shared/core/pipeline/Foo.kt androidApp/Foo.kt
        git commit -qm move
        mkdir -p .git/hooks; cp "$hookdir/pre-push" .git/hooks/; chmod +x .git/hooks/pre-push
        printf 'refs/heads/claude/scoped %s refs/heads/claude/scoped %s\n' \
            "$(git rev-parse HEAD)" "$(git rev-parse main)" \
            | bash .git/hooks/pre-push >/dev/null 2>&1
        echo $?
    ) > "$tmp/r2out"
    local got; got=$(cat "$tmp/r2out")
    if [ "$got" = "1" ]; then
        echo "  ok    a rename out of shared/ is caught, not hidden by rename detection"; pass=$((pass+1))
    else
        echo "  FAIL  a rename out of shared/ is caught (expected 1, got $got)"; fail=$((fail+1))
    fi
}
echo
echo "rename detection:"
rename_check

echo
echo "pushing a branch that is not checked out:"
rm -rf "$tmp/x"; mkdir -p "$tmp/x"
(
    cd "$tmp/x"
    git init -q -b main
    git config user.email t@t; git config user.name t
    echo seed > seed.txt; git add -A; git commit -qm seed
    git update-ref refs/remotes/origin/main main

    # other-branch carries a scope file and a file outside it; it is committed,
    # then we return to main so it is NOT the checked-out branch.
    git checkout -q -b claude/other
    mkdir -p .github/pr-scope/claude androidApp shared/core/model
    printf 'androidApp/**\n' > .github/pr-scope/claude/other.txt
    echo x > androidApp/ok.kt
    echo x > shared/core/model/stray.kt
    git add -A; git commit -qm change
    other_sha=$(git rev-parse HEAD)
    git checkout -q main

    mkdir -p .git/hooks; cp "$hookdir/pre-push" .git/hooks/; chmod +x .git/hooks/pre-push
    printf 'refs/heads/claude/other %s refs/heads/claude/other %s\n' "$other_sha" "$(git rev-parse main)" \
        | bash .git/hooks/pre-push >/dev/null 2>&1
    echo $?
) > "$tmp/xout"
x_exit=$(cat "$tmp/xout")
if [ "$x_exit" = "1" ]; then
    echo "  ok    an out-of-scope file is caught on a branch that is not HEAD"; pass=$((pass+1))
else
    echo "  FAIL  an out-of-scope file is caught on a branch that is not HEAD (expected 1, got $x_exit)"; fail=$((fail+1))
fi

echo
echo "branch.sh:"
rm -rf "$tmp/b"; mkdir -p "$tmp/b"
(
    cd "$tmp/b"
    git init -q -b main src && cd src
    git config user.email t@t; git config user.name t
    mkdir -p tools; cp "$root/tools/branch.sh" tools/
    echo x > f.txt; git add -A; git commit -qm seed
    git remote add origin .; git update-ref refs/remotes/origin/main main
    TRIM_WORKTREES="$tmp/b/wts" bash tools/branch.sh claude/slashed 'androidApp/**' >/dev/null 2>&1
    echo $?
    # `cat-file -e HEAD:...`, not `-f` on disk: presence on disk is exactly the
    # property that stopped being sufficient when pre-push moved to reading the
    # scope out of the pushed commit.
    git -C "$tmp/b/wts/claude-slashed" cat-file -e "HEAD:.github/pr-scope/claude/slashed.txt" 2>/dev/null \
        && echo yes || echo no
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
