# Eden (Switch/LDN) room server for the Thor (arm64 Android) and the Mac (RomBro). Built by scripts/build-eden-room.sh,
# not by Gradle: on Android the result ships as jniLibs/arm64-v8a/libedenroom.so (only lib*.so are executable there).
cmake_minimum_required(VERSION 3.22)
project(eden_room CXX C)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(EDEN "" CACHE PATH "Eden source checkout (src/network, src/common)")
set(ENET "" CACHE PATH "ENet 1.3.x source")
set(FMT "" CACHE PATH "fmt source (header only)")

file(GLOB ENET_SRC ${ENET}/*.c)
add_library(enet STATIC ${ENET_SRC})
target_include_directories(enet PUBLIC ${ENET}/include)
target_compile_definitions(enet PRIVATE HAS_SOCKLEN_T=1 HAS_FCNTL=1 HAS_POLL=1 HAS_GETADDRINFO=1 HAS_GETNAMEINFO=1
    HAS_INET_PTON=1 HAS_INET_NTOP=1 HAS_MSGHDR_FLAGS=1)

add_executable(eden-room eden_room.cpp
    ${EDEN}/src/network/room.cpp ${EDEN}/src/network/packet.cpp ${EDEN}/src/network/verify_user.cpp)
# the shim comes first: it replaces Eden's logging and web service headers
target_include_directories(eden-room PRIVATE ${CMAKE_SOURCE_DIR}/shim ${EDEN}/src ${FMT}/include)
target_compile_definitions(eden-room PRIVATE FMT_HEADER_ONLY)
# std::jthread / std::stop_token are still "experimental" in the libc++ of NDK r27 and Apple clang
target_compile_options(eden-room PRIVATE -fexperimental-library)
target_link_options(eden-room PRIVATE -fexperimental-library)
target_link_libraries(eden-room PRIVATE enet)
if(APPLE)
# Apple clang rejects fmt's compile-time format checks inside format-inl.h: check at run time instead
target_compile_definitions(eden-room PRIVATE FMT_CONSTEVAL=)
endif()
if(ANDROID)
target_link_options(eden-room PRIVATE -static-libstdc++ -s)
endif()
