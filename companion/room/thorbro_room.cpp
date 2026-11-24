// Azahar (3DS) multiplayer room for ThorBro: the room part of Azahar's dedicated room server,
// without the public lobby, web service and ban list, so it runs on the Thor itself (no internet needed).
//
//   azahar-room <port> <room name> <max members> [password]
//
// Prints "ready <port>" once the room listens and one line per join/leave; ends on SIGTERM/SIGINT or stdin EOF.
#include <atomic>
#include <chrono>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <memory>
#include <string>
#include <thread>
#include <unistd.h>
#include "enet/enet.h"
#include "network/room.h"
#include "network/verify_user.h"

static std::atomic<bool> stop{false};

int main(int argc, char** argv) {
    if (argc < 4) {
        std::fprintf(stderr, "usage: %s <port> <name> <max members> [password]\n", argv[0]);
        return 2;
    }
    const int port = std::atoi(argv[1]);
    const std::string name = argv[2];
    const int members = std::atoi(argv[3]);
    const std::string password = argc > 4 ? argv[4] : "";
    if (port <= 0 || port > 65535 || members < 2 || members > 16) {
        std::fprintf(stderr, "bad arguments\n");
        return 2;
    }
    std::signal(SIGTERM, [](int) { stop = true; });
    std::signal(SIGINT, [](int) { stop = true; });
    std::signal(SIGPIPE, SIG_IGN);
    if (enet_initialize() != 0) {
        std::fprintf(stderr, "enet init failed\n");
        return 1;
    }
    Network::Room room;
    if (!room.Create(name, "ThorBro", "", static_cast<u16>(port), password, static_cast<u32>(members), "ThorBro", "", 0,
                     std::make_unique<Network::VerifyUser::NullBackend>())) { // a join calls the backend: never null
        std::printf("error could not open port %d\n", port);
        std::fflush(stdout);
        enet_deinitialize();
        return 1;
    }
    std::printf("ready %d\n", port);
    std::fflush(stdout);
    // the parent (ThorBro) holds stdin open; when it goes away, the room goes too
    std::thread watch([] {
        char buf[64];
        while (read(0, buf, sizeof buf) > 0) {}
        stop = true;
    });
    watch.detach();
    // "members <n> <name>,<name>…" whenever the room changes, so ThorBro can show who is in
    std::string last = "-";
    while (!stop && room.GetState() == Network::Room::State::Open) {
        std::string now;
        const auto list = room.GetRoomMemberList();
        for (const auto& m : list) {
            if (!now.empty()) now += ",";
            now += m.nickname;
            if (!m.game_info.name.empty()) now += " (" + m.game_info.name + ")";
        }
        if (now != last) {
            std::printf("members %zu %s\n", list.size(), now.c_str());
            std::fflush(stdout);
            last = now;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(500));
    }
    room.Destroy();
    enet_deinitialize();
    std::printf("closed\n");
    return 0;
}
