#!/bin/sh
# Run by `wt new` in a fresh worktree: copy the untracked local SDK config.
MAIN=$(cd "$(dirname "$0")/.." && pwd)
cp "$MAIN/companion/local.properties" "$1/companion/" 2>/dev/null || true
