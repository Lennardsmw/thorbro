# Azahar room server for the Thor (arm64 Android). Built by scripts/build-room.sh, not by Gradle:
# the result is an executable shipped as jniLibs/arm64-v8a/libazaharroom.so (Android only marks lib*.so as executable).
cmake_minimum_required(VERSION 3.22)
project(thorbro_room CXX C)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(AZAHAR "" CACHE PATH "Azahar source checkout (with externals/enet and externals/fmt)")

file(GLOB ENET_SRC ${AZAHAR}/externals/enet/*.c)
add_library(enet STATIC ${ENET_SRC})
target_include_directories(enet PUBLIC ${AZAHAR}/externals/enet/include)
target_compile_definitions(enet PRIVATE HAS_SOCKLEN_T=1 HAS_FCNTL=1 HAS_POLL=1 HAS_GETADDRINFO=1 HAS_GETNAMEINFO=1
    HAS_INET_PTON=1 HAS_INET_NTOP=1 HAS_MSGHDR_FLAGS=1)

add_executable(azahar-room thorbro_room.cpp
    ${AZAHAR}/src/network/room.cpp ${AZAHAR}/src/network/packet.cpp ${AZAHAR}/src/network/verify_user.cpp)
target_include_directories(azahar-room PRIVATE ${CMAKE_SOURCE_DIR}/shim ${AZAHAR}/src ${AZAHAR}/externals/fmt/include)
target_compile_definitions(azahar-room PRIVATE FMT_HEADER_ONLY)
target_link_libraries(azahar-room PRIVATE enet)
if(ANDROID)
target_link_options(azahar-room PRIVATE -static-libstdc++ -s)
endif()
