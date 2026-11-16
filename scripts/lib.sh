# Shared helpers for the ThorBro scripts (sourced, not run).
# Several chats/sessions work in parallel git worktrees but share ONE Thor - see CLAUDE.md.

MAIN_ROOT=$(git -C "$ROOT" worktree list --porcelain | sed -n '1s/^worktree //p')
BRANCH=$(git -C "$ROOT" rev-parse --abbrev-ref HEAD)
CONF="$HOME/.config/thorbro"
LOCK="$CONF/deploy.lock"
LOCK_MIN=${LOCK_MIN:-30}
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"

desktop_json="$HOME/Library/Application Support/ThorBro/desktop.json"
json_thor() { [ -f "$desktop_json" ] && python3 -c 'import json,sys;t=json.load(open(sys.argv[1]))["thors"][0];print(t[sys.argv[2]])' "$desktop_json" "$1" 2>/dev/null || true; }

# address and pairing code: env > ~/.config/thorbro/code > the desktop app's pairing
THOR="${THOR:-$(json_thor address | sed 's/:7330$//')}"
THOR="${THOR:-192.168.178.67}"
CODE="${THORBRO_CODE:-$(cat "$CONF/code" 2>/dev/null || json_thor token)}"

die() { echo "$*" >&2; exit 1; }

# refuse to replace another branch's build that was installed less than LOCK_MIN minutes ago
check_lock() {
    [ -f "$LOCK" ] || return 0
    IFS='|' read -r lb lt lw < "$LOCK"
    age=$(( ($(date +%s) - lt) / 60 ))
    if [ "$lb" != "$BRANCH" ] && [ "$age" -lt "$LOCK_MIN" ] && [ "${FORCE:-0}" != 1 ]; then
        die "Der Thor ist belegt: $lb hat vor $age min aufgespielt ($lw).
Warten (frei nach $LOCK_MIN min), mit dem anderen Chat absprechen, oder FORCE=1 $0 zum Übernehmen."
    fi
}

write_lock() { mkdir -p "$CONF"; echo "$BRANCH|$(date +%s)|$ROOT" > "$LOCK"; }

build_apk() { (cd "$ROOT/companion" && ./gradlew assembleRelease -q) || die "Build fehlgeschlagen"; }
APK="$ROOT/companion/app/build/outputs/apk/release/app-release.apk"
