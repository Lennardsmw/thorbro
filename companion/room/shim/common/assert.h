#pragma once
#include <cstdlib>
#define ASSERT(x) do { if (!(x)) std::abort(); } while (0)
#define ASSERT_MSG(x, ...) ASSERT(x)
#define UNREACHABLE() std::abort()
#define UNREACHABLE_MSG(...) std::abort()
