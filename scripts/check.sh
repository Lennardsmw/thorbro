#!/bin/sh
# Run by `wt land` before merging: the release APK must build.
set -eu
ROOT=$(git rev-parse --show-toplevel)
. "$ROOT/scripts/lib.sh"
build_apk
