#!/bin/sh
# Builds ThorBro and publishes it as a GitHub release; the update button on the Thor picks it up.
#   scripts/release.sh 0.24.0   -> writes VERSION, commits it and publishes release v0.24.0
#   scripts/release.sh          -> publishes the version that is in VERSION
# Only on main (features land there with scripts/land.sh first). Needs the GitHub CLI (gh) logged in.
set -eu
cd "$(dirname "$0")/.."
[ "$(git rev-parse --abbrev-ref HEAD)" = main ] || { echo "Releases nur von main" >&2; exit 1; }
if [ $# -ge 1 ]; then echo "$1" > VERSION; git add VERSION; git commit -q -m "Release $1"; fi
VER=$(cat VERSION)
TAG="v$VER"

[ -z "$(git status --porcelain)" ] || { echo "Uncommitted changes - commit first" >&2; exit 1; }
if gh release view "$TAG" >/dev/null 2>&1; then echo "Release $TAG exists already - scripts/release.sh <new version>" >&2; exit 1; fi
git push -q origin HEAD

(cd companion && JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}" ./gradlew assembleRelease -q)
OUT="companion/app/build/outputs/apk/release/ThorBro-$VER.apk"
cp companion/app/build/outputs/apk/release/app-release.apk "$OUT"

gh release create "$TAG" "$OUT" --title "ThorBro $VER" --notes "ThorBro $VER" --target "$(git rev-parse HEAD)"
echo "Published $TAG. The Thor installs it on its next check, or right away via 'Nach Updates suchen'."
