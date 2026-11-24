# Azahar-Raumserver für den Thor

3DS-Multiplayer ohne Internet: ThorBro startet auf dem Host-Thor einen Azahar-Raum (Port 24872),
beide Thors treten ihm in Azahar bei.

- `thorbro_room.cpp`: kleines `main` um Azahars `Network::Room` (ohne öffentliche Lobby, Web-Service, Bannliste).
- `shim/`: Ersatz für die wenigen Azahar-`common/`-Header, die der Raum braucht (Logging auf stdout).
- Bauen: `scripts/build-room.sh` (Android-NDK + CMake aus dem SDK) → `app/src/main/jniLibs/arm64-v8a/libazaharroom.so`.
  Die Azahar-Quellen (fester Commit im Skript) werden nach `room/build/` geholt.

Lizenz: Der Raumserver enthält Code aus [Azahar](https://github.com/azahar-emu/azahar) (GPLv2) und ENet (MIT)
und steht damit unter GPLv2. Er ist ein eigenes Programm, das ThorBro nur startet; ThorBro selbst bleibt MIT.
