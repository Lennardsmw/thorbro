#!/bin/sh
# Puts this worktree's build into the dev update channel (LXC 107, http://192.168.178.97:8097/feed.json).
# Test builds ("<version>-<branch>+<commit>") look there for updates instead of GitHub, so phones and other
# Thors at home get what is being tested without a release.
#   scripts/publish-dev.sh            builds, then publishes
#   NOBUILD=1 scripts/publish-dev.sh  publishes the APK that is already built (push-update.sh does this)
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
. "$ROOT/scripts/lib.sh"
[ "${NOBUILD:-0}" = 1 ] || build_apk
[ -f "$APK" ] || die "Kein APK gebaut"
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
AAPT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)/aapt2
badge=$("$AAPT" dump badging "$APK" | head -1)
code=$(echo "$badge" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p")
name=$(echo "$badge" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")
[ -n "$code" ] && [ -n "$name" ] || die "Version im APK nicht lesbar"
case "$name" in *+*) ;; *) die "$name ist ein Release-Build: der gehört auf GitHub (scripts/release.sh), nicht in den Dev-Kanal" ;; esac
tmp=$(mktemp -d)
cp "$APK" "$tmp/thorbro.apk"
printf '{"versionName":"%s","versionCode":%s,"url":"http://192.168.178.97:8097/thorbro.apk","branch":"%s","published":"%s"}\n' \
  "$name" "$code" "$BRANCH" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$tmp/feed.json"
scp -q "$tmp/thorbro.apk" "$tmp/feed.json" g6:/tmp/ || die "g6 nicht erreichbar"
# APK first, feed last: a device never sees a feed whose APK is not there yet
ssh g6 'pct push 107 /tmp/thorbro.apk /opt/thorbro-dev/www/thorbro.apk.new && pct exec 107 -- mv /opt/thorbro-dev/www/thorbro.apk.new /opt/thorbro-dev/www/thorbro.apk &&
  pct push 107 /tmp/feed.json /opt/thorbro-dev/www/feed.json && rm -f /tmp/thorbro.apk /tmp/feed.json' || die "Hochladen fehlgeschlagen"
rm -rf "$tmp"
echo "Dev-Kanal: $name ($code)"
