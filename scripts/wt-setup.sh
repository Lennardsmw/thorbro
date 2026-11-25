#!/bin/sh
# Run by `wt new` in a fresh worktree: copy the untracked local SDK config.
MAIN=$(git -C "$1" worktree list --porcelain | sed -n "1s/^worktree //p")
cp "$MAIN/companion/local.properties" "$1/companion/" 2>/dev/null || true
