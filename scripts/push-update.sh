#!/bin/sh
# Builds THIS worktree and installs it on the Thor over Wi-Fi (through ThorBro itself, no USB).
#   scripts/push-update.sh              FORCE=1 scripts/push-update.sh   (take the Thor from another branch)
# Several chats share one Thor: if a different branch was installed in the last 30 minutes this stops,
# so nobody overwrites someone else's test. The Thor shows the installed build as "<version>-<branch>+<commit>".
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
. "$ROOT/scripts/lib.sh"
[ -n "$CODE" ] || die "Kopplungscode fehlt: THORBRO_CODE setzen oder in ~/.config/thorbro/code schreiben"
check_lock
build_apk
echo "Lade $(du -h "$APK" | cut -f1) ($BRANCH) auf $THOR ..."
curl -sS --fail-with-body -X POST -H "Authorization: Bearer $CODE" \
  -H "Content-Type: application/vnd.android.package-archive" --data-binary "@$APK" "http://$THOR:7330/api/self-update"
echo
write_lock
echo "Auf dem Thor bestätigen, falls Android fragt. ThorBro startet danach neu."
