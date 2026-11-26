#!/bin/sh
# Builds the Eden (Switch/LDN) room server: arm64 Android for the Thor (into the APK's native libs) and, with --mac,
# the Mac binary for RomBro (build/mac/eden-room). Needs the Android NDK + cmake (SDK) or Homebrew cmake.
# Eden source: tag below (network_version must match the Eden the players use), fetched into companion/room/build/.
set -eu
EDEN_TAG=v0.2.1
ENET_TAG=v1.3.18
FMT_TAG=10.2.1
ROOT=$(cd "$(dirname "$0")/.." && pwd)
B=$ROOT/companion/room/build
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
NDK=${ANDROID_NDK:-$SDK/ndk/$(ls "$SDK/ndk" | sort -V | tail -1)}
CMAKE=$(ls -d "$SDK"/cmake/*/bin 2>/dev/null | sort -V | tail -1)/cmake
[ -x "$CMAKE" ] || CMAKE=cmake
mkdir -p "$B"
if [ ! -d "$B/eden/.git" ]; then
  git clone --depth 1 --filter=blob:none --no-checkout https://git.eden-emu.dev/eden-emu/eden.git "$B/eden"
fi
git -C "$B/eden" sparse-checkout set --no-cone /src/network /src/common/announce_multiplayer_room.h /src/common/socket_types.h /src/common/polyfill_thread.h
git -C "$B/eden" fetch -q --depth 1 origin tag "$EDEN_TAG"
git -C "$B/eden" checkout -q "$EDEN_TAG"
[ -d "$B/enet" ] || git clone -q --depth 1 --branch "$ENET_TAG" https://github.com/lsalzman/enet.git "$B/enet"
[ -d "$B/fmt" ] || git clone -q --depth 1 --branch "$FMT_TAG" https://github.com/fmtlib/fmt.git "$B/fmt"
SRC=$ROOT/companion/room/eden
if [ "${1:-}" = "--mac" ]; then
  OUT=$B/mac
  "$CMAKE" -S "$SRC" -B "$OUT" -DEDEN="$B/eden" -DENET="$B/enet" -DFMT="$B/fmt" -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_OSX_ARCHITECTURES=arm64 -DCMAKE_OSX_DEPLOYMENT_TARGET=13.0
  "$CMAKE" --build "$OUT" -j8
  echo "ok: $OUT/eden-room"
  exit 0
fi
OUT=$B/eden-arm64
"$CMAKE" -S "$SRC" -B "$OUT" -DEDEN="$B/eden" -DENET="$B/enet" -DFMT="$B/fmt" -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=30
"$CMAKE" --build "$OUT" -j8
DEST=$ROOT/companion/app/src/main/jniLibs/arm64-v8a
mkdir -p "$DEST"
cp "$OUT/eden-room" "$DEST/libedenroom.so"
echo "ok: $DEST/libedenroom.so ($(wc -c < "$DEST/libedenroom.so") bytes)"
