#!/bin/sh
# Starts work on a feature in its own worktree, so parallel chats never share a checkout.
#   scripts/feature.sh save-profiles   ->  ../thorbro-save-profiles on branch feature/save-profiles (from main)
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
. "$ROOT/scripts/lib.sh"
[ $# -eq 1 ] || die "Aufruf: scripts/feature.sh <name>"
SLUG=$(echo "$1" | tr 'A-Z' 'a-z' | sed 's/[^a-z0-9]\{1,\}/-/g; s/^-//; s/-$//')
BR="feature/$SLUG"
DIR="$(dirname "$MAIN_ROOT")/thorbro-$SLUG"
[ -e "$DIR" ] && die "$DIR gibt es schon"
git -C "$MAIN_ROOT" worktree add -q -b "$BR" "$DIR" main
cp "$MAIN_ROOT/companion/local.properties" "$DIR/companion/" 2>/dev/null || true
echo "Fertig: $DIR (Zweig $BR). Dort arbeiten, committen, dann scripts/land.sh."
