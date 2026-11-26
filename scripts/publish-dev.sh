#!/bin/sh
# Publishes this worktree's test build to the dev channels the Dev dialog lists (protocol: Ordnung doc "Dev-Versionen"):
#  - GitHub pre-release "dev-<versionCode>" (asset ThorBro-dev.apk) with a machine-readable
#    "<!-- dev: {...} -->" line + the features as bullets in the notes
#  - home network (LXC 107, nginx :8097): /opt/thorbro-dev/www/thorbro/thorbro-<code>.apk + index.json (newest first, max 10)
# Features = commit subjects of this branch since origin/main (no merges, max 15).
# Test builds ("<version>-<branch>+<commit>") auto-update from GitHub; normal builds ignore dev-* tags.
# Only committed builds (no ".dirty"); the commit is pushed first.
#   scripts/publish-dev.sh             builds, then publishes to both
#   NOBUILD=1 scripts/publish-dev.sh   publishes the APK that is already built
#   HOMENET=0 scripts/publish-dev.sh   GitHub only (home network not reachable: it only warns anyway)
#   GITHUB=0 scripts/publish-dev.sh    home network only
# Keeps the newest KEEP (5) GitHub pre-releases and the newest 10 home builds.
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
commit=$(git rev-parse --short HEAD)
git fetch -q origin main || true
git push -q origin "HEAD:refs/heads/$BRANCH"

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
git log --no-merges --format=%s origin/main..HEAD | head -15 > "$tmp/features"
# the shared entry: {versionName, versionCode, branch, commit, published, features}
python3 - "$tmp/features" "$name" "$code" "$BRANCH" "$commit" > "$tmp/entry.json" <<'PY'
import json, sys, datetime
f, name, code, branch, commit = sys.argv[1:]
features = [l.strip() for l in open(f, encoding="utf-8") if l.strip()]
print(json.dumps({"versionName": name, "versionCode": int(code), "branch": branch, "commit": commit,
                  "published": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
                  "features": features}, ensure_ascii=False))
PY

if [ "${GITHUB:-1}" != 0 ]; then
    {
        echo "<!-- dev: $(cat "$tmp/entry.json") -->"
        echo "Test-Build vom Zweig \`$BRANCH\` ($commit). ThorBro-Test-Builds holen sich das als Update; normale Versionen ignorieren es."
        echo
        if [ -s "$tmp/features" ]; then echo "**Features seit main**"; echo; sed 's/^/- /' "$tmp/features"; fi
    } > "$tmp/notes.md"
    cp "$APK" "$tmp/ThorBro-dev.apk"
    gh release create "dev-$code" "$tmp/ThorBro-dev.apk" --prerelease --title "ThorBro $name" --target "$(git rev-parse HEAD)" \
      --notes-file "$tmp/notes.md"
    KEEP=${KEEP:-5}
    gh release list --limit 100 --json tagName -q '.[].tagName' | grep -E '^dev-[0-9]+$' | sort -t- -k2 -n -r | tail -n +$((KEEP + 1)) |
      while read -r t; do gh release delete "$t" --cleanup-tag -y >/dev/null && echo "altes $t entfernt"; done
    echo "Test-Build: $name (dev-$code) auf GitHub"
fi

# ---- home network: LXC 107 on g6 (no direct scp into the container, so through pct exec) ----
DEV_SSH=${DEV_SSH:-g6}
DEV_CT=${DEV_CT:-107}
DEV_DIR=/opt/thorbro-dev/www/thorbro
DEV_URL=${DEV_URL:-http://192.168.178.97:8097/thorbro}
in_ct() { ssh -o ConnectTimeout=5 -o BatchMode=yes "$DEV_SSH" "pct exec $DEV_CT -- sh -c '$1'"; }
if [ "${HOMENET:-1}" != 0 ]; then
    if ! in_ct "test -d $DEV_DIR" < /dev/null 2>/dev/null; then
        echo "WARNUNG: Heimnetz ($DEV_SSH / LXC $DEV_CT) nicht erreichbar – nur GitHub" >&2
    else
        file="thorbro-$code.apk"
        in_ct "cat > $DEV_DIR/$file.part && mv $DEV_DIR/$file.part $DEV_DIR/$file" < "$APK"
        in_ct "cat $DEV_DIR/index.json 2>/dev/null || true" < /dev/null > "$tmp/index.json"
        # newest first, same versionCode replaced, max 10; prints the APK files that are no longer listed
        python3 - "$tmp/index.json" "$tmp/entry.json" "$DEV_URL/$file" "$(wc -c < "$APK" | tr -d ' ')" > "$tmp/drop" <<'PY'
import json, sys
idx, entry, url, size = sys.argv[1:]
try: data = json.load(open(idx, encoding="utf-8"))
except Exception: data = {}
e = json.load(open(entry, encoding="utf-8"))
e["url"], e["size"] = url, int(size)
old = [v for v in data.get("versions", []) if v.get("versionCode") != e["versionCode"]]
versions = sorted([e] + old, key=lambda v: v.get("versionCode", 0), reverse=True)
keep, drop = versions[:10], versions[10:]
json.dump({"app": "thorbro", "versions": keep}, open(idx, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
for v in drop: print(v.get("url", "").rsplit("/", 1)[-1])
PY
        in_ct "cat > $DEV_DIR/index.json.part && mv $DEV_DIR/index.json.part $DEV_DIR/index.json" < "$tmp/index.json"
        while read -r f; do
            case "$f" in thorbro-[0-9]*.apk) in_ct "rm -f $DEV_DIR/$f" < /dev/null && echo "altes $f im Heimnetz entfernt" ;; esac
        done < "$tmp/drop"
        echo "Test-Build: $name im Heimnetz ($DEV_URL/index.json)"
    fi
fi
