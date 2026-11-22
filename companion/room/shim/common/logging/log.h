// Minimal stand-in for Azahar's logging: one line per message on stdout (ThorBro reads it).
#pragma once
#include <cstdio>
#include <fmt/format.h>
#define THORBRO_LOG(level, cls, ...) \
    do { std::fputs(fmt::format("[" level "] " __VA_ARGS__).append("\n").c_str(), stdout); std::fflush(stdout); } while (0)
#define LOG_TRACE(cls, ...) do {} while (0)
#define LOG_DEBUG(cls, ...) do {} while (0)
#define LOG_INFO(cls, ...) THORBRO_LOG("info", cls, __VA_ARGS__)
#define LOG_WARNING(cls, ...) THORBRO_LOG("warn", cls, __VA_ARGS__)
#define LOG_ERROR(cls, ...) THORBRO_LOG("error", cls, __VA_ARGS__)
#define LOG_CRITICAL(cls, ...) THORBRO_LOG("error", cls, __VA_ARGS__)
