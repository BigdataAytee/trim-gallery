#!/usr/bin/env bash
# Points this repository's hooks at tools/git-hooks, which is version-controlled.
# .git/hooks is not, so a hook that lives only there protects exactly one clone.
set -euo pipefail
cd "$(dirname "$0")/.."
git config core.hooksPath tools/git-hooks
# Git skips a non-executable hook *silently*. They are committed 100755, but a
# clone that lost the mode would report "installed" and never fire — the failure
# mode this whole branch exists to remove.
chmod +x tools/git-hooks/*
echo "core.hooksPath -> tools/git-hooks"
echo "note: this replaces any hooks in .git/hooks for this repository."
echo "hooks active:"
ls -1 tools/git-hooks | sed 's/^/  /'
