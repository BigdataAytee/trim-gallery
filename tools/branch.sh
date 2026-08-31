#!/usr/bin/env bash
#
# Starts a branch in its own worktree, cut from a freshly fetched origin/main.
#
# Why not `git checkout -b`: a checkout moves one working tree between branches,
# and carries any uncommitted edit with it. That happened here — a version bump
# in progress rode a checkout onto an unrelated ABI branch and was committed
# there. Nothing about `git checkout -b` warns you; the edits simply follow.
#
# A worktree per branch removes the mechanism rather than asking people to
# remember. Each branch has its own directory and its own uncommitted state, and
# there is no operation that moves work between them by accident.
#
# Usage: tools/branch.sh <branch-name> [scope-glob ...]
#
# Any globs given are written to .github/pr-scope/<branch>.txt, which the
# pre-push hook enforces. Declaring the scope when the branch is created is the
# point: it is the moment you actually know what the branch is for.

set -euo pipefail
cd "$(dirname "$0")/.."
root=$(git rev-parse --show-toplevel)

[ $# -ge 1 ] || { echo "usage: tools/branch.sh <branch-name> [scope-glob ...]" >&2; exit 2; }
branch=$1; shift

trees="${TRIM_WORKTREES:-$(dirname "$root")/trim-gallery-worktrees}"
dest="$trees/${branch//\//-}"
[ -e "$dest" ] && { echo "already exists: $dest" >&2; exit 1; }

echo "fetching origin/main"
for attempt in 1 2 3 4; do
    git fetch origin main && break
    echo "  fetch failed, retrying in $((2 ** attempt))s"; sleep $((2 ** attempt))
done

mkdir -p "$trees"
git worktree add -b "$branch" "$dest" origin/main

if [ $# -gt 0 ]; then
    mkdir -p "$dest/.github/pr-scope"
    {
        echo "# Files this branch is allowed to touch, enforced by tools/git-hooks/pre-push."
        echo "# PROJECT.md, CHANGELOG.md and this file are always allowed."
        for glob in "$@"; do echo "$glob"; done
    } > "$dest/.github/pr-scope/$branch.txt"
    echo "declared scope:"
    printf '  %s\n' "$@"
fi

echo
echo "worktree: $dest"
echo "cd $dest"
