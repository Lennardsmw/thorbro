#!/bin/sh
# Starts work on a feature in its own worktree + branch + PR flow (see ~/dev/tools/README.md).
#   scripts/feature.sh save-profiles [feature|fix|chore]  ->  ~/dev/wt/thorbro/save-profiles, branch feature/save-profiles from origin/main
set -eu
[ $# -ge 1 ] || { echo "Aufruf: scripts/feature.sh <name> [feature|fix|chore]" >&2; exit 1; }
exec "$HOME/dev/tools/bin/wt" new thorbro "$1" "${2:-feature}"
