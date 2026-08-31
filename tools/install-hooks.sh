#!/usr/bin/env bash
# Points this repository's hooks at tools/git-hooks, which is version-controlled.
# .git/hooks is not, so a hook that lives only there protects exactly one clone.
set -euo pipefail
cd "$(dirname "$0")/.."
git config core.hooksPath tools/git-hooks
echo "core.hooksPath -> tools/git-hooks"
echo "hooks active:"
ls -1 tools/git-hooks | sed 's/^/  /'
