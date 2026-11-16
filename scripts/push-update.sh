#!/bin/sh
# Builds ThorBro and installs it on the Thor over Wi-Fi through ThorBro itself (no USB needed).
#   THOR=192.168.178.107 scripts/push-update.sh          (address defaults to the value below)
# The pairing code is read from $THORBRO_CODE or ~/.config/thorbro/code (one line, chmod 600).
# Confirm "Update" on the Thor if Android asks. Needs ThorBro >= 0.2.0 on the device.
set -eu
cd "$(dirname "$0")/../companion"
THOR="${THOR:-192.168.178.107}"
CODE="${THORBRO_CODE:-$(cat "$HOME/.config/thorbro/code" 2>/dev/null || true)}"
[ -n "$CODE" ] || { echo "Pairing code missing: set THORBRO_CODE or write it to ~/.config/thorbro/code" >&2; exit 1; }

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
./gradlew assembleRelease -q
APK=app/build/outputs/apk/release/app-release.apk

echo "Uploading $(du -h "$APK" | cut -f1) to $THOR ..."
curl -sS --fail-with-body -X POST \
  -H "Authorization: Bearer $CODE" \
  -H "Content-Type: application/vnd.android.package-archive" \
  --data-binary "@$APK" "http://$THOR:7330/api/self-update"
echo
echo "Confirm the update on the Thor. ThorBro restarts by itself afterwards."
