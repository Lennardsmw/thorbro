#!/bin/sh
# Builds ThorBro and publishes it as a GitHub release; the update button on the Thor picks it up.
#   scripts/release.sh     -> release vX.Y.Z from versionName in companion/app/build.gradle.kts
# Needs the GitHub CLI (gh) logged in. Bump versionCode + versionName first:
# the Thor only installs versions newer than its own.
set -eu
cd "$(dirname "$0")/.."
VER=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' companion/app/build.gradle.kts)
TAG="v$VER"

[ -z "$(git status --porcelain)" ] || { echo "Uncommitted changes - commit first" >&2; exit 1; }
if gh release view "$TAG" >/dev/null 2>&1; then echo "Release $TAG exists already - bump versionName" >&2; exit 1; fi
git push -q origin HEAD

(cd companion && JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}" ./gradlew assembleRelease -q)
OUT="companion/app/build/outputs/apk/release/ThorBro-$VER.apk"
cp companion/app/build/outputs/apk/release/app-release.apk "$OUT"

gh release create "$TAG" "$OUT" --title "ThorBro $VER" --notes "ThorBro $VER" --target "$(git rev-parse HEAD)"
echo "Published $TAG. The Thor installs it on its next check, or right away via 'Nach Updates suchen'."
