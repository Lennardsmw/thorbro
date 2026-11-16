#!/bin/sh
# Brings a finished feature into main. Run inside the feature's worktree, with everything committed.
#   scripts/land.sh            (keeps the worktree)      scripts/land.sh --remove   (deletes it afterwards)
# Steps: rebase onto the current main -> build -> fast-forward main. Conflicts stop at the rebase;
# fix them, `git rebase --continue`, run land.sh again.
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
. "$ROOT/scripts/lib.sh"
[ "$BRANCH" != main ] || die "Das hier ist main – land.sh läuft im Feature-Worktree"
[ -z "$(git -C "$ROOT" status --porcelain)" ] || die "Erst committen"
[ "$(git -C "$MAIN_ROOT" rev-parse --abbrev-ref HEAD)" = main ] || die "Der Haupt-Checkout $MAIN_ROOT steht nicht auf main"
[ -z "$(git -C "$MAIN_ROOT" status --porcelain)" ] || die "Der Haupt-Checkout $MAIN_ROOT hat offene Änderungen"
git -C "$ROOT" rebase -q main
build_apk
git -C "$MAIN_ROOT" merge -q --ff-only "$BRANCH"
echo "$BRANCH ist jetzt in main ($(git -C "$MAIN_ROOT" log -1 --format='%h %s'))."
if [ "${1:-}" = "--remove" ]; then
    cd "$MAIN_ROOT" && git worktree remove "$ROOT" && git branch -q -d "$BRANCH" && echo "Worktree und Zweig entfernt."
fi
