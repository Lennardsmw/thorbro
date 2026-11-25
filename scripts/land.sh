#!/bin/sh
# Brings a finished feature into main via its GitHub PR. Run inside the feature worktree, everything committed.
#   scripts/land.sh          (removes worktree + branch afterwards)      scripts/land.sh --keep
# rebase onto origin/main -> scripts/check.sh (APK build) -> push -> PR -> rebase-merge -> local main fast-forward.
set -eu
exec "$HOME/dev/tools/bin/wt" land "$@"
