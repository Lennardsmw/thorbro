#!/bin/sh
# Publishes this worktree's test build as a GitHub pre-release "dev-<versionCode>" (asset ThorBro-dev.apk).
# Test builds ("<version>-<branch>+<commit>") update from there, so testers anywhere get them without a release;
# normal builds ignore dev-* tags. Only committed builds (no ".dirty"); the commit is pushed first.
#   scripts/publish-dev.sh            builds, then publishes
#   NOBUILD=1 scripts/publish-dev.sh  publishes the APK that is already built
# Keeps the newest KEEP (5) test pre-releases and deletes older ones.
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
. "$ROOT/scripts/lib.sh"
cd "$ROOT"
[ -z "$(git status --porcelain)" ] || die "Uncommitted changes – erst committen"
[ "${NOBUILD:-0}" = 1 ] || build_apk
[ -f "$APK" ] || die "Kein APK gebaut"
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
AAPT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)/aapt2
badge=$("$AAPT" dump badging "$APK" | head -1)
code=$(echo "$badge" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p")
name=$(echo "$badge" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")
[ -n "$code" ] && [ -n "$name" ] || die "Version im APK nicht lesbar"
case "$name" in *.dirty*) die "$name ist nicht committet" ;; *+*) ;; *) die "$name ist ein Release-Build: scripts/release.sh" ;; esac
git push -q origin "HEAD:refs/heads/$BRANCH"
tmp=$(mktemp -d)
cp "$APK" "$tmp/ThorBro-dev.apk"
gh release create "dev-$code" "$tmp/ThorBro-dev.apk" --prerelease --title "ThorBro $name" --target "$(git rev-parse HEAD)" \
  --notes "Test-Build vom Zweig \`$BRANCH\` ($(git rev-parse --short HEAD)). ThorBro-Test-Builds holen sich das als Update; normale Versionen ignorieren es."
rm -rf "$tmp"
KEEP=${KEEP:-5}
gh release list --limit 100 --json tagName -q '.[].tagName' | grep -E '^dev-[0-9]+$' | sort -t- -k2 -n -r | tail -n +$((KEEP + 1)) |
  while read -r t; do gh release delete "$t" --cleanup-tag -y >/dev/null && echo "altes $t entfernt"; done
echo "Test-Build: $name (dev-$code) auf GitHub"
