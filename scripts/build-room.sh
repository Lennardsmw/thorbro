#!/bin/sh
# Builds the Azahar room server for the Thor (arm64) and puts it into the APK's native libs.
# Needs the Android NDK and cmake (Android SDK). Azahar source: pinned commit below, fetched into build/.
set -eu
AZAHAR_COMMIT=6f293a24daa292bd02b4ffee1a2adb5435e1ae00
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
NDK=${ANDROID_NDK:-$SDK/ndk/$(ls "$SDK/ndk" | sort -V | tail -1)}
CMAKE=$(ls -d "$SDK"/cmake/*/bin 2>/dev/null | sort -V | tail -1)/cmake
[ -x "$CMAKE" ] || CMAKE=cmake
SRC=$ROOT/companion/room/build/azahar
if [ ! -d "$SRC/.git" ]; then
  git clone --filter=blob:none --no-checkout https://github.com/azahar-emu/azahar.git "$SRC"
fi
git -C "$SRC" checkout -q "$AZAHAR_COMMIT"
git -C "$SRC" submodule update --init --depth 1 externals/enet externals/fmt
# diagnosis: log every connection and every refused join (Azahar's room refuses silently)
git -C "$SRC" checkout -q -- src/network/room.cpp
perl -0pi -e 's/(void Room::RoomImpl::Send(NameCollision|MacCollision|ConsoleIdCollision|WrongPassword|RoomIsFull|VersionMismatch|UserBanned)\(ENetPeer\* client\) \{)/$1\n    LOG_INFO(Network, "join refused: $2");/g; s/(            case ENET_EVENT_TYPE_NONE:\n            case ENET_EVENT_TYPE_CONNECT:\n)/            case ENET_EVENT_TYPE_CONNECT:\n                LOG_INFO(Network, "connect from port {}", event.peer->address.port);\n                break;\n            case ENET_EVENT_TYPE_NONE:\n/' "$SRC/src/network/room.cpp"
OUT=$ROOT/companion/room/build/arm64
"$CMAKE" -S "$ROOT/companion/room" -B "$OUT" -DAZAHAR="$SRC" -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=30
"$CMAKE" --build "$OUT" -j8
DEST=$ROOT/companion/app/src/main/jniLibs/arm64-v8a
mkdir -p "$DEST"
cp "$OUT/azahar-room" "$DEST/libazaharroom.so"
echo "ok: $DEST/libazaharroom.so ($(wc -c < "$DEST/libazaharroom.so") bytes)"
